# <a href="https://f-droid.org/packages/org.scrib.transcriber/" title="Transcriber on F-Droid"><img src="app/src/main/res/mipmap-xhdpi/ic_launcher.png" width="34" alt="Transcriber"></a> Transcriber

[![F-Droid](https://img.shields.io/f-droid/v/org.scrib.transcriber.svg)](https://f-droid.org/packages/org.scrib.transcriber/)
[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

On-device, privacy-focused voice transcription and speaker diarization for Android. Your audio processing runs 100% locally on your phone.

Transcriber is an open-source fork of [Scrib](https://github.com/23rd/Scrib). It converts speech to text on your device using open [Whisper](https://github.com/ggml-org/whisper.cpp) speech models and performs automatic multi-speaker identification using WeSpeaker CAM++ neural voiceprint embeddings. Nothing is uploaded — the app operates fully offline unless you explicitly configure a remote endpoint.

## Key Features

- **Private On-Device Transcription:** Import `.wav`, `.mp3`, `.m4a`, `.aac`, or `.ogg` recordings from your phone or record live audio straight into the app.
- **Neural Speaker Diarization:** 100% offline, ONNX-accelerated speaker identification powered by WeSpeaker CAM++ with $O(N^2)$ Centroid Vector AHC clustering, acoustic pause snapping, and word-level timestamp alignment.
- **Interactive Speaker Label Management:** Tappable speaker labels in the full-screen transcript viewer allow:
  - **Single Instance vs. Global Renaming:** Choose to rename **This Instance Only** (a specific speaker turn) or **All Instances** across the entire transcript.
  - **Delete Speaker Labels:** Remove a label at a turn and reassign the text to the preceding speaker.
  - **Insert Speaker Labels:** Add a new speaker label at any turn and assign following text until the next speaker change.
- **Universal 1-Tap Undo (`↺`):** Instantly revert accidental speaker label edits, renames, or deletions.
- **Contextual Speaker Change Labels:** Broadcast-style transcript formatting displays speaker labels only when the speaker actually changes across turns, avoiding repetitive label clutter.
- **OpenXML Word `.docx` & Subtitle Export:** Export completed transcripts directly to Microsoft Word `.docx` (opens natively in Google Docs, Word, LibreOffice) formatted with timestamps and speaker headings, as well as `.txt`, `.srt`, `.vtt`, and `.lrc`.
- **In-App Background Transcription & Floating Mini-Bar:** Minimize active transcription runs to browse history, models, or settings while transcription continues uninterrupted in the background, with live ETA, progress bar, and guarded cancellation.
- **Full-Document Text Editing with Search & Replace:** Edit full transcript text directly with live search jumping, span highlighting, replace / replace-all, unsaved edit safeguards, and proportional neural timestamp preservation.
- **Native Arbitrary Text Selection:** Integrated selection container enables standard Android touch handles for copying and sharing arbitrary text phrases across turns.
- **High-Resolution Multi-Format Audio Decoding:** Hardware-accelerated decoding supporting 24-bit packed PCM, 32-bit integer PCM, IEEE-754 floats, 16-bit audio, Opus, OGG, and AAC with disk-backed memory-mapping to keep active RAM below 5 MB even on multi-hour recordings.
- **Broadcast Workstation UI:** Material 3 user interface styled with a zinc/slate dark aesthetic and electric sky blue (`#38BDF8`) accents.
- **Background AIDL Contract:** Supports the open **Open Transcribe** AIDL service contract (`org.opentranscribe.api`) so third-party Android client apps (such as Forkgram) can use Transcriber for offline voice notes.

## Speech Models

Transcriber runs open neural speech models through `transcribe.cpp`: Whisper, Parakeet, SenseVoice, Canary, Moonshine, GigaAM, Qwen3-ASR, Nemotron, Voxtral, and others read from a unified ggml runtime. Download models directly inside the app or pick a language and Transcriber fetches a suitable size. You can also import any local `.bin` or `.gguf` model file directly from your device storage.

## Three Build Flavours

- **Transcriber** (`org.scrib.transcriber`): Lean build shipped on F-Droid. Every ggml model family, on-device WeSpeaker speaker diarization, small installer footprint (~20 MB).
- **Transcriber Max** (`org.scrib.transcriber.max`): Extended build adding the [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runtime (Zipformer, Dolphin, FireRed, Omnilingual).
- **Transcriber Remote** (`org.scrib.transcriber.remote`): Lightweight client build without local inference runtimes that connects to an OpenAI-compatible remote transcription server.

## Building

Clone with submodules to pull the bundled `transcribe.cpp` runtime:

```bash
git clone --recurse-submodules <repo-url>
cd Transcriber
./gradlew :app:assembleRelease
```

### Requirements
- Android SDK 35
- NDK 27 (`27.2.12479018`)
- CMake 3.22+
- `minSdk` 26, Target Architecture: `arm64-v8a`

Output release APK: `app/build/outputs/apk/release/app-release.apk`

## Privacy

Transcriber processes 100% of your audio locally on your device by default. Audio files and voice recordings are never uploaded to any remote server or external service. Network access is strictly limited to fetching models you explicitly choose to download.

## License

Transcriber is free software under the [GNU General Public License v3.0 or later](LICENSE).
Bundles [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT, © The ggml authors).
