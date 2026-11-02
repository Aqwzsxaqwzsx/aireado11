package com.aireado.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;

/** Keeps imported text and reading state on the device. No document content leaves the app. */
public final class BookStore {
    private static final String PREFS = "aireado_library";
    private static final String BOOKS = "books";
    private final Context context;
    private final SharedPreferences preferences;

    public static final class Book {
        public String id;
        public String title;
        public String sourceName;
        public int progressSegment;
        public int bookmarkSegment;
        public long elapsedMs;
        public long lastOpened;
        public int wordCount;

        Book(String id, String title, String sourceName, int progressSegment,
             int bookmarkSegment, long elapsedMs, long lastOpened, int wordCount) {
            this.id = id;
            this.title = title;
            this.sourceName = sourceName;
            this.progressSegment = progressSegment;
            this.bookmarkSegment = bookmarkSegment;
            this.elapsedMs = elapsedMs;
            this.lastOpened = lastOpened;
            this.wordCount = wordCount;
        }
    }

    public BookStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized Book add(String title, String sourceName, String text) throws Exception {
        String id = UUID.randomUUID().toString();
        try (FileOutputStream output = context.openFileOutput(id + ".txt", Context.MODE_PRIVATE)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        Book book = new Book(id, title, sourceName, 0, -1, 0L,
                System.currentTimeMillis(), words);
        ArrayList<Book> all = list();
        all.add(0, book);
        persist(all);
        return book;
    }

    public synchronized ArrayList<Book> list() {
        ArrayList<Book> result = new ArrayList<>();
        String raw = preferences.getString(BOOKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                result.add(new Book(
                        item.optString("id"),
                        item.optString("title", "Untitled book"),
                        item.optString("source", ""),
                        item.optInt("progress", 0),
                        item.optInt("bookmark", -1),
                        item.optLong("elapsed", 0L),
                        item.optLong("opened", 0L),
                        item.optInt("words", 0)));
            }
        } catch (Exception ignored) {
            return result;
        }
        Collections.sort(result, Comparator.comparingLong((Book book) -> book.lastOpened).reversed());
        return result;
    }

    public synchronized String loadText(String id) throws Exception {
        try (FileInputStream input = context.openFileInput(id + ".txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
            return text.toString();
        }
    }

    public synchronized void updateProgress(String id, int segment, long elapsedMs) {
        ArrayList<Book> all = list();
        for (Book book : all) {
            if (book.id.equals(id)) {
                book.progressSegment = Math.max(0, segment);
                book.elapsedMs = Math.max(0L, elapsedMs);
                book.lastOpened = System.currentTimeMillis();
                break;
            }
        }
        persist(all);
    }

    public synchronized void saveBookmark(String id, int segment) {
        ArrayList<Book> all = list();
        for (Book book : all) {
            if (book.id.equals(id)) {
                book.bookmarkSegment = Math.max(0, segment);
                break;
            }
        }
        persist(all);
    }

    public synchronized void remove(String id) {
        ArrayList<Book> all = list();
        all.removeIf(book -> book.id.equals(id));
        context.deleteFile(id + ".txt");
        persist(all);
    }

    private void persist(ArrayList<Book> books) {
        JSONArray array = new JSONArray();
        for (Book book : books) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", book.id);
                item.put("title", book.title);
                item.put("source", book.sourceName);
                item.put("progress", book.progressSegment);
                item.put("bookmark", book.bookmarkSegment);
                item.put("elapsed", book.elapsedMs);
                item.put("opened", book.lastOpened);
                item.put("words", book.wordCount);
            } catch (Exception ignored) { }
            array.put(item);
        }
        preferences.edit().putString(BOOKS, array.toString()).apply();
    }
}
