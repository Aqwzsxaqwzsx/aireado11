package com.aireado.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts selectable PDF text and paragraphs from Microsoft Word .docx files. */
public final class DocumentLoader {
    public interface ProgressListener {
        void onProgress(int page, int totalPages);
    }

    private DocumentLoader() { }

    public static String read(Context context, Uri uri, String mimeType, ProgressListener progress) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("The selected document could not be opened.");
            if (mimeType != null && mimeType.toLowerCase().contains("pdf")) {
                return readPdf(context, input, progress);
            }
            return readDocx(input);
        }
    }

    private static String readPdf(Context context, InputStream input, ProgressListener progress) throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        File pdf = File.createTempFile("aireado-import-", ".pdf", context.getCacheDir());
        try {
            try (OutputStream output = new FileOutputStream(pdf)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            try (PDDocument document = PDDocument.load(pdf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setPageEnd("\n\n");
                String embeddedText = normalize(stripper.getText(document));
                if (embeddedText.replaceAll("\\s", "").length() >= 30) return embeddedText;
            }
            return ocrPdf(context, pdf, progress);
        } finally {
            // The extracted text is stored privately by BookStore; the temporary PDF is discarded.
            pdf.delete();
        }
    }

    private static String ocrPdf(Context context, File pdf, ProgressListener progress) throws Exception {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        StringBuilder text = new StringBuilder();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int total = renderer.getPageCount();
            for (int pageNumber = 0; pageNumber < total; pageNumber++) {
                if (progress != null) progress.onProgress(pageNumber + 1, total);
                try (PdfRenderer.Page page = renderer.openPage(pageNumber)) {
                    float scale = Math.min(2.2f, 2100f / Math.max(page.getWidth(), page.getHeight()));
                    int width = Math.max(1, Math.round(page.getWidth() * scale));
                    int height = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Matrix matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try {
                        String pageText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
                        if (pageText != null && !pageText.trim().isEmpty()) text.append(pageText).append("\n\n");
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        } finally {
            recognizer.close();
        }
        return normalize(text.toString());
    }

    private static String readDocx(InputStream input) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                    parser.setInput(zip, "UTF-8");
                    StringBuilder text = new StringBuilder();
                    boolean insideParagraph = false;
                    int event;
                    while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG) {
                            String name = parser.getName();
                            if ("p".equals(name)) {
                                if (insideParagraph && text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                                    text.append('\n');
                                }
                                insideParagraph = true;
                            } else if ("tab".equals(name)) {
                                text.append('\t');
                            } else if ("br".equals(name) || "cr".equals(name)) {
                                text.append('\n');
                            } else if ("t".equals(name)) {
                                text.append(parser.nextText());
                            }
                        } else if (event == XmlPullParser.END_TAG && "p".equals(parser.getName())) {
                            text.append("\n\n");
                            insideParagraph = false;
                        }
                    }
                    return normalize(text.toString());
                }
            }
        }
        throw new IllegalArgumentException("This Word file is not a .docx document.");
    }

    public static String normalize(String text) {
        if (text == null) return "";
        String normalized = text.replace('\u0000', ' ')
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return normalized;
    }
}
