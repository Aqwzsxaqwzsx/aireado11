package com.aireado.reader;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns book text into short narration/dialogue units so playback can be buffered smoothly. */
public final class SpeechChunker {
    public enum Gender { FEMALE, MALE, UNKNOWN }

    public static final class Chunk {
        public final String text;
        public final int start;
        public final int end;
        public final String speaker;
        public final Gender gender;
        public final String language;
        public final boolean dialogue;

        Chunk(String text, int start, int end, String speaker, Gender gender,
              String language, boolean dialogue) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.speaker = speaker;
            this.gender = gender;
            this.language = language;
            this.dialogue = dialogue;
        }
    }

    private static final Pattern QUOTES = Pattern.compile("“[^”\\n]+”|«[^»\\n]+»|\"[^\"\\n]+\"");
    private static final Pattern CAPITALIZED_NAME = Pattern.compile("\\b([\\p{Lu}][\\p{L}'’\\-]{1,24})\\b");
    private static final Pattern VERB_THEN_NAME = Pattern.compile(
            "(?iu)\\b(?:said|asked|replied|answered|whispered|shouted|called|wrote|" +
            "dit|demanda|répondit|repondit|murmura|cria|déclara|declara|ajouta|" +
            "a dit|a demandé|a demande|a répondu|a repondu)\\s+([\\p{L}'’\\-]{2,24})");
    private static final Pattern NAME_THEN_VERB = Pattern.compile(
            "([\\p{Lu}][\\p{L}'’\\-]{1,24})\\s+(?:said|asked|replied|answered|whispered|shouted|" +
            "dit|demanda|répondit|repondit|murmura|cria|déclara|declara|a dit|a demandé|a demande|a répondu|a repondu)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FEMALE_CUES = Pattern.compile("\\b(she|her|hers|woman|girl|mother|daughter|wife|elle|elles|femme|fille|mère|mere|épouse|epouse)\\b");
    private static final Pattern MALE_CUES = Pattern.compile("\\b(he|him|his|man|boy|father|son|husband|il|ils|homme|garçon|garcon|père|pere|époux|epoux)\\b");
    private static final String[] FRENCH_WORDS = {"le", "la", "les", "une", "des", "est", "dans", "avec", "pour", "elle", "il", "que", "qui", "pas", "mais", "dit", "son", "sa", "être", "être", "bonjour"};
    private static final String[] ENGLISH_WORDS = {"the", "and", "that", "with", "for", "she", "he", "was", "were", "said", "this", "have", "you", "not", "but", "from", "his", "her"};

    private SpeechChunker() { }

    public static ArrayList<Chunk> split(String document) {
        ArrayList<Chunk> result = new ArrayList<>();
        if (document == null || document.isEmpty()) return result;
        int paragraphStart = 0;
        while (paragraphStart < document.length()) {
            int paragraphEnd = document.indexOf("\n\n", paragraphStart);
            if (paragraphEnd < 0) paragraphEnd = document.length();
            String paragraph = document.substring(paragraphStart, paragraphEnd);
            if (!paragraph.trim().isEmpty()) splitParagraph(paragraph, paragraphStart, result);
            paragraphStart = paragraphEnd + 2;
        }
        return result;
    }

    private static void splitParagraph(String paragraph, int absoluteStart, ArrayList<Chunk> out) {
        Matcher quoteMatcher = QUOTES.matcher(paragraph);
        int cursor = 0;
        while (quoteMatcher.find()) {
            addNarration(paragraph, cursor, quoteMatcher.start(), absoluteStart, out);
            int opening = 1;
            int closing = 1;
            int contentStart = quoteMatcher.start() + opening;
            int contentEnd = quoteMatcher.end() - closing;
            String dialogue = paragraph.substring(contentStart, contentEnd).trim();
            int leftTrim = paragraph.substring(contentStart, contentEnd).length()
                    - paragraph.substring(contentStart, contentEnd).stripLeading().length();
            if (!dialogue.isEmpty()) {
                String speaker = findSpeaker(paragraph, quoteMatcher.start(), quoteMatcher.end());
                Gender gender = speaker.isEmpty() ? Gender.UNKNOWN : inferGender(paragraph, speaker);
                out.add(new Chunk(dialogue,
                        absoluteStart + contentStart + leftTrim,
                        absoluteStart + contentEnd,
                        speaker,
                        gender,
                        detectLanguage(dialogue),
                        true));
            }
            cursor = quoteMatcher.end();
        }
        addNarration(paragraph, cursor, paragraph.length(), absoluteStart, out);
        if (cursor == 0 && out.isEmpty()) addNarration(paragraph, 0, paragraph.length(), absoluteStart, out);
    }

    private static void addNarration(String paragraph, int start, int end, int absoluteStart,
                                     ArrayList<Chunk> out) {
        while (start < end && Character.isWhitespace(paragraph.charAt(start))) start++;
        while (end > start && Character.isWhitespace(paragraph.charAt(end - 1))) end--;
        if (end <= start) return;
        String text = paragraph.substring(start, end);
        out.add(new Chunk(text, absoluteStart + start, absoluteStart + end,
                "Narrator", Gender.UNKNOWN, detectLanguage(text), false));
    }

    private static String findSpeaker(String paragraph, int quoteStart, int quoteEnd) {
        int from = Math.max(0, quoteStart - 110);
        int to = Math.min(paragraph.length(), quoteEnd + 110);
        String around = paragraph.substring(from, to);
        Matcher after = NAME_THEN_VERB.matcher(around);
        String nearest = "";
        while (after.find()) {
            int candidate = from + after.start();
            if (candidate <= quoteEnd && candidate >= quoteStart - 110) nearest = after.group(1);
        }
        Matcher before = VERB_THEN_NAME.matcher(around);
        while (before.find()) {
            int candidate = from + before.start();
            if (candidate >= quoteStart && candidate <= quoteEnd + 110) nearest = before.group(1);
        }
        if (!nearest.isEmpty()) return capitalize(nearest);

        // A nearby capitalized name is only a weak fallback; return it only when one candidate exists.
        Matcher names = CAPITALIZED_NAME.matcher(around);
        String onlyName = "";
        int count = 0;
        while (names.find()) {
            String candidate = names.group(1);
            if (candidate.equals("I") || candidate.equals("The") || candidate.equals("He") || candidate.equals("She")) continue;
            count++;
            onlyName = candidate;
        }
        return count == 1 ? onlyName : "";
    }

    private static Gender inferGender(String paragraph, String speaker) {
        int at = paragraph.toLowerCase(Locale.ROOT).indexOf(speaker.toLowerCase(Locale.ROOT));
        if (at < 0) return Gender.UNKNOWN;
        String context = paragraph.substring(Math.max(0, at - 100), Math.min(paragraph.length(), at + speaker.length() + 140))
                .toLowerCase(Locale.ROOT);
        boolean female = FEMALE_CUES.matcher(context).find();
        boolean male = MALE_CUES.matcher(context).find();
        if (female == male) return Gender.UNKNOWN;
        return female ? Gender.FEMALE : Gender.MALE;
    }

    public static String detectLanguage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int fr = 0;
        int en = 0;
        for (String word : FRENCH_WORDS) if (containsWord(lower, word)) fr++;
        for (String word : ENGLISH_WORDS) if (containsWord(lower, word)) en++;
        if (lower.matches(".*[àâçéèêëîïôùûüÿœ].*")) fr += 2;
        return fr > en ? "fr" : "en";
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?iu)\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
