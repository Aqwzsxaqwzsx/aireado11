# Aireado

An offline-first Android reader for PDF books and Word `.docx` files. The first build is aimed at Android 9 and newer and will be tuned on a Huawei Y9 2019 (JKM-LX1).

## Current features

- Import PDF and Word files through Android's document picker.
- Extract selectable PDF text and DOCX paragraphs on the phone. Scanned PDFs use the bundled Latin OCR model.
- Save imported text, current reading position, elapsed listening time, and bookmarks in app-private storage.
- Read with installed Android voices only when the voice reports that it does not need a network connection. Aireado itself has no Internet permission.
- Detect clearly attributed quoted dialogue with lightweight English/French text rules. Character voices are selected from available offline voices; uncertain lines use the narrator. The Cast screen lets you correct a character's voice.
- Show the current part, animated text highlight, estimated total and remaining time, and time listened.
- Dark/light theme, font size and serif/sans-serif reader choices, speed control, and next/previous speech segment controls.
- GitHub Actions builds a debug APK artifact on pushes to `main` and pull requests.

## Voice setup and limits

Aireado does not upload book text or train a speech model. It uses voices installed by the Android Text-to-speech engine and filters out voices that require a network connection. Install French and English offline voice data in Android's Text-to-speech settings before going offline. Available accents, voice choices, and naturalness depend on the speech engine installed on the phone; voice quality must be judged on the Y9 itself.

Speaker attribution is deliberately conservative. It recognizes common forms such as “Alice said” and “dit Alice”; when the text does not clearly name a speaker, Aireado uses the narrator. Pronoun-based gender clues are heuristic, so use Cast to fix a voice when the book's clues are ambiguous. Each character's manual choice is saved per book.

Scanned PDF OCR is on-device and bundled for Latin-script text, including French and English. OCR accuracy depends on scan quality and page layout. Password-protected PDFs are not supported.

## Build

Open this repository in Android Studio, or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `minSdk` is 28 (Android 9); compile SDK is 37.
