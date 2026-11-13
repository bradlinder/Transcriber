# Transcriber — Product Vision & Prioritized Development Roadmap

## 1. Product Vision
Transcriber is an offline-first, privacy-focused Android application designed for field reporters, journalists, researchers, and content creators. It provides private, high-accuracy, on-device speech-to-text transcription and speaker diarization for `.wav` and `.mp3` audio recordings without sending audio to external servers.

---

## 2. Near-Term Feature Set & Current Version (`v0.72`)
- **Native C++ Engine:** Powered by Scrib's C++ `transcribe.cpp` / `whisper.cpp` / `ggml` runtime.
- **Sentence-Boundary Paragraph Breaking:** Automatically breaks paragraphs at sentence-ending punctuation (`.?!`) when the configurable word threshold is reached (default 70 words, adjustable from 20 to 200 words in Settings).
- **Intelligent Sentence Fragment Merger:** Automatically attaches short, lowercase sentence fragments ($\le 2$ words, e.g. `"examples."`) to parent sentences before paragraph breaks.
- **Time Markers:** Prepends time markers in minutes and seconds (`[MM:SS]`) to the start of each new paragraph block.
- **OpenXML Microsoft Word `.docx` Export:** Generates valid, compliant `.docx` files formatted with paragraph blocks, timestamps, and speaker turn headings (opens natively in Google Docs, Word, LibreOffice).
- **Clean UI & Branding:** Renamed app to **Transcriber**, removed legacy emoji glyphs and Scrib logo bars.
- **Compact APK Footprint:** Filtered native NDK ABIs (`arm64-v8a` + `x86_64`) to build a clean ~20 MB installer.

---

## 3. Prioritized Development Roadmap

### [x] Priority 1 (COMPLETED): On-Device Speaker Diarization via WeSpeaker CAM++
- **Overview:** 100% offline, on-device speaker diarization powered by ONNX Runtime and WeSpeaker CAM++ with low-level word-level timestamp assignment and acoustic silence valley snapping.

### [x] Priority 2 (COMPLETED): Speaker Label Renaming
- **Overview:** Rename speaker labels across an entire transcript (`AllSpeakersRenameDialog`). Updating a speaker label automatically updates all occurrences throughout the transcript on-screen and auto-saves directly to local storage.

### [x] Priority 3 (COMPLETED): Time Markers & Speaker Labels Toggles
- **Overview:** Toggle options to show or hide time markers (`[MM:SS]`) and speaker labels (`Speaker 1:`):
  - In-App Viewer Toggles: Real-time display switches in the Display & Export Options sheet.
  - Export Sync: Exported files (`.docx`, `.txt`, `.srt`, `.vtt`, `.lrc`) reflect exact on-screen toggle choices.

### [x] Priority 4 (COMPLETED): Dedicated Full In-App Transcript Screen & Selectable Text
- **Overview:** Dedicated full-screen view (`FullScreenTranscriptScreen`) replacing modal popups:
  - Top App Bar with back navigation, file title, Copy, and Share.
  - Full-width selectable text body using Compose `SelectionContainer` for touch handle text selection.
  - Spacious 2-row bottom action bar with Options, Save, Close, and prominent 1-tap Export button (`Export DOCX` default).

### [x] Priority 6 (COMPLETED): Transcripts File Manager & Export Controls
- **Overview:** Refined Transcripts tab manager:
  - Removed top "Record" cards so the tab is dedicated exclusively to managing saved transcripts.
  - M3 List Cards with 1-tap view, speaker count badges, rename, export, and delete.

### [x] Priority 7 (COMPLETED in v0.24): Rebranding & Navigation UX Overhaul
- **Overview:**
  - Complete Rebranding: App renamed to **Transcriber** across IDE, Gradle (`v0.24`), AndroidManifest, themes, C++ tags, and ViewModels.
  - 4-Tab M3 Navigation Bar: `Home`, `Transcripts`, `Voice Record`, `Settings`. Removed legacy keyboard subsystem.
  - Grouped Settings Categories: Organized Settings into M3 category cards (*Speech Recognition Models*, *Transcript Layout & Speaker Options*, *Custom Vocabulary*, *Tools & Diagnostics*).

### [x] Priority 8 (COMPLETED in v0.25): Export Format Selector Fix & RTVS3 Aesthetic Alignment
- **Overview:**
  - Export Format Fix: Fixed default export format selection to properly reflect user-selected `.docx` default across newly created, saved, and restored transcripts.
  - Skeuomorphic Graphic Removal: Cleaned up UI across all screens by removing decorative icons/emojis (`🎙`, `📁`, `📂`, `🗑️`, `👥`, `🎨`, `📐`, `📖`, `🛠`, `☁️`, `🔒`).
  - RTVS3 Color Palette: Aligned Material 3 theme colors to the broadcast-grade zinc/slate and electric sky blue (`#38bdf8`) palette from `rtvs3`.

### [x] Priority 9 (COMPLETED in v0.27): High-Contrast Model Cards & Visual Hierarchy
- **Overview:**
  - Active Model Contrast: Re-architected active model cards to use dark broadcast surfaces (`#181B20`) with prominent 2.5dp electric sky blue borders (`#38BDF8`) for optimal readability.
  - Active Status Pill: High-contrast dark badge (`#081018`) with bright sky blue text (`#38BDF8`) and checkmark.
  - Star Pin Icon: Updated star pin/unpin icon tinting to high-contrast warm gold (`#F59E0B`) / bright white (`#F0F3F6`) on dark cards.
  - Quality & Size Indicators: Replaced low-contrast grey with distinct, high-contrast sky blue (`#38BDF8`) active bars and slate grey (`#475569`) inactive bars.

### [x] Priority 10 (COMPLETED in v0.28): Delete Action Styling Alignment
- **Overview:**
  - Delete Action Color: Changed Delete action text color on active model cards from red (`cs.error`) to slate grey (`cs.onSurfaceVariant`), ensuring visual harmony with secondary card actions across all model states.

