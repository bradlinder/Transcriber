# GEMINI & AI AGENT DIRECTIVES (TRANSCRIBER / SCRIB)

This file defines mandatory engineering constraints for all AI coding agents working on this project.

## MANDATORY RULES

### 1. NO TOP-LAYER HACKS OR STRING HEURISTICS
* **NEVER** use hardcoded word lists, vocabulary dictionaries, regex heuristics, or string matching to solve audio timing, speaker diarization, or signal processing problems.
* All timing, speaker attribution, and segment boundary decisions MUST be grounded strictly in:
  1. Real neural token timestamps from ASR models (`token_timestamps`).
  2. Neural voiceprint embeddings (WeSpeaker CAM++).
  3. Short-Time Energy (STE) acoustic pause analysis.

### 2. LOW-LEVEL FIRST & PIPELINE INTEGRITY
* Fix issues at their lowest source layer (C++ NDK/JNI, raw PCM audio samples, or neural ASR token output) rather than patching top-level Kotlin wrappers.
* If low-level data (e.g. word-level timestamps) is needed in Kotlin, expose it cleanly from the C++ JNI bridge (`native_diarize.cpp` / `bridge.cmake` / `TranscribeLib.kt`).

### 3. NO PARAMETER DISTORTION OR ARTIFICIAL CHUNKING
* Do not distort neural model window parameters (e.g., shrinking 3s-6s voiceprint windows down to 2s) to bypass alignment issues, as this destroys voiceprint clustering accuracy on full-length recordings.

### 4. THOROUGH PLANNING & PRE-WRITE SANITY CHECK
Before editing core code, present a brief 3-bullet technical breakdown confirming:
1. The exact files and C++ / JNI layers being modified.
2. The underlying low-level mechanism being used.
3. An explicit confirmation that zero shortcuts or hardcoded word rules are involved.

### 5. AUTOMATIC VERSION BUMP POLICY
* Every update, bug fix, or feature refinement MUST automatically increment the application version number with a point release (e.g., `versionCode 25` / `versionName '0.25'` -> `versionCode 26` / `versionName '0.26'`).
* All version updates MUST be synchronized across `app/build.gradle`, `metadata/org.scrib.transcriber.yml`, and `roadmap.md`.

### 6. NO UNILATERAL RECOVERY LOOPS — ASK USER IMMEDIATELY
* If an accidental file rollback, divergence, or loss occurs, **NEVER** enter speculative autonomous loops attempting to reverse-engineer, decompile, or scrape internal caches.
* Stop and pause immediately, state clearly to the user what happened, and ask for their guidance or a quick revert from IDE Local History / backups.
