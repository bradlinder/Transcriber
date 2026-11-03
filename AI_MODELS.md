# AI Models & Download Guide

This document lists all AI models used by the **Scrib / Transcriber** Android application, their locations in the source tree, and direct download links.

To keep the source repository lightweight, large model binaries (`.onnx`, `.bin`, `.gguf`) are excluded from this source distribution archive. The app will automatically download models on demand when selected, or you can manually place them in the assets directory.

---

## 1. Embedded / Asset Models

Place these files in `app/src/main/assets/models/diarization/` if bundling them with the app assets:

| Model Name | Purpose | Expected Location | Size | Direct Download URL |
| :--- | :--- | :--- | :--- | :--- |
| **`wespeaker_en_voxceleb_CAM++.onnx`** | Speaker Diarization / Embedding Extraction (CAM++) | `app/src/main/assets/models/diarization/` | ~27.9 MB | [Download from GitHub (sherpa-onnx)](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-reconstruction-models/wespeaker_en_voxceleb_CAM++.onnx) |
| **`silero_vad.onnx`** | Voice Activity Detection (Silero VAD ONNX) | `app/src/main/assets/models/diarization/` | ~0.61 MB | [Download from GitHub (sherpa-onnx)](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) |

---

## 2. Speech-to-Text & Transcription Models (Whisper / GGUF)

The application downloads these models on demand directly inside the app. If manually downloading, place `.bin` or `.gguf` files into the app's internal models folder (or device storage).

| Model ID | Display Name | Quantized File Name | Size | Direct Download URL |
| :--- | :--- | :--- | :--- | :--- |
| `base-q5_1` | **Base (Recommended)** | `ggml-base-q5_1.bin` | ~57.8 MB | [Download from HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin) |
| `tiny-q5_1` | **Tiny** | `ggml-tiny-q5_1.bin` | ~32.2 MB | [Download from HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin) |
| `small-q5_1` | **Small** | `ggml-small-q5_1.bin` | ~190.0 MB | [Download from HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin) |
| `medium-q5_0` | **Medium** | `ggml-medium-q5_0.bin` | ~539.0 MB | [Download from HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin) |
| `large-v3-turbo-q5_0` | **Large v3 Turbo** | `ggml-large-v3-turbo-q5_0.bin` | ~574.0 MB | [Download from HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin) |
| `parakeet-tdt_ctc-110m-gguf` | **Parakeet English (GGUF)** | `parakeet-tdt_ctc-110m-Q4_K_M.gguf` | ~90.0 MB | [Download from HuggingFace](https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf/resolve/main/parakeet-tdt_ctc-110m-Q4_K_M.gguf) |
| `canary-180m-flash-gguf` | **Canary Flash (GGUF)** | `canary-180m-flash-Q4_K_M.gguf` | ~139.2 MB | [Download from HuggingFace](https://huggingface.co/handy-computer/canary-180m-flash-gguf/resolve/main/canary-180m-flash-Q4_K_M.gguf) |
| `moonshine-tiny-gguf` | **Moonshine Tiny** | `moonshine-tiny-Q8_0.gguf` | ~35.5 MB | [Download from HuggingFace](https://huggingface.co/handy-computer/moonshine-tiny-gguf/resolve/main/moonshine-tiny-Q8_0.gguf) |

---

## 3. VAD Companion Models (ggml)

| File Name | Purpose | Size | Direct Download URL |
| :--- | :--- | :--- | :--- |
| **`ggml-silero-v5.1.2.bin`** | Whisper.cpp VAD Companion | ~2.0 MB | [Download from HuggingFace](https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin) |

---

## Source References in Code

* Model Catalog definitions: `app/src/main/kotlin/org/scrib/transcriber/ModelCatalog.kt`
* Model Management & Diarization asset loading: `app/src/main/kotlin/org/scrib/transcriber/ModelManager.kt`
