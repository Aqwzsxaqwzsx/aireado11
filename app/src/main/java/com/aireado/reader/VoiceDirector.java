package com.aireado.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects only installed, non-network voices. Character choices are stored locally per book. */
public final class VoiceDirector {
    private final SharedPreferences prefs;

    public VoiceDirector(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("aireado_voices", Context.MODE_PRIVATE);
    }

    public List<Voice> offlineVoices(TextToSpeech tts, String language) {
        ArrayList<Voice> voices = new ArrayList<>();
        if (tts == null || tts.getVoices() == null) return voices;
        for (Voice voice : tts.getVoices()) {
            Locale locale = voice.getLocale();
            if (!voice.isNetworkConnectionRequired() && locale != null && locale.getLanguage().equals(language)) {
                voices.add(voice);
            }
        }
        Collections.sort(voices, Comparator.comparing(Voice::getName));
        return voices;
    }

    public Voice forChunk(TextToSpeech tts, String bookId, SpeechChunker.Chunk chunk) {
        List<Voice> available = offlineVoices(tts, chunk.language);
        if (available.isEmpty()) return null;
        if (chunk.dialogue && !chunk.speaker.isEmpty() && !"Narrator".equals(chunk.speaker)) {
            String key = characterKey(bookId, chunk.speaker, chunk.language);
            String saved = prefs.getString(key, null);
            if (saved != null) {
                for (Voice voice : available) if (saved.equals(voice.getName())) return voice;
            }
        }

        ArrayList<Voice> matching = new ArrayList<>();
        for (Voice voice : available) {
            int label = genderLabel(voice);
            if ((chunk.gender == SpeechChunker.Gender.FEMALE && label == 1)
                    || (chunk.gender == SpeechChunker.Gender.MALE && label == 2)) {
                matching.add(voice);
            }
        }
        if (matching.isEmpty()) matching.addAll(available);
        int index;
        if (chunk.dialogue && !chunk.speaker.isEmpty()) {
            index = Math.floorMod(chunk.speaker.hashCode(), matching.size());
        } else {
            index = 0;
        }
        return matching.get(index);
    }

    public void saveCharacterVoice(String bookId, String speaker, String language, String voiceName) {
        prefs.edit().putString(characterKey(bookId, speaker, language), voiceName).apply();
    }

    public void resetCharacterVoice(String bookId, String speaker, String language) {
        prefs.edit().remove(characterKey(bookId, speaker, language)).apply();
    }

    public String savedCharacterVoice(String bookId, String speaker, String language) {
        String value = prefs.getString(characterKey(bookId, speaker, language), null);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String characterKey(String bookId, String speaker, String language) {
        return "character_" + bookId + "_" + language + "_" + speaker.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    /** 1 = likely feminine, 2 = likely masculine, 0 = unknown; users can override per character. */
    private static int genderLabel(Voice voice) {
        String data = voice.getName().toLowerCase(Locale.ROOT);
        Set<String> features = voice.getFeatures();
        if (features != null) {
            for (String feature : features) data += " " + feature.toLowerCase(Locale.ROOT);
        }
        if (data.matches(".*(female|feminin|woman|\u0023f[0-9]|\u0023female).*")) return 1;
        if (data.matches(".*(male|masculin|man|\u0023m[0-9]|\u0023male).*")) return 2;
        return 0;
    }
}