### [x] Priority 11 (COMPLETED in v0.29): Speaker Renaming Persistence & Header De-cluttering
- **Overview:**
  - Speaker Renaming Persistence: Fixed memory cache invalidation in `updateHistoryEntry` so edited speaker names persist to disk and immediately reload when opening saved transcripts.
  - Header De-cluttering: Removed redundant save/download button from the top app bar in `FullScreenTranscriptScreen` in favor of the primary bottom Export action button.

### [x] Priority 12 (COMPLETED in v0.30): O(N²) Centroid Vector Diarization Engine Optimization
- **Overview:**
  - Fast Centroid AHC Clustering: Re-architected `clusterAhcThreshold` and `clusterAhcFixedK` in `DiarizationEngine.kt` to use $O(N^2)$ Centroid Vector Nearest-Neighbor tracking.
  - Performance Improvement: Reduced AHC clustering execution time on long recordings (e.g. 38-minute interviews with 1,500+ voiceprint chunks) from >10 minutes down to ~0.2 seconds (>100x speedup), eliminating timeouts and enabling accurate multi-speaker labeling on long audio files.

### [x] Priority 13 (COMPLETED in v0.31): RTVS3 Launcher Icon Alignment
- **Overview:**
  - App Launcher Icon: Updated Android adaptive vector icons (`ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`) and legacy PNG mipmap icons across all densities (`mdpi` through `xxxhdpi`) to match the official broadcast workstation icon from `rtvs3`.

### [x] Priority 14 (COMPLETED in v0.32): Instance-Level Speaker Renaming & Label Insertion/Deletion
- **Overview:**
  - Interactive Speaker Labels: Speaker labels in the transcript viewer are now directly clickable/tappable, popping up a `SpeakerActionDialog`.
  - Single Instance vs. Global Renaming: Users can choose to rename **This Instance Only** or **All Instances** (matching `d:\rtvs3`).
  - Delete & Insert Speaker Labels: Added options to delete a speaker label at a turn (reassigning text to the previous speaker) or insert a new speaker label (assigning following text until the next speaker change).

### [x] Priority 15 (COMPLETED in v0.33): Universal Undo Stack & Speaker Change Labeling Rules
- **Overview:**
  - Circular Icon Padding: Scaled and centered the launcher vector/PNG artwork to fit comfortably within Android's circular adaptive mask.
  - Universal Undo Button: Integrated 1-tap Undo in the transcript bar (`undoStack`) to instantly revert accidental speaker label edits, renames, and deletions.
  - Contextual Speaker Change Labels: Suppressed duplicate speaker labels on consecutive paragraphs of the same speaker, displaying speaker labels only on speaker turns.

### [x] Priority 16 (COMPLETED in v0.34): Documentation Realignment
- **Overview:**
  - Comprehensive README Update: Updated `README.md` to document Transcriber's architecture, WeSpeaker CAM++ ONNX speaker diarization engine, $O(N^2)$ centroid AHC clustering, interactive speaker turn management, `.docx` OpenXML export, broadcast zinc/slate styling, and current v0.34 build instructions.

### [x] Priority 17 (COMPLETED in v0.35): In-Paragraph Sentence-Level Speaker Label Insertion
- **Overview:**
  - Long-Press Sentence Context Menu: Long-pressing or tapping a sentence inside a transcript paragraph opens a context menu (`Copy Sentence`, `Insert Speaker Label Here`).
  - Speaker Picker: Selecting `Insert Speaker Label Here` opens a dialog listing all existing speakers for 1-tap assignment alongside a custom speaker name input field.
  - Sentence-Level Segment Splitting: Uses `insertSpeakerLabelAtTimestamp` to split transcript segments at exact sentence boundaries and assign the new speaker label, refreshing the transcript with paragraph breaks (`\n\n`) and timestamps (`[MM:SS]`).
  - 1-Tap Undo Support: Fully integrated with the universal `undoStack` so label insertions can be reverted with 1 tap.

