"""Radio & TV Segmenter — Transcript cleaning, loop collapsing, and hallucination scrubbing.

Handles detection and collapsing of degenerate repeating word/token loops in transcription text,
standard Whisper hallucination phrase stripping, trailing tail truncation, and timestamp alignment.
"""

from __future__ import annotations

import copy
import re


HALLUCINATION_PHRASES = [
    r"subtitles\s+by\s+(?:the\s+)?amara\.org(?:\s+community)?",
    r"subtitles\s+by\b.*",
    r"sous-titres\s+faits\s+par\b.*",
    r"transcribed\s+by\b.*",
    r"transcription\s+by\b.*",
    r"thank\s+you\s+for\s+watching(?:\s+this\s+video)?(?:\s+and\s+listening)?",
    r"thanks\s+for\s+watching(?:\s+this\s+video)?",
    r"please\s+(?:like\s+and\s+)?subscribe(?:\s+to\s+(?:our|the|my)\s+channel)?",
    r"subscribe\s+to\s+(?:our|the|my)\s+channel",
    r"don\'t\s+forget\s+to\s+like\s+and\s+subscribe",
    r"like\s+and\s+subscribe(?:\s+for\s+more)?",
    r"see\s+you\s+in\s+the\s+next\s+video",
    r"see\s+you\s+next\s+time",
]

HALLUCINATION_PATTERNS = [re.compile(p, re.IGNORECASE) for p in HALLUCINATION_PHRASES]
REPEATED_BRACKET_PATTERN = re.compile(r'(\[(?:music|applause|laughter|silence|inaudible|whispering|coughing|screaming|bell|cheering|background\s+music)\]\s*){2,}', re.IGNORECASE)
REPEATED_PAREN_PATTERN = re.compile(r'(\((?:music|applause|laughter|silence|inaudible|whispering|coughing|screaming|bell|cheering|background\s+music)\)\s*){2,}', re.IGNORECASE)
REPEATED_MUSIC_NOTE_PATTERN = re.compile(r'(?:[♪♫♩♬]\s*){3,}')
REPEATED_PUNCTUATION_PATTERN = re.compile(r'([.?!,;:-]\s*){4,}')


