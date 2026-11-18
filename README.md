# Voice Notes

Offline speech-to-text for Android. Speak, and it writes your words — no internet, no extra device.

- Languages: Auto-detect, English, বাংলা (Bengali), हिंदी (Hindi)
- Engine: [whisper.cpp](https://github.com/ggml-org/whisper.cpp), fully on-device
  - Auto / English / Hindi: multilingual Whisper `base`
  - Bengali: [anuragshas/whisper-small-bn](https://huggingface.co/anuragshas/whisper-small-bn) (Whisper small fine-tuned on Bengali), converted to ggml q8_0 during the build
- Live mode: words appear while you speak, then a careful final pass polishes them
- Read button: reads the text back aloud with the phone's own voices (English, Bengali, Hindi)
- Copy, share, edit, and a saved history — everything stays on the phone

## Install

Open **Releases → latest** on your phone, download `VoiceNotes.apk`, and install it.

## How it is built

Every push to `main` runs GitHub Actions, which downloads whisper.cpp and the model, builds the APK, and publishes it as a new release.
