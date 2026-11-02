package com.aireado.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-first PDF and Word reader for Android 9 and newer. */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_DOCUMENT = 401;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private BookStore store;
    private VoiceDirector voiceDirector;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean dark = true;
    private float fontSizeSp = 21f;
    private float speechRate = 1.0f;
    private boolean serifFont = true;

    private LinearLayout root;
    private LinearLayout readerBody;
    private ScrollView readerScroll;
    private ProgressBar progressBar;
    private TextView loadingMessage;
    private TextView progressLabel;
    private TextView timeLabel;
    private TextView playButton;
    private TextView activeSpeakerLabel;
    private final ArrayList<TextView> paragraphViews = new ArrayList<>();
    private final ArrayList<Integer> paragraphStarts = new ArrayList<>();

    private BookStore.Book currentBook;
    private String currentText = "";
    private ArrayList<SpeechChunker.Chunk> chunks = new ArrayList<>();
    private int currentChunkIndex = 0;
    private int activeWordStart = -1;
    private int activeWordEnd = -1;
    private boolean reading = false;
    private long listenedMs = 0L;
    private long chunkStartedAt = 0L;
    private String activeUtteranceId = "";
    private boolean lightModeAtLaunch = false;

    private int bg;
    private int surface;
    private int primary;
    private int secondary;
    private int accent;
    private int highlight;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new BookStore(this);
        voiceDirector = new VoiceDirector(this);
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark", true);
        fontSizeSp = getPreferences(MODE_PRIVATE).getFloat("font_size", 21f);
        speechRate = getPreferences(MODE_PRIVATE).getFloat("speech_rate", 1.0f);
        serifFont = getPreferences(MODE_PRIVATE).getBoolean("serif", true);
        applyPalette();
        tts = new TextToSpeech(this, this);
        showLibrary();
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            runOnUiThread(() -> Toast.makeText(this, "The phone's speech engine could not start.", Toast.LENGTH_LONG).show());
            return;
        }
        ttsReady = true;
        tts.setSpeechRate(speechRate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                runOnUiThread(() -> {
                    if (!utteranceId.equals(activeUtteranceId)) return;
                    chunkStartedAt = SystemClock.elapsedRealtime();
                    updateReaderStatus();
                });
            }

            @Override public void onDone(String utteranceId) {
                runOnUiThread(() -> {
                    if (!reading || !utteranceId.equals(activeUtteranceId)) return;
                    if (chunkStartedAt > 0L) listenedMs += SystemClock.elapsedRealtime() - chunkStartedAt;
                    chunkStartedAt = 0L;
                    currentChunkIndex++;
                    activeWordStart = -1;
                    activeWordEnd = -1;
                    savePosition();
                    if (currentChunkIndex < chunks.size()) {
                        highlightActiveChunk(true);
                        speakCurrentChunk();
                    } else {
                        reading = false;
                        updatePlayButton();
                        updateReaderStatus();
                        Toast.makeText(MainActivity.this, "You reached the end of this book.", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override public void onError(String utteranceId) {
                runOnUiThread(() -> {
                    if (utteranceId.equals(activeUtteranceId)) {
                        reading = false;
                        chunkStartedAt = 0L;
                        updatePlayButton();
                        Toast.makeText(MainActivity.this, "This offline voice could not read the passage.", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                runOnUiThread(() -> {
                    if (!utteranceId.equals(activeUtteranceId) || currentChunkIndex >= chunks.size()) return;
                    SpeechChunker.Chunk chunk = chunks.get(currentChunkIndex);
                    activeWordStart = Math.max(0, Math.min(start, chunk.text.length()));
                    activeWordEnd = Math.max(activeWordStart, Math.min(end, chunk.text.length()));
                    renderHighlights(false);
                });
            }
        });
        if (currentBook != null) showReader(currentBook, currentText);
        else showLibrary();
    }

    private void applyPalette() {
        if (dark) {
            bg = 0xFF17181E;
            surface = 0xFF242630;
            primary = 0xFFF1E9DF;
            secondary = 0xFFAAAAB4;
            accent = 0xFFE0A56E;
            highlight = 0xFF57412A;
        } else {
            bg = 0xFFF5F0E9;
            surface = 0xFFFFFFFF;
            primary = 0xFF22232A;
            secondary = 0xFF6E7079;
            accent = 0xFF9C5D36;
            highlight = 0xFFFFE6B8;
        }
        getWindow().setStatusBarColor(dark ? 0xFF17181E : 0xFFF5F0E9);
        getWindow().setNavigationBarColor(dark ? 0xFF17181E : 0xFFF5F0E9);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void showLibrary() {
        applyPalette();
        root = column();
        root.setPadding(dp(22), dp(22), dp(22), dp(20));
        setContentView(root);

        LinearLayout top = row();
        TextView brand = label("A I R E A D O", 12, accent, true);
        brand.setLetterSpacing(0.14f);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(chip(dark ? "☼" : "☾", () -> toggleTheme()), wrap());
        root.addView(top);

        TextView title = label("Your books,\nread aloud.", 31, primary, true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(30);
        root.addView(title, titleParams);

        TextView subtitle = label("Private on your phone. Ready for offline reading.", 14, secondary, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(8);
        root.addView(subtitle, subParams);

        LinearLayout actions = row();
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(24);
        root.addView(actions, actionsParams);
        actions.addView(actionCard("Open PDF", "Text PDFs", () -> openPicker("application/pdf")), new LinearLayout.LayoutParams(0, dp(92), 1));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        actions.addView(actionCard("Open Word", ".docx files", () -> openPicker("application/vnd.openxmlformats-officedocument.wordprocessingml.document")), new LinearLayout.LayoutParams(0, dp(92), 1));

        TextView offlineStatus = label(offlineVoiceSummary(), 13, secondary, false);
        offlineStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        offlineStatus.setBackground(roundRect(surface, dp(16)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(16);
        root.addView(offlineStatus, statusParams);
        offlineStatus.setOnClickListener(v -> installOfflineVoiceData());

        LinearLayout section = row();
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.topMargin = dp(30);
        root.addView(section, sectionParams);
        section.addView(label("Recently opened", 18, primary, true), new LinearLayout.LayoutParams(0, -2, 1));
        section.addView(chip("Voices", () -> showVoiceHelp()), wrap());

        ScrollView shelf = new ScrollView(this);
        LinearLayout.LayoutParams shelfParams = new LinearLayout.LayoutParams(-1, 0, 1);
        shelfParams.topMargin = dp(12);
        root.addView(shelf, shelfParams);
        LinearLayout cards = column();
        shelf.addView(cards);

        ArrayList<BookStore.Book> books = store.list();
        if (books.isEmpty()) {
            TextView empty = label("Your library is empty. Open a PDF or Word document to begin.", 15, secondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(40), dp(24), dp(40));
            empty.setBackground(roundRect(surface, dp(18)));
            cards.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (BookStore.Book book : books) {
                TextView card = label(book.title + "\n" + book.sourceName + "   ·   " + progressSummary(book), 15, primary, true);
                card.setMaxLines(3);
                card.setPadding(dp(17), dp(16), dp(17), dp(16));
                card.setBackground(roundRect(surface, dp(18)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(10);
                cards.addView(card, params);
                card.setOnClickListener(v -> loadBook(book, false));
                card.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle(book.title)
                            .setItems(new String[]{"Resume reading", "Open bookmark", "Remove from library"}, (dialog, which) -> {
                                if (which == 0) loadBook(book, false);
                                else if (which == 1) loadBook(book, true);
                                else confirmRemove(book);
                            }).show();
                    return true;
                });
            }
        }

        TextView footer = label("Aireado  ·  books stay on this device", 11, secondary, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(12);
        root.addView(footer, footerParams);
    }

    private View actionCard(String heading, String subheading, Runnable action) {
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(surface, dp(18)));
        card.addView(label(heading, 16, primary, true));
        TextView sub = label(subheading, 12, secondary, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(5);
        card.addView(sub, subParams);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private void openPicker(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_DOCUMENT);
        } catch (Exception error) {
            Toast.makeText(this, "A file picker is not available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_DOCUMENT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        String mime = getContentResolver().getType(uri);
        String name = displayName(uri);
        showLoading("Opening " + name + "…");
        io.execute(() -> {
            try {
                String text = DocumentLoader.read(this, uri, mime, (page, total) ->
                        runOnUiThread(() -> updateLoading("Reading scanned page " + page + " of " + total + " offline…")));
                if (text.trim().length() < 30) {
                    throw new IllegalArgumentException("No readable text was found. The PDF may be damaged, protected, or too low quality for offline OCR.");
                }
                BookStore.Book book = store.add(name, fileType(name), text);
                runOnUiThread(() -> showReader(book, text));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showLibrary();
                    String message = error.getMessage() == null ? "Could not read this document." : error.getMessage();
                    new AlertDialog.Builder(this).setTitle("Could not open document")
                            .setMessage(message).setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private String displayName(Uri uri) {
        String name = "Book";
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) { }
        return name == null || name.trim().isEmpty() ? "Book" : name;
    }

    private String fileType(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "PDF" : "Word document";
    }

    private void showLoading(String message) {
        applyPalette();
        root = column();
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(26), dp(26), dp(26), dp(26));
        loadingMessage = label(message, 17, primary, true);
        loadingMessage.setGravity(Gravity.CENTER);
        root.addView(loadingMessage);
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        p.gravity = Gravity.CENTER;
        p.topMargin = dp(18);
        root.addView(spinner, p);
        setContentView(root);
    }

    private void updateLoading(String message) {
        if (loadingMessage != null) loadingMessage.setText(message);
    }

    private void loadBook(BookStore.Book book, boolean openBookmark) {
        showLoading("Preparing your book…");
        io.execute(() -> {
            try {
                String text = store.loadText(book.id);
                runOnUiThread(() -> {
                    if (openBookmark && book.bookmarkSegment >= 0) book.progressSegment = book.bookmarkSegment;
                    showReader(book, text);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showLibrary();
                    Toast.makeText(this, "The saved text for this book could not be opened.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showReader(BookStore.Book book, String text) {
        if (tts != null) {
            activeUtteranceId = "";
            tts.stop();
        }
        reading = false;
        currentBook = book;
        currentText = DocumentLoader.normalize(text);
        chunks = SpeechChunker.split(currentText);
        currentChunkIndex = Math.max(0, Math.min(book.progressSegment, Math.max(0, chunks.size() - 1)));
        listenedMs = book.elapsedMs;
        activeWordStart = -1;
        activeWordEnd = -1;
        renderReader();
        updateReaderStatus();
    }

    private void renderReader() {
        applyPalette();
        root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        setContentView(root);

        LinearLayout header = row();
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
        header.addView(chip("‹", this::showLibrary), wrap());
        LinearLayout heading = column();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -1, 1);
        headingParams.leftMargin = dp(10);
        header.addView(heading, headingParams);
        TextView title = label(currentBook.title, 15, primary, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title);
        heading.addView(label("NOW READING", 9, secondary, true));
        header.addView(chip("Cast", this::showCastDialog), wrap());
        header.addView(chip("Aa", this::showDisplaySettings), wrap());

        progressLabel = label("Preparing reading position…", 12, secondary, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        root.addView(progressLabel, p);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(Math.max(1, chunks.size()));
        progressBar.setProgress(currentChunkIndex);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, dp(4));
        pb.topMargin = dp(8);
        root.addView(progressBar, pb);
        timeLabel = label("", 11, secondary, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        tp.bottomMargin = dp(6);
        root.addView(timeLabel, tp);

        readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(readerScroll, scrollParams);
        readerBody = column();
        readerBody.setPadding(dp(5), dp(10), dp(5), dp(24));
        readerScroll.addView(readerBody);
        buildParagraphViews();

        TextView speakerLine = label("", 11, secondary, false);
        activeSpeakerLabel = speakerLine;
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(6);
        root.addView(speakerLine, sl);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), dp(9), dp(6), dp(2));
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(66)));
        controls.addView(chip("‹‹", () -> seekSegment(-1)), wrap());
        TextView play = chip("▶", this::togglePlayback);
        playButton = play;
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(60), dp(48));
        playParams.leftMargin = dp(8);
        playParams.rightMargin = dp(8);
        controls.addView(play, playParams);
        controls.addView(chip("››", () -> seekSegment(1)), wrap());
        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(chip("−", () -> changeSpeed(-0.1f)), wrap());
        TextView rate = label(String.format(Locale.US, "%.1fx", speechRate), 12, primary, true);
        rate.setGravity(Gravity.CENTER);
        rate.setTag("speedLabel");
        controls.addView(rate, new LinearLayout.LayoutParams(dp(42), -2));
        controls.addView(chip("+", () -> changeSpeed(0.1f)), wrap());
        controls.addView(chip("▱", this::saveCurrentBookmark), wrap());

        highlightActiveChunk(false);
        updatePlayButton();
        updateReaderStatus();
    }

    private void buildParagraphViews() {
        paragraphViews.clear();
        paragraphStarts.clear();
        int offset = 0;
        String[] paragraphs = currentText.split("\\n\\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                offset += paragraph.length() + 2;
                continue;
            }
            TextView view = label(paragraph, fontSizeSp, primary, false);
            view.setTypeface(Typeface.create(serifFont ? "serif" : "sans-serif", Typeface.NORMAL));
            view.setLineSpacing(dp(6), 1.08f);
            view.setTextIsSelectable(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(20);
            readerBody.addView(view, params);
            paragraphViews.add(view);
            paragraphStarts.add(offset);
            offset += paragraph.length() + 2;
        }
    }

    private void togglePlayback() {
        if (reading) {
            pausePlayback();
            return;
        }
        if (!ttsReady || tts == null) {
            Toast.makeText(this, "Starting the offline speech engine…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (chunks.isEmpty()) return;
        if (currentChunkIndex >= chunks.size()) currentChunkIndex = 0;
        reading = true;
        highlightActiveChunk(true);
        speakCurrentChunk();
        updatePlayButton();
    }

    private void speakCurrentChunk() {
        if (!reading || currentBook == null || currentChunkIndex >= chunks.size()) return;
        SpeechChunker.Chunk chunk = chunks.get(currentChunkIndex);
        Voice voice = voiceDirector.forChunk(tts, currentBook.id, chunk);
        if (voice == null) {
            reading = false;
            updatePlayButton();
            String language = "fr".equals(chunk.language) ? "French" : "English";
            new AlertDialog.Builder(this).setTitle("Offline voice needed")
                    .setMessage("Install an offline " + language + " voice in Android Text-to-speech settings, then return here. Aireado will only use voices that do not need a network connection.")
                    .setPositiveButton("Voice settings", (dialog, which) -> installOfflineVoiceData())
                    .setNegativeButton("Later", null).show();
            return;
        }
        tts.setVoice(voice);
        tts.setSpeechRate(speechRate);
        activeUtteranceId = currentBook.id + "-" + currentChunkIndex + "-" + SystemClock.elapsedRealtime();
        chunkStartedAt = 0L;
        tts.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        updatePlayButton();
    }

    private void pausePlayback() {
        reading = false;
        if (chunkStartedAt > 0L) listenedMs += SystemClock.elapsedRealtime() - chunkStartedAt;
        chunkStartedAt = 0L;
        activeUtteranceId = "";
        if (tts != null) tts.stop();
        savePosition();
        updatePlayButton();
        updateReaderStatus();
    }

    private void seekSegment(int delta) {
        if (chunks.isEmpty()) return;
        if (reading) pausePlayback();
        currentChunkIndex = Math.max(0, Math.min(chunks.size() - 1, currentChunkIndex + delta));
        activeWordStart = -1;
        activeWordEnd = -1;
        highlightActiveChunk(true);
        savePosition();
        updateReaderStatus();
    }

    private void changeSpeed(float delta) {
        speechRate = Math.max(0.7f, Math.min(1.5f, Math.round((speechRate + delta) * 10f) / 10f));
        getPreferences(MODE_PRIVATE).edit().putFloat("speech_rate", speechRate).apply();
        if (tts != null) tts.setSpeechRate(speechRate);
        if (root != null) {
            View rate = root.findViewWithTag("speedLabel");
            if (rate instanceof TextView) ((TextView) rate).setText(String.format(Locale.US, "%.1fx", speechRate));
        }
        updateReaderStatus();
    }

    private void saveCurrentBookmark() {
        if (currentBook == null) return;
        store.saveBookmark(currentBook.id, currentChunkIndex);
        Toast.makeText(this, "Bookmark saved at this reading position.", Toast.LENGTH_SHORT).show();
    }

    private void updatePlayButton() {
        if (playButton != null) playButton.setText(reading ? "Ⅱ" : "▶");
    }

    private void updateReaderStatus() {
        if (currentBook == null || progressLabel == null || timeLabel == null) return;
        int count = Math.max(1, chunks.size());
        int current = Math.min(count, currentChunkIndex + 1);
        int percent = chunks.isEmpty() ? 0 : Math.min(100, currentChunkIndex * 100 / chunks.size());
        progressLabel.setText("PART " + current + " OF " + count + "   ·   " + percent + "%");
        if (progressBar != null) {
            progressBar.setMax(count);
            progressBar.setProgress(Math.min(currentChunkIndex, count));
        }
        int totalWords = Math.max(1, currentBook.wordCount);
        int readWords = 0;
        if (currentChunkIndex < chunks.size()) {
            int charPos = chunks.get(currentChunkIndex).start;
            readWords = countWords(currentText.substring(0, Math.max(0, Math.min(charPos, currentText.length()))));
        } else readWords = totalWords;
        long totalSeconds = Math.round(totalWords * 60.0 / (165.0 * speechRate));
        long remainingSeconds = Math.round(Math.max(0, totalWords - readWords) * 60.0 / (165.0 * speechRate));
        timeLabel.setText(formatMinutes(totalSeconds) + " estimated total   ·   " + formatMinutes(remainingSeconds) + " remaining   ·   " + formatMinutes(listenedMs / 1000L) + " listened");
        if (activeSpeakerLabel != null && currentChunkIndex < chunks.size()) {
            SpeechChunker.Chunk chunk = chunks.get(currentChunkIndex);
            String role = chunk.dialogue ? "Character" : "Narrator";
            String who = chunk.dialogue && !chunk.speaker.isEmpty() ? chunk.speaker : role;
            activeSpeakerLabel.setText((reading ? "●  " : "") + "Voice: " + who + "  ·  " + ("fr".equals(chunk.language) ? "French" : "English"));
        }
    }

    private String progressSummary(BookStore.Book book) {
        if (book.wordCount <= 0) return "Not started";
        if (book.progressSegment == 0) return "Not started  ·  " + formatMinutes(book.elapsedMs / 1000L) + " listened";
        return "Resume at part " + (book.progressSegment + 1) + "  ·  " + formatMinutes(book.elapsedMs / 1000L) + " listened";
    }

    private void highlightActiveChunk(boolean scrollTo) {
        if (chunks.isEmpty() || currentChunkIndex >= chunks.size()) return;
        activeWordStart = -1;
        activeWordEnd = -1;
        renderHighlights(scrollTo);
    }

    private void renderHighlights(boolean scrollTo) {
        if (currentChunkIndex >= chunks.size() || paragraphViews.isEmpty()) return;
        SpeechChunker.Chunk chunk = chunks.get(currentChunkIndex);
        int chunkGlobalWordStart = activeWordStart >= 0 ? chunk.start + activeWordStart : -1;
        int chunkGlobalWordEnd = activeWordEnd >= 0 ? chunk.start + activeWordEnd : -1;
        int activeParagraph = -1;
        for (int i = 0; i < paragraphViews.size(); i++) {
            int start = paragraphStarts.get(i);
            String original = paragraphViews.get(i).getText().toString();
            int end = start + original.length();
            android.text.SpannableString spannable = new android.text.SpannableString(original);
            if (chunk.start < end && chunk.end > start) {
                activeParagraph = i;
                int localStart = Math.max(0, chunk.start - start);
                int localEnd = Math.min(original.length(), chunk.end - start);
                if (localEnd > localStart) spannable.setSpan(new android.text.style.BackgroundColorSpan(highlight), localStart, localEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (chunkGlobalWordStart >= start && chunkGlobalWordEnd <= end && chunkGlobalWordEnd > chunkGlobalWordStart) {
                    spannable.setSpan(new android.text.style.BackgroundColorSpan(accent), chunkGlobalWordStart - start, chunkGlobalWordEnd - start, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new android.text.style.ForegroundColorSpan(dark ? 0xFF17181E : 0xFFFFFFFF), chunkGlobalWordStart - start, chunkGlobalWordEnd - start, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            paragraphViews.get(i).setText(spannable);
        }
        if (activeParagraph >= 0 && scrollTo) {
            TextView view = paragraphViews.get(activeParagraph);
            readerScroll.post(() -> readerScroll.smoothScrollTo(0, Math.max(0, view.getTop() - dp(24))));
            AlphaAnimation pulse = new AlphaAnimation(0.68f, 1f);
            pulse.setDuration(420);
            view.startAnimation(pulse);
        }
        updateReaderStatus();
    }

    private void showDisplaySettings() {
        String[] options = {dark ? "Switch to light theme" : "Switch to dark theme", "Make text smaller", "Make text larger", "Reader font: " + (serifFont ? "serif" : "sans serif")};
        new AlertDialog.Builder(this).setTitle("Display options").setItems(options, (dialog, which) -> {
            if (which == 0) toggleTheme();
            else if (which == 1) setFontSize(fontSizeSp - 1f);
            else if (which == 2) setFontSize(fontSizeSp + 1f);
            else showFontPicker();
        }).show();
    }

    private void showFontPicker() {
        String[] fonts = {"Book serif", "Clean sans serif"};
        new AlertDialog.Builder(this).setTitle("Reading font").setItems(fonts, (dialog, which) -> {
            serifFont = which == 0;
            getPreferences(MODE_PRIVATE).edit().putBoolean("serif", serifFont).apply();
            fontSizeSp = Math.max(16f, fontSizeSp);
            renderReader();
        }).show();
    }

    private void setFontSize(float size) {
        fontSizeSp = Math.max(16f, Math.min(32f, size));
        getPreferences(MODE_PRIVATE).edit().putFloat("font_size", fontSizeSp).apply();
        renderReader();
    }

    private void toggleTheme() {
        dark = !dark;
        getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
        applyPalette();
        if (currentBook != null) renderReader(); else showLibrary();
    }

    private void showCastDialog() {
        if (chunks.isEmpty()) {
            Toast.makeText(this, "No character voices were found in this book.", Toast.LENGTH_SHORT).show();
            return;
        }
        LinkedHashMap<String, SpeechChunker.Chunk> speakers = new LinkedHashMap<>();
        for (SpeechChunker.Chunk chunk : chunks) {
            if (chunk.dialogue && !chunk.speaker.isEmpty() && !"Narrator".equals(chunk.speaker)) {
                speakers.putIfAbsent(chunk.speaker + "|" + chunk.language, chunk);
            }
        }
        if (speakers.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Character voices")
                    .setMessage("Aireado reads dialogue with the narrator when it cannot confidently identify the speaker. This book has no clear dialogue attributions yet.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[speakers.size()];
        int i = 0;
        for (Map.Entry<String, SpeechChunker.Chunk> entry : speakers.entrySet()) {
            SpeechChunker.Chunk chunk = entry.getValue();
            String selected = voiceDirector.savedCharacterVoice(currentBook.id, chunk.speaker, chunk.language);
            labels[i++] = chunk.speaker + "  ·  " + ("fr".equals(chunk.language) ? "French" : "English") + (selected == null ? "  ·  Auto" : "  ·  Custom voice");
        }
        ArrayList<SpeechChunker.Chunk> values = new ArrayList<>(speakers.values());
        new AlertDialog.Builder(this).setTitle("Choose a voice for a character")
                .setItems(labels, (dialog, which) -> chooseCharacterVoice(values.get(which)))
                .setNegativeButton("Done", null).show();
    }

    private void chooseCharacterVoice(SpeechChunker.Chunk chunk) {
        if (!ttsReady) {
            Toast.makeText(this, "Speech voices are still loading.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Voice> voices = voiceDirector.offlineVoices(tts, chunk.language);
        if (voices.isEmpty()) {
            installOfflineVoiceData();
            return;
        }
        String[] names = new String[voices.size() + 1];
        names[0] = "Automatic voice (match character clues)";
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            names[i + 1] = voice.getName() + (voice.getQuality() >= Voice.QUALITY_HIGH ? "  ·  high quality" : "");
        }
        new AlertDialog.Builder(this).setTitle(chunk.speaker + " · " + ("fr".equals(chunk.language) ? "French" : "English"))
                .setItems(names, (dialog, which) -> {
                    if (which == 0) {
                        voiceDirector.resetCharacterVoice(currentBook.id, chunk.speaker, chunk.language);
                        Toast.makeText(this, "Automatic voice restored.", Toast.LENGTH_SHORT).show();
                    } else {
                        Voice voice = voices.get(which - 1);
                        voiceDirector.saveCharacterVoice(currentBook.id, chunk.speaker, chunk.language, voice.getName());
                        tts.setVoice(voice);
                        tts.speak("Hello. I am " + chunk.speaker + ".", TextToSpeech.QUEUE_FLUSH, null, "preview-" + SystemClock.elapsedRealtime());
                        Toast.makeText(this, "Voice saved for " + chunk.speaker + ".", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showVoiceHelp() {
        String message = offlineVoiceSummary() + "\n\nAireado reads only with installed voices marked as offline by Android. If French or English is missing, install its voice data in Android Text-to-speech settings. Voice names and quality depend on the phone's speech engine.";
        new AlertDialog.Builder(this).setTitle("Offline voices")
                .setMessage(message)
                .setPositiveButton("Voice settings", (dialog, which) -> installOfflineVoiceData())
                .setNegativeButton("Done", null).show();
    }

    private String offlineVoiceSummary() {
        if (!ttsReady || tts == null) return "Checking installed offline voices…";
        int french = voiceDirector.offlineVoices(tts, "fr").size();
        int english = voiceDirector.offlineVoices(tts, "en").size();
        return "OFFLINE VOICES   ·   " + french + " French   ·   " + english + " English   ·   tap for setup";
    }

    private void installOfflineVoiceData() {
        try {
            startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
        } catch (Exception error) {
            Toast.makeText(this, "Open Settings → Accessibility → Text-to-speech output to install offline voices.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemove(BookStore.Book book) {
        new AlertDialog.Builder(this).setTitle("Remove book?")
                .setMessage("Remove “" + book.title + "” and its saved reading position from Aireado?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    store.remove(book.id);
                    showLibrary();
                }).show();
    }

    private void savePosition() {
        if (currentBook != null) store.updateProgress(currentBook.id, currentChunkIndex, listenedMs);
    }

    private static int countWords(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private String formatMinutes(long seconds) {
        long minutes = Math.max(0, seconds / 60);
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours > 0) return String.format(Locale.US, "%dh %02dm", hours, rest);
        return String.format(Locale.US, "%d min", minutes);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(bg);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String value, float sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setIncludeFontPadding(true);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private TextView chip(String title, Runnable action) {
        TextView button = label(title, 14, primary, true);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(44));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(roundRect(surface, dp(14)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ttsReady && currentBook == null) showLibrary();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        io.shutdown();
        super.onDestroy();
    }

}