def collapse_repeating_ngrams(text: str, max_n: int = 10, min_repeats: int = 3) -> str:
    """Detect and collapse degenerate repeating word/token loops in transcription text.
    
    Handles both single-word stutters ('yeah yeah yeah yeah yeah' -> 'yeah yeah')
    and multi-word runaway hallucination loops ('thank you very much. thank you very much...' -> 'thank you very much.').
    """
    if not text or not text.strip():
        return ""
    
    cleaned = text.strip()
    tokens = re.findall(r'\S+', cleaned)
    if len(tokens) < 4:
        return cleaned

    num_tokens = len(tokens)
    for n in range(min(max_n, num_tokens // 2), 0, -1):
        i = 0
        new_tokens = []
        changed = False
        while i < len(tokens):
            if i + n > len(tokens):
                new_tokens.extend(tokens[i:])
                break
            
            pattern = [t.lower().rstrip('.,!?;:') for t in tokens[i:i+n]]
            
            repeat_count = 1
            cursor = i + n
            while cursor + n <= len(tokens):
                candidate = [t.lower().rstrip('.,!?;:') for t in tokens[cursor:cursor+n]]
                if candidate == pattern:
                    repeat_count += 1
                    cursor += n
                else:
                    break
            
            threshold = min_repeats if n <= 2 else 2
            if repeat_count >= threshold:
                keep_reps = 2 if (n == 1 and repeat_count > 2 and len(tokens[i]) <= 4) else 1
                new_tokens.extend(tokens[i:i + n * keep_reps])
                i = cursor
                changed = True
            else:
                new_tokens.append(tokens[i])
                i += 1
        
        if changed:
            tokens = new_tokens

    collapsed = " ".join(tokens)
    collapsed = re.sub(r'\s+([.,!?;:])', r'\1', collapsed)
    return collapsed.strip()


def strip_hallucination_phrases(text: str) -> str:
    """Filter out standard Whisper hallucination phrases and repetitive acoustic tags."""
    if not text or not text.strip():
        return ""
    
    res = text.strip()
    res = REPEATED_BRACKET_PATTERN.sub(lambda m: m.group(1).strip() + " ", res)
    res = REPEATED_PAREN_PATTERN.sub(lambda m: m.group(1).strip() + " ", res)
    res = REPEATED_MUSIC_NOTE_PATTERN.sub("♪ ", res)
    res = REPEATED_PUNCTUATION_PATTERN.sub("... ", res)
    
    for pattern in HALLUCINATION_PATTERNS:
        if pattern.fullmatch(res.strip(' .,!?;:')):
            return ""
        res = pattern.sub("", res)
        
    return res.strip()


def trim_trailing_degenerate_tail(segments: list[dict], min_tail_repeats: int = 3) -> list[dict]:
    """Inspect the trailing segments of a transcript and truncate degenerate infinite loops at the end."""
    if not segments or len(segments) < min_tail_repeats:
        return segments
    
    last_texts = [s.get("text", "").strip().lower() for s in segments[-min_tail_repeats:]]
    if len(last_texts) >= min_tail_repeats and all(t and t == last_texts[0] for t in last_texts):
        first_rep_idx = len(segments) - min_tail_repeats
        while first_rep_idx > 0 and segments[first_rep_idx - 1].get("text", "").strip().lower() == last_texts[0]:
            first_rep_idx -= 1
        return segments[:first_rep_idx + 1]
    
    return segments


def scrub_transcript_segments(segments: list[dict], collapse_loops: bool = True) -> list[dict]:
    """Comprehensive scrubber for transcript segment lists:
    1. Sanity-checks and clamps start/end timestamps.
    2. Strips hallucination phrases and collapses repeating n-grams.
    3. Collapses consecutive duplicate segments.
    4. Trims trailing degenerate tails.
    5. Cleans and aligns word timestamp lists.
    """
    if not segments:
        return []
    
    cleaned_segments = []
    prev_text_norm = ""
    
    for seg in segments:
        if not isinstance(seg, dict):
            continue
        
        start = max(0.0, float(seg.get("start", 0.0)))
        end = max(start, float(seg.get("end", start)))
        raw_text = str(seg.get("text", "")).strip()
        # Strip accidental baked-in timestamp or speaker prefix (e.g. from previous edit corruptions)
        raw_text = re.sub(r'^\s*\[?(?:\d{1,2}:)?\d{1,2}:\d{2}(?:\.\d{1,3})?\]?\s+', '', raw_text)
        raw_text = re.sub(r'^(?:Speaker\s+\d+|[A-Z][a-zA-Z0-9_\s]{1,30}):\s*', '', raw_text)
        
        scrubbed_text = strip_hallucination_phrases(raw_text)
        if collapse_loops and scrubbed_text:
            scrubbed_text = collapse_repeating_ngrams(scrubbed_text)
        
        if not scrubbed_text:
            continue
            
        curr_text_norm = scrubbed_text.lower().rstrip('.,!?;:')
        if curr_text_norm and curr_text_norm == prev_text_norm and cleaned_segments:
            cleaned_segments[-1]["end"] = max(cleaned_segments[-1]["end"], end)
            continue
        
        words = seg.get("words", [])
        if end - start > 300.0 and len(scrubbed_text.split()) < 15:
            if words and len(words) > 0:
                end = max(start + 1.0, float(words[-1].get("end", start + 5.0)))
            else:
                end = start + min(30.0, max(2.0, len(scrubbed_text.split()) * 0.4))
        
        clean_words = []
        if isinstance(words, list):
            for w in words:
                if not isinstance(w, dict):
                    continue
                w_text = str(w.get("word", "")).strip()
                if not w_text:
                    continue
                w_start = max(start, float(w.get("start", start)))
                w_end = max(w_start, float(w.get("end", w_start)))
                clean_w = dict(w)
                clean_w["word"] = w_text
                clean_w["start"] = w_start
                clean_w["end"] = w_end
                clean_words.append(clean_w)
        
        new_seg = dict(seg)
        new_seg["start"] = start
        new_seg["end"] = end
        new_seg["text"] = scrubbed_text
        if clean_words:
            scrubbed_tokens = scrubbed_text.split()
            if len(clean_words) > len(scrubbed_tokens) and scrubbed_tokens:
                diff = len(clean_words) - len(scrubbed_tokens)
                if [w["word"].strip().lower() for w in clean_words[diff:]] == [t.strip().lower() for t in scrubbed_tokens]:
                    clean_words = clean_words[diff:]
            new_seg["words"] = clean_words
        elif "words" in new_seg:
            new_seg["words"] = []
            
        cleaned_segments.append(new_seg)
        prev_text_norm = curr_text_norm
        
    cleaned_segments = trim_trailing_degenerate_tail(cleaned_segments)
    return cleaned_segments


def scrub_transcript(transcript: dict) -> dict:
    """Clean and sanitize a complete transcript payload dictionary."""
    if not isinstance(transcript, dict):
        return transcript
    
    clean = copy.deepcopy(transcript)
    segments = clean.get("segments", [])
    if isinstance(segments, list):
        clean_segs = scrub_transcript_segments(segments)
        clean["segments"] = clean_segs
        clean["text"] = " ".join([s.get("text", "").strip() for s in clean_segs if s.get("text", "").strip()])
    return clean