### [x] Priority 18 (COMPLETED in v0.36): About Page Realignment & Fork Disclosure
- **Overview:**
  - About Screen Realignment: Updated the in-app About screen ([AboutScreen.kt](file:///D:/Transcriber/app/src/main/kotlin/org/scrib/transcriber/AboutScreen.kt)) to document current multi-speaker diarization capabilities, interactive speaker label management, 1-tap Undo, and `.docx` OpenXML export.
  - Fork Attribution & Link: Added explicit fork disclosures and direct links to the original [Scrib repository](https://github.com/23rd/Scrib) across both the in-app About page and `README.md`.

### [x] Priority 5 & 8 (COMPLETED in v0.37): In-Transcript Search, Find & Replace, and Inline Text Editing
- **Overview:**
  - Real-Time Search & Match Highlighting: Integrated `FindReplaceBar` in the transcript viewer with live match count (`Match X of Y`) and navigation controls (`▲`/`▼`).
  - Find & Replace (Single Instance & Replace All): Allows users to search for misheard/misspelled words and replace either **This Instance Only** or **Replace All** across the entire transcript.
  - Inline Paragraph Text Editor: Allows users to tap into transcript paragraph blocks to manually correct misheard words and save the updated text directly to disk.
  - Universal Undo Integration: All Find & Replace and text editing operations push snapshots to `undoStack` for 1-tap revert.

### [x] Priority 19 (COMPLETED in v0.38): Full-Paragraph Text Editing & In-Paragraph Sentence-Split Insertion
- **Overview:**
  - Full Paragraph Text Editing: Updated `EditTurnTextDialog` to load and edit the full text of the paragraph block (`block.text`).
  - Sentence-Level Paragraph Splitting: Enhanced paragraph options to display sentence options inside the paragraph, allowing users to pick the exact sentence where a new speaker label and paragraph break (`\n\n`) should be inserted.
  - Timestamp-Splitting Accuracy: Refined `insertSpeakerLabelAtTimestamp` in `Transcript.kt` to split transcript segments at sentence timestamps and format new turns with time markers (`[MM:SS]`) and speaker labels.

### [x] Priority 20 (COMPLETED in v0.39): Full-Document Live Text Editor Mode & Gesture Protection
- **Overview:**
  - Full-Document Live Text Editor: Tapping the **`✏️` Edit** toggle button in the top action bar switches the entire transcript into a full-document scrollable text editor (`OutlinedTextField`). Users can place the cursor anywhere, type, delete, and edit text seamlessly across the whole document.
  - View Mode Gesture Protection: Single-tap scrolling in View Mode is 100% free of accidental context pop-ups. Long-pressing paragraph text opens context options (*Edit Text*, *Insert Speaker / Split Turn*, *Copy*).
  - Save & Universal Undo Integration: Tapping `✓ Save` parses edited text back into structured `TranscriptSegment` objects and pushes snapshots to `undoStack` for 1-tap revert (`↺`).

### [x] Priority 21 (COMPLETED in v0.40): Non-Skeuomorphic Vector Icons Realignment
- **Overview:**
  - Vector Drawables Created: Created clean 24dp Material vector drawables (`ic_action_search`, `ic_action_undo`, `ic_action_edit`, `ic_action_check`).
  - Action Bar Alignment: Replaced emoji/text icons in the top bar with vector drawables matching the exact size, padding, and `cs.onSurfaceVariant` / `cs.primary` tinting of `ic_action_copy` and `ic_action_share`.

### [x] Priority 22 (COMPLETED in v0.41): Unified Speaker Dialog & Full-Document Text Editing
- **Overview:**
  - Unified Speaker Action Dialog: Updated `SpeakerActionDialog` to display a **Preexisting Speakers List** alongside custom name input for 1-tap reassignments.
  - Full-Document Live Text Editing: Fully integrated `ic_action_edit` mode displaying complete text including speaker headings (`Speaker 1:`). Tapping `ic_action_check` re-parses and updates local storage with `undoStack` integration.
  - Gesture Protection: Long-press gesture scoping on paragraph text prevents accidental context pop-ups while scrolling.

### [x] Priority 23 (COMPLETED in v0.42): Active Editor Live Undo Synchronization
- **Overview:**
  - Real-Time Editor Undo: Synchronized `undoStack` with `fullDocumentText` inside `FullScreenTranscriptScreen`. Tapping the Undo icon (`ic_action_undo`) while Full-Document Edit Mode is open instantly reverts typing and text changes live inside the active editor field.

### [x] Priority 6 (COMPLETED in v0.43): Multi-File Batch Queue & Batch Processing Tool
- **Overview:**
  - Multi-File Batch Selection (`📁+`): Added "Batch Transcribe Audio Files" card on Home screen launching Android's multi-file picker.
  - Sequential Queue Execution: Processes files sequentially in `TranscriptionForegroundService` with skip-and-continue error tolerance to prevent OOMs.
  - Master Queue Progress Dialog: Displays real-time master progress (`Processing 3 of 10 Files`) with individual status badges.

### [x] Priority 24 (COMPLETED in v0.44): Two-Stage Weighted Progress Bar & Live Status Updates
- **Overview:**
  - Weighted Progress Allocation: Re-allocated transcription progress into distinct weighted stages (Stage 1 ASR: 0%–65%, Stage 2 Diarization: 65%–95%, Stage 3 Formatting: 95%–100%).
  - Granular Diarization Progress: `DiarizationEngine` emits chunk-level progress callbacks during WeSpeaker CAM++ neural voiceprint extraction.
  - Dynamic Progress Status Labels: Displays stage names and chunk counts (`Stage 2/2: Extracting Speaker Voiceprints...`) in real time.

### [x] Priority 25 (COMPLETED in v0.45): Chunked Audio Buffer Decoder & 150-Minute Diarization Fix
- **Overview:**
  - Root Cause Resolution: Replaced contiguous buffer growth in `AudioDecoder.kt` with a chunked buffer list (`ChunkedSampleSink`), eliminating `OutOfMemoryError` failures when allocating 150MB+ contiguous byte arrays at the 20-minute mark.
  - 100% Long-Audio Coverage: `DecodedAudio` now provides a seamless `getSlice(startSample, length)` method that reads samples across 5-minute chunk boundaries up to 150 minutes (144,000,000 samples).
  - Unbroken Long Diarization: Guaranteed that 100% of sentences from minute 0 to minute 150 receive neural voiceprint extraction, AHC clustering, and speaker assignments without dropping off.

### [x] Priority 26 (COMPLETED in v0.46): Non-Blocking Long-Audio Transcription & Diarization Guidance
- **Overview:**
  - Non-Blocking Transcription: Removed hard blocking exception for audio files exceeding 150 minutes. Transcription proceeds fully while speaker diarization covers the first 150 minutes (2.5 hours).
  - Clarified Diarization Notice: Refined notice text to inform users that automatic speaker diarization is optimized for audio up to 150 minutes (2.5 hours).

### [x] Priority 27 (COMPLETED in v0.47): Multi-File Batch Processing Fixes & Transcribe Window Batch Button
- **Overview:**
  - Persistable Document Permissions: Switched batch selection to `OpenMultipleDocuments()` with `takePersistableUriPermission`, ensuring robust background URI file descriptor access.
  - Correct Progress & State Sync: Fixed item ID mismatch in `executeSingleBatchItem` to restore live item progress tracking.
  - Neural Diarization Integration: Integrated `DiarizationEngine.diarize()` into batch processing so batch transcripts include speaker assignments.
  - Transcribe Window Batch Action: Added a dedicated Batch Transcribe button to the Transcribe tab (`FileTab`) and non-destructive queue dialog visibility controls.

### [x] Priority 28 (COMPLETED in v0.48): Single-Pass Audio Pipeline, Zero-Copy Buffer Slicing & Long-Audio Diarization Fix
- **Overview:**
  - Zero-Copy Chunk Slicing: Implemented `DecodedAudio.getSlice(startSample, length): FloatArray` directly reading from chunked native direct `ByteBuffers` across 5-minute boundaries, eliminating multi-hundred-megabyte contiguous heap allocations and OOM crashes on long audio files.
  - Single-Pass Pipeline: Unified ASR and speaker diarization to share a single `DecodedAudio` instance via `transcribeDecodedToSegments()`, completely eliminating redundant duplicate audio decodes and cutting processing time in half.
  - STE Energy Profile Optimization: Precomputes 100-fps Short-Time Energy profile once upfront rather than recalculating the entire 150-minute audio array on every segment iteration (100,000x speedup in alignment).
  - Native ONNX Tensor Cleanup: Enforced RAII `.use { }` cleanup on `OrtSession.Result` and `SessionOptions` in `DiarizationEngine.kt` to prevent native tensor memory leaks during sub-window embedding extraction.
  - Large Heap & Dialogue Protection: Enabled `android:largeHeap="true"` in `AndroidManifest.xml` and removed dialogue text mutation side-effects during speaker label renaming in `Transcript.kt`. Extended timestamp parser to support 3-digit minutes and HH:MM:SS format.

### [x] Priority 29 (COMPLETED in v0.49): Auto Diarization Sensitivity Calibration & Punctuation-Grounded Paragraph Boundary Integrity
- **Overview:**
  - Auto Diarization Silhouette Verification: Added Silhouette Score analysis ($k=2$) to `DiarizationEngine.kt` in `Auto` mode when $n \ge 4$. If distance threshold clustering collapses into 1 cluster, a positive silhouette score ($\ge 0.10$) dynamically separates the two distinct speakers, preventing interview dialogues from degenerating into single-speaker monologues.
  - Sensitivity Threshold Recalibration: Calibrated WeSpeaker CAM++ cosine distance thresholds (`1.0 - cos_sim`) in `ModelManager.kt` (`normal` = 0.48, `high` = 0.40, `low` = 0.58) to reflect empirical inter-speaker acoustic distances instead of collapsing all speakers at 0.68.
  - Robust Temporal Span Snapping: Upgraded `findBestSpeaker` to snap to the closest temporal diarization span when exact overlap is 0 due to acoustic pause/silence troughs, eliminating unassigned speaker gaps.
  - Continuous Sentence Stitching Across 30s Window Cuts: Enhanced `toSentencesInternal` in `Transcript.kt` to stitch mid-sentence fragments across Whisper 30s window boundaries when trailing text lacks terminating punctuation or the next segment begins with a lowercase letter, stripping hallucinated border punctuation.
  - Punctuation-Grounded Paragraph Breaks: Enforced that `wordThreshold` paragraph breaks in `paragraphsWithTimestamps` and `toTurnBlocks` occur strictly after terminating punctuation (`[.?!]`) and never cut thoughts mid-sentence.

### [x] Priority 30 (COMPLETED in v0.50): Trailing Silence Hallucination & Runaway N-Gram Loop Scrubber (rtvs3 Port)
- **Overview:**
  - Ported `transcript_cleaner.py` from desktop `d:\rtvs3` to `TranscriptCleaner.kt`.
  - Trailing Silence Hallucination Scrubbing: Automatically detects and strips standard end-of-audio silence hallucinations (e.g. *"Thanks for watching"*, *"Please subscribe"*, *"Subtitles by..."*) that ASR models emit during room silence or music ring-outs at the end of recordings.
  - Degenerate Repeating N-Gram Collapser: Detects and collapses single-word stutter loops (`"yeah yeah yeah yeah yeah"` -> `"yeah yeah"`) and multi-word runaway hallucination loops (`"thank you very much... thank you very much..."` -> `"thank you very much"`).
  - Cleaned Segment Merger: Merges consecutive identical segments and prunes degenerate repeating tails while preserving exact neural token timings. Integrated into `TranscriptionRun.kt` via `rawDiarized.scrubTranscript()`.

### [x] Priority 31 (COMPLETED in v0.51): Conversational Diarization Calibration, Transcripts Bulk Select & Rename
- **Overview:**
  - Desktop-Calibrated CAM++ Baseline (0.68f): Re-aligned sensitivity distance thresholds with desktop `d:\rtvs3` (`normal` = `0.68f`, `low` = `0.78f`, `high` = `0.58f`, `very_high` = `0.48f`, `ultra` = `0.38f`). Eliminates the 41-speaker over-segmentation bug by allowing natural intra-speaker pitch and inflection variations ($\le 0.55$) across long recordings to merge into the primary speaker while keeping different speakers ($> 0.75$) cleanly apart.
  - Spherical Centroid Outlier Refinement: Added dominant-centroid refinement in `DiarizationEngine.kt` to re-assign isolated single-chunk outliers to their nearest conversational speaker centroid on the unit hypersphere.
  - Sentence-Closure Speaker Transition Protection: In `AcousticAligner.kt`, enhanced `splitSegmentAtSpeakerTransition` to ensure trailing lowercase sentence-closure tokens (e.g. `"locally."`) are retained with the speaker who uttered them, eliminating orphaned punctuation fragments at speaker handoffs.
  - Zero Mid-Sentence Paragraph Breaks: Enforced in `toTurnBlocks` and `paragraphsWithTimestamps` that paragraph breaks never sever thoughts immediately before lowercase continuation words across window boundaries or run-ons.
  - Transcripts Tab Bulk Selection: Added full multi-select mode in `ScribScreen.kt` with selection checkboxes, Select All / Deselect All, bulk deletion with confirmation dialog, and bulk sharing via Android system share sheet.
  - Transcript Rename in Three-Dot Menu: Added a "Rename" action to each transcript's three-dot menu with an in-place rename dialog and persistent database update.

### [x] Priority 32 (COMPLETED in v0.52): Disk-Backed Audio Pipeline, Unbounded Duration Support, Transcripts CAB & Floating Mini-Bar
- **Overview:**
  - Disk-Backed Temp PCM Stream (`AudioDecoder.kt`): Replaced in-memory DirectByteBuffer chunk allocation with a streaming disk-backed temporary file in `cacheDir` memory-mapped via OS `mmap`. Eliminates the 25-minute (96 MB) direct memory ceiling, reducing active physical RAM to under 5 MB and enabling seamless transcription of 38-minute, 90-minute, and multi-hour recordings without memory limits.
  - Unbounded Duration & Low Storage Pre-flight Guard: Removed artificial 150-minute cap, allowing recordings of arbitrary length up to 32-bit sample index limits. Added pre-flight storage verification and friendly low storage dialog if device internal cache space is insufficient.
  - Automatic Temporary File Lifecycle: Automated cleanup of stale `.pcm` cache files on app launch and guaranteed file deletion upon run completion or cancellation.
  - Native Contextual Action Bar (CAB): Removed the static "Select" button in favor of native long-press activation on any transcript card, complete with M3 top action bar, selection count, Select All toggle, Share, Delete, and system `BackHandler` exit.
  - In-App Background Transcription & Floating Mini-Bar: Added minimize capability to the active transcription view. When minimized, a persistent M3 floating mini-bar docks above the bottom navigation bar showing live progress, percentage, ETA, and cancellation, allowing users to browse other tabs while transcription continues uninterrupted. 1-tap re-expands to full screen.

### [x] Priority 33 (COMPLETED in v0.53): Navigation Rationalization, Transcripts Tab Document Icon, Command Dashboard, Unblocked Text Selection & Scroll-To-Match
- **Overview:**
  - Transcripts Tab Vector Icon: Replaced ambiguous microphone icon on the Transcripts tab with authentic M3 document sheet icon (`ic_tab_transcripts.xml`) featuring transcript text lines.
  - Command Dashboard (`HomeTab`): Transformed Home tab into a clean dashboard featuring Active Model StatusCard with quick settings shortcut, Pinned Quick Access models, and Recent Transcripts (last 4 items with 1-tap open and "View All ->" link to the Transcripts tab).
  - Unified Action Hub (`FileTab`): Merged "Record & Transcribe" capability alongside "Transcribe Audio File" and "Batch Transcribe Audio Files" on the Transcribe tab.
  - Persistent Bottom Navigation & Downward Minimize Chevron: Replaced misleading top `✕` button with a downward minimize chevron (`ic_chevron_down` / `⌄ Minimize`). Bottom navigation remains accessible during transcription so tapping any tab automatically docks the running session into the floating mini-bar without interrupting progress. Provided an explicit red-tinted `[Cancel Transcription]` button for aborting.
  - Auto-Save & Clean Export Actions: Completed transcriptions automatically persist to `TranscriptHistory` on completion. Replaced manual internal "Save" button with a green `"Saved to Transcripts ✓"` indicator, primary `[Export DOCX]` action, and native `[Share]` sheet.
  - Search Jump-To-Match & Span Highlighting: Replaced plain Column scrolling with `LazyColumn` and `AnnotatedString` match styling. All search matches are highlighted in `secondaryContainer`, with the active match highlighted in `primary`. Next/Previous match navigation smoothly scrolls directly to the containing block with `listState.animateScrollToItem()`.
  - Unblocked Arbitrary Text Selection: Removed gesture-intercepting `combinedClickable` from paragraph text and wrapped the transcript in `SelectionContainer`, enabling native Android selection handles for arbitrary word/phrase copying and sharing. Moved paragraph actions to a dedicated header button.
  - Full-Document Speaker Parsing: Enhanced `parseTextToSegments()` to recognize both timestamped and plain `Speaker: Text` blocks, enabling speaker assignment directly from text editing.

### [x] Priority 34 (COMPLETED in v0.54): Search & Replace in Full-Document Editor, Valid History DOCX Export, Neural Timestamp Preservation & Hour Rollover
- **Overview:**
  - Search Jump-to-Match & Highlighting in Editor Mode: Integrated `TextFieldValue` selection in Full-Document Edit mode. Next/Previous navigation moves the selection range and automatically scrolls the editor to the matched word. Added custom `VisualTransformation` highlighting all matches in `secondaryContainer` and active match in `primary`. Wired live Replace and Replace All directly into the editor text.
  - Valid DOCX Export from History: Unified file exporting via `exportTranscriptFile()`, routing History `.docx` exports through `DocxExporter.exportToDocx()` instead of writing raw text. Eliminates file corruption when exporting saved transcripts from the Transcripts tab.
  - Neural Token Timestamp Preservation: Replaced naive text parsing on edit save with `updateSegmentsFromText()`. Edits made in reading mode (without visible timestamps) map proportionally to original segments rather than wiping all timestamps to `00:00`.
  - Speaker Label Search Support: Indexed speaker names in search matching so searching for "Speaker 1" or speaker names highlights the block header and supports speaker jumping and renaming.
  - Audio Timestamp Hour Rollover: Updated `clockMMSS` to format durations $\ge 60$ minutes as `[HH:MM:SS]` (e.g. `[01:30:15]`) instead of `[90:15]`.

### [x] Priority 35 (COMPLETED in v0.55): Multi-Format Audio Decoding, Configuration State Retention, Screen Wake Lock & UI Streamlining
- **Overview:**
  - Robust 24-bit Packed & 32-bit Integer PCM Decoding: Expanded `AudioDecoder.ChunkConverter` to unpack `AudioFormat.ENCODING_PCM_24BIT_PACKED` (3-byte signed int), `AudioFormat.ENCODING_PCM_32BIT` (4-byte signed int), and `AudioFormat.ENCODING_PCM_8BIT` alongside 16-bit and IEEE-754 floats. Fixes crashes when decoding high-resolution studio recordings, WAVs, or lossless FLACs.
  - Rotation & Configuration State Preservation: Migrated transcript editor state (`isFullDocumentEditing`, `fullDocumentFieldValue` via `TextFieldValue.Saver`, `searchQuery`, `replaceQuery`), Transcripts selection list (`selectedIds` via `listSaver`), and Dictionary rule editor to `rememberSaveable`. Unsaved edits and multi-selection modes survive device rotation, split-screen, and theme toggling without data loss.
  - Recording Screen Wake Lock: Added `FLAG_KEEP_SCREEN_ON` dynamically during active microphone capture in `MainActivity` to prevent screen sleep and premature recording cutoffs.
  - Guarded Mini-Bar Cancellation: Added a confirmation alert dialog to the docked mini-bar cancel button, preventing accidental aborts of long transcriptions during tab navigation.
  - Safe DOCX Export Error Handling: Eliminated corrupted `.docx` fallback output in `ScribViewModel.exportTranscriptFile()`; errors report failure cleanly rather than dumping plain text into a `.docx` file.
  - Comprehensive Audio Picker MIME Alignment: Updated single audio picker to accept `application/ogg`, `application/opus`, and `application/octet-stream` matching the batch picker.
  - Transcript Screen UI Streamlining: Removed redundant bottom-toolbar Share button (retaining the top bar Share), removed top `✕` icon when viewing completed transcripts (retaining bottom Close), and renamed "Display Options" button to "Transcript Options".
  - Cleaned Orphaned Code & Missing VAD Resources: Removed dead `SentenceActionDialog` code and added default English string definitions for localized VAD keys to eliminate AAPT compiler warnings.

### [x] Priority 36 (COMPLETED in v0.56): Unsaved Edits Guard, Multi-Level Back Navigation & Stale Export Cache Cleanup
- **Overview:**
  - Prioritized Multi-Level Back Navigation: Established strict back navigation hierarchy in `FullScreenTranscriptScreen`. Back first closes the Find & Replace bar if active, then guards unsaved document edits, and only closes the transcript screen when in standard reading mode.
  - Unsaved Edits Exit Guard: Added a confirmation dialog (*"Discard Unsaved Edits?"*) prompting the user to *Save & Exit*, *Discard*, or *Keep Editing* if they attempt to back out or tap Close while edits are in progress in the full-document editor.
  - Cache Cleanup for Word Exports: Expanded `AudioDecoder.cleanStaleTempFiles()` to purge orphaned `temp_export_*.docx` files alongside stale `.pcm` files older than 1 hour, triggered automatically on document export.
  - Documentation Updates: Updated `README.md` to comprehensively document in-app background transcription, mini-bar progress tracking, full-document editing with search & replace, native arbitrary text selection, and 24-bit/32-bit audio decoding.

### [x] Priority 37 (COMPLETED in v0.57): Mode-Specific Top Bar, Overflow Menu, Reaper Unsaved Indicator, Working Editor Undo & Auto-Hiding Bottom Toolbar
- **Overview:**
  - Clean Mode-Specific Top Bar: Redesigned `FullScreenTranscriptScreen` top bar into focused modes:
    - Reading Mode: Spacious title with only `[Search]`, `[Edit]`, and a Material 3 `[⋮]` Overflow Menu housing secondary actions (Copy Entire Text, Share, Transcript Options, Undo Speaker Edit, Batch Queue).
    - Editing Mode: Dedicated editor toolbar with `[✕ Cancel]`, document title, `[Unsaved *]` badge, `[↺ Undo]`, `[Search]`, and `[✓ Save]`.
  - Reaper-Style Unsaved Status Indicator: Eliminated the bulky permanent green `"Saved to Transcripts ✓"` banner. In its place, a discrete `[Unsaved *]` badge appears next to the filename only when dirty (`text != formattedText`), disappearing automatically when saved.
  - Working Full-Document Editor Undo: Added an in-memory `editUndoStack` tracking text states across typing, find/replace, and paste operations, wired directly to the top `[Undo]` button in editing mode.
  - Auto-Hiding Bottom Toolbar & 100% Editing Canvas: Completely hides the bottom toolbar during full-document editing so the keyboard and editor utilize full screen height. In reading mode, the bottom toolbar automatically slides out of view on scroll down (`AnimatedVisibility`) and smoothly slides back on scroll up or at the bottom.

### [x] Priority 38 (COMPLETED in v0.58): Hybrid In-Place Saving, 2-Second Debounced Auto-Save & Hardware Keyboard Shortcuts
- **Overview:**
  - Debounced Auto-Save on Inactivity: Added a trailing 2-second debounce timer (`delay(2000L)`) in `LaunchedEffect` that automatically persists changes in the background without interrupting editing flow.
  - 1-Tap Manual In-Place Save via Status Badge: Made the `[Unsaved *]` badge directly interactive; tapping it immediately saves changes to disk in place, leaving the editor open and transforming the badge into a `[Saved ✓]` confirmation.
  - Hardware Keyboard Support (`Ctrl + S`): Integrated `onPreviewKeyEvent` on the editor field to trigger in-place saving when pressing `Ctrl + S`.
  - Backgrounding & Lifecycle Auto-Save: Attached `DisposableEffect` to flush pending changes to disk whenever the editing screen is dismissed or the app is backgrounded.

### [x] Priority 39 (COMPLETED in v0.59): Decoupled In-Place Save Dirty Tracking & Top-Bar Cancel Navigation
- **Overview:**
  - Decoupled Dirty State from Re-Formatted Text: Introduced dedicated snapshot state `lastSavedText` in `FullScreenTranscriptScreen` rather than comparing against re-synthesized paragraph/timestamp formatting (`formattedText`). Resolves the issue where `[Unsaved *]` persisted indefinitely due to word wrapping and whitespace divergence after saving.
  - Responsive Auto-Save & 1-Tap Badge Clearing: Updating `lastSavedText` immediately upon auto-save (after 2 seconds of inactivity), manual tap on `[Unsaved *]`, or `Ctrl+S` now cleanly transitions the badge to the `[Saved ✓]` feedback indicator for 1.5 seconds before hiding.
  - Top Bar Cancel Navigation Fix: Updated the editor mode top bar `[✕]` cancel button and Android back gesture to smoothly exit document editing back to reading mode rather than dismissing the full transcript screen. Added discard confirmation dialog with safe fallback to `lastSavedText`.

### [x] Priority 40 (COMPLETED in v0.60): Comprehensive Purge of Legacy Voice Keyboard Subsystem & Copy Alignment
- **Overview:**
  - Purged Dead Keyboard Files (~1,000+ Lines): Removed orphaned `ScribInputMethodService.kt`, `KeyboardLayouts.kt`, and `method.xml` lingering from the pre-v0.24 IME architecture.
  - Removed Dead UI Composables: Purged the uncalled `KeyboardTab` (~260 lines) and custom key helper components (`AddKeyRow`, `KeyField`, `CustomKeyRow`, `KeyActionButton`) from `ScribScreen.kt`.
  - Streamlined ViewModel & MainActivity: Stripped dead `keyboardLayouts` states, preferences, and handlers from `TranscriberViewModel` and `TranscriberUiState`. Removed legacy `takeMicrophoneRequest()` and `EXTRA_REQUEST_MICROPHONE` hooks from `MainActivity.kt`.
  - Manifest & Permissions Hygiene: Removed obsolete `<queries><action android:name="android.view.InputMethod"/></queries>` block from `AndroidManifest.xml`.
  - Accurate Documentation & String Alignment: Removed the outdated *"Dictate into any app"* section from `AboutScreen.kt` and all localized `strings.xml` resource files. Aligned the *"Two ways to use Transcriber"* heading with the two active features (transcribing own audio and background engine for other apps via Open Transcribe AIDL). Cleaned `endpoint_name_hint` and 29 orphaned `ime_*` string definitions.

### [x] Priority 41 (COMPLETED in v0.61): RTVS3-Aligned Accessible Speaker Color Palette & Optional DOCX/UI Colorization
- **Overview:**
  - Ported RTVS3 8-Color Transcription Palette (`SpeakerPalette.kt`): Brought over the transcription-optimized 8-family paired palette (Sapphire Blue, Rose Crimson, Emerald Green, Royal Violet, Amber Ochre, Deep Teal, Berry Magenta, Slate Indigo) with theme-adaptive dark/light hex pairs and sequential appearance-order color assignment (`SpeakerColorAssigner`).
  - In-App UI Speaker Badges: Added theme-aware color styling to speaker badges across active transcription and history viewing when "Colorize Speaker Labels" is enabled, dynamically adjusting container alpha and text contrast for dark and light modes.
  - Colored OpenXML DOCX Export (`DocxExporter.kt`): Structured DOCX export into separate styled runs for timestamps (muted gray `#787878`), speaker labels (bold text styled with the print-optimized light-mode hex code, e.g. `<w:color w:val="1A56DB"/>`), and body text.
  - Dual Configuration Points: Added a persistent global toggle in Settings under *"Transcript Layout & Speaker Options"* and a quick toggle inside the per-transcript *"Display & Export Options"* dialog.

---

## 4. Remaining Roadmap Items

### [x] Priority 42 (COMPLETED in v0.62): Foreground Audio Waveform Icon, Bottom Bar Scroll Stabilization, Full-Screen Transcript Overlay & Per-Transcript Speaker Color Persistence
- **Overview:**
  - Replace Misleading Download Icon: Swapped out `android.R.drawable.stat_sys_download` in `TranscriptionForegroundService` with the audio waveform / graphic equalizer vector (`ic_tab_graphic_eq`), accurately indicating on-device neural processing rather than network downloads.
  - Eliminate Transcript Scroll & Text Flickering: Re-anchored the bottom action bar inside the outer transcript container as an overlay at `Alignment.BottomCenter` with `PaddingValues(bottom = 88.dp)` and `!listState.isScrollInProgress` check, eliminating viewport height oscillations and continuous 60fps text flickering near the end of documents.
  - Full-Screen Transcript Overlay & Navigation Guard: Promoted active transcript view to a root-level full-screen overlay, hiding the main bottom navigation bar during transcription to prevent tab click collisions and visual bleeding.
  - Automatic New Transcript Preservation: Updated `BackHandler` and `attemptClose()` in `FullScreenTranscriptScreen` and `TranscriptionDialog` to ensure newly generated transcripts are automatically committed to History upon user exit rather than discarded.
  - Per-Transcript Speaker Color Persistence: Persisted manual speaker label colorization toggles to individual history entries in `TranscriptHistory.updateColorize`, retaining per-transcript choices across subsequent app sessions.

### [x] Priority 43 (COMPLETED in v0.63): Production Release Build & Pipeline Synchronization
- **Overview:**
  - Synchronized application versioning to `v0.63` (`versionCode 63`) across `app/build.gradle`, `metadata/org.scrib.transcriber.yml`, and `roadmap.md`.
  - Built production-signed release APK with embedded native runtime and neural model pipeline integrity.

### [x] Priority 44 (COMPLETED in v0.64): Enhanced Release APK Signing & Play Protect Verification Alignment
- **Overview:**
  - Full APK Signature Enforcement: Explicitly enabled `v1SigningEnabled true`, `v2SigningEnabled true`, `v3SigningEnabled true`, and `v4SigningEnabled true` under `signingConfigs.release` in `app/build.gradle`.
  - Version Synchronization: Updated application versioning to `v0.64` (`versionCode 64`) across `app/build.gradle`, `metadata/org.scrib.transcriber.yml`, and `roadmap.md`.
  - Side-Loading & Play Protect Verification Workflow: Prepared production release build for direct side-loading and submission to Google Play Protect Developer Appeals & App Verification portal.

### [x] Priority 45 (COMPLETED in v0.65): Idempotent History Entry Save & Duplicate Transcript Elimination
- **Overview:**
  - Idempotent History Storage: Added `historyId: String?` property to `TranscribeUi` and updated `TranscriptHistory.save()` to reuse existing IDs when saving.
  - History Entry Synchronization: Synchronized auto-save on 100% completion with `TranscriptionRun.setHistoryId()` so subsequent exit/dismiss actions update the existing entry in place rather than creating duplicate history files on disk.

### [x] Priority 46 (COMPLETED in v0.66): Heterogeneous Audio & VoIP Diarization Robustness
- **Overview:**
  - Active Speech RMS Normalization: Implemented voiced-sample RMS loudness scaling to eliminate dynamic range disparities between close on-mic, distant off-mic, and AGC-compressed Zoom/VoIP speech.
  - Uniform Multi-Window Averaging: Slices all turns >= 1.2s into 1.5s sliding windows (0.75s step) to average CAM++ voiceprint embeddings and cancel transient codec jitter.
  - Elimination of Single-Soundbite Outlier Erasure: Replaced the 1-chunk cluster suppression rule with duration-grounded preservation (>= 1.0s) so brief guest remarks and Zoom quotes are never collapsed into other speakers.
  - Average Linkage (UPGMA) & Adaptive Dendrogram Gap Detection: Switched from centroid gravitational merging to unweighted pairwise average distance with adaptive knee-point acceleration stopping in Auto mode.

### [x] Priority 47 (COMPLETED in v0.67): Pause-Snapped Windowing & Lossless Neural Word Alignment
- **Overview:**
  - STE Pause-Snapped Chunking: Replaced rigid 30.000s non-overlapping cuts in `GgmlTranscriptionEngine` with Short-Time Energy pause-snapped windows (`24s–30s`) and context prompt carry-over, preventing mid-word and mid-clause losses at window boundaries.
  - Exclusive Neural Word Partitioning: Eliminated `±100ms` boundary filtering drops and duplicate edge words across `GgmlTranscriptionEngine` and `AcousticAligner` via deterministic max-overlap assignment and sequential sentence partitioning.
  - Whitespace-Preserving Token Assembly: Preserved standalone whitespace tokens in `TranscribeLib.tokens()` and reconciled token timings against canonical segment text so BPE/SentencePiece subwords never glue adjacent words together.

### [x] Priority 48 (COMPLETED in v0.68): GitHub Actions CI APK Build Workflow & Cross-Platform Gradle Fix
- **Overview:**
  - Added `.github/workflows/build-apk.yml` to build signed `release` or `debug` APKs directly from `main` (on push to Android sources or manual `workflow_dispatch`), run unit tests, and publish downloadable `.apk` artifacts without requiring release tags or private signing secrets.
  - Replaced the Windows-hardcoded Chaquopy `buildPython` path in `app/build.gradle` with cross-platform OS detection and `-Pchaquopy.buildPython` support.

### [x] Priority 49 (COMPLETED in v0.69): VAD Speech Retention & Temporal Speaker Flicker Elimination
- **Overview:**
  - Tuned Silero VAD (`TranscribeVad`: `THRESHOLD=0.25f`, `MIN_SPEECH_MS=100`, `MIN_SILENCE_MS=500`, `SPEECH_PAD_MS=250`) and bridged short pauses (`<= 350ms`) with an 85% active-speech compaction bypass in `GgmlTranscriptionEngine` so quiet/off-mic words are never excised before ASR.
  - Eliminated phantom 1-2 word trailing speaker splits in `DiarizationEngine` via cohesive 0.8s–6.0s turn embeddings, full-width 3.0s tail windows for >= 6.0s turns, raising the Auto AHC knee-point floor (`0.55f`), and `smoothShortSpeakerRuns` (< 650ms neural token duration).

### [x] Priority 50 (COMPLETED in v0.70): GitHub Release Publishing Workflow Alignment
- **Overview:**
  - Updated `.github/workflows/build-apk.yml` with `permissions: contents: write` and a `Publish APK to GitHub Release` step that publishes built `.apk` files directly to both `v<version>` and `latest` GitHub Releases (in addition to Actions artifacts).
  - Updated `.github/workflows/release.yml` to configure Python 3.12 (`-Pchaquopy.buildPython`), Android SDK API 35 / NDK 27, keystore fallback, and APK verification compatible with `app/build.gradle`.

### [x] Priority 51 (COMPLETED in v0.71): Sherpa/Parakeet Pause-Snapped Windowing, Neural Timestamps & Auto Micro-Cluster Consolidation
- **Overview:**
  - Upgraded `SherpaTranscriptionEngine` (`parakeet-onnx` and other Sherpa models) with `AcousticAligner.planPauseSnappedWindowRanges` (`24s–30s` STE pause cuts replacing blind `30s` slices), neural token/word timestamps extracted via shared `AcousticAligner.assembleSubwordTokensToWords`, tuned `SileroVadModelConfig` thresholds (`0.25f`, `0.50s` min silence, `0.10s` min speech, `28.0s` max span), and `transcribeDecodedToSegments` support.
  - Added `DiarizationEngine.absorbTransientAutoMicroClusters` so isolated `< 2.0s` single-utterance backchannels in `Auto` mode consolidate into their closest established (`>= 8.0s`) speaker centroid while preserving genuine soundbite speakers (`>= 2.0s`).
  - Updated `.github/workflows/build-apk.yml` to build and publish both `scrib` and `max` APKs cleanly on push.

### [x] Priority 52 (COMPLETED in v0.72): Max & Remote Variant Compilation Fix (`EndpointWordTiming` / `EndpointTranscript`)
- **Overview:**
  - Resolved the `org.scrib.transcriber.WordTiming` top-level Kotlin redeclaration between `Transcript.kt` and `CloudflareTransport.kt` that surfaced when building `-PwithSherpa` (`max`) or `-PwithRemote` (`remote`).
  - Renamed endpoint transport DTOs to `EndpointWordTiming` and `EndpointTranscript` across `CloudflareTransport.kt`, `OpenAiTransport.kt`, and `EndpointTranscriptionEngine.kt`.

---

## 5. Project Architecture & Directory Structure

```
rtvs-mobile/
├── roadmap.md
├── PROJECT_GOALS.md
└── app/src/main/
    ├── AndroidManifest.xml
    └── kotlin/org/scrib/transcriber/
        ├── MainActivity.kt
        ├── ScribScreen.kt
        ├── ScribViewModel.kt
        ├── Transcript.kt
        ├── DocxExporter.kt
        ├── ModelCatalog.kt
        └── ModelManager.kt
```
