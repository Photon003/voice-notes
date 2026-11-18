# Voice Notes

Offline speech-to-text for Android. Speak, and it writes your words — no internet, no extra device.

- Languages: Auto-detect, English, বাংলা (Bengali), हिंदी (Hindi)
- Engine: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) with the multilingual `base` model, packed inside the app
- Copy, share, edit, and a saved history — everything stays on the phone

## Install

Open **Releases → latest** on your phone, download `VoiceNotes.apk`, and install it.

## How it is built

Every push to `main` runs GitHub Actions, which downloads whisper.cpp and the model, builds the APK, and publishes it as a new release.
