"""Model-independent terminology and glossary support.

The glossary is deliberately kept outside any ASR or translation engine.
It provides:
- canonical spelling normalization for every transcription backend;
- persistent parsing of the legacy ``source -> preferred`` format;
- protected proper-noun rules for translation workers.

Legacy glossary entries remain valid. New code may also store dictionaries
with ``source``, ``preferred`` and ``do_not_translate`` keys.
"""
from __future__ import annotations

import json
import re
from typing import Any, Iterable


def parse_glossary(raw: Any) -> list[dict[str, Any]]:
    """Return normalized glossary entries while accepting legacy list strings."""
    if raw is None or raw == "":
        return []

    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except Exception:
            raw = [raw]
    if not isinstance(raw, (list, tuple)):
        raw = [raw]

    entries: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()

    for item in raw:
        if isinstance(item, dict):
            source = str(item.get("source", item.get("term", "")) or "").strip()
            preferred = str(
                item.get("preferred", item.get("target", source)) or source
            ).strip()
            dnt = bool(item.get("do_not_translate", item.get("protected", True)))
        else:
            text = str(item or "").strip()
            if not text:
                continue
            if "->" in text:
                source, preferred = (part.strip() for part in text.split("->", 1))
            else:
                source = preferred = text
            dnt = True

        if not source or not preferred:
            continue
        key = (source.casefold(), preferred.casefold())
        if key in seen:
            continue
        seen.add(key)
        entries.append(
            {
                "source": source,
                "preferred": preferred,
                "do_not_translate": dnt,
            }
        )

    # Longest first prevents a short term from rewriting part of a longer one.
    return sorted(entries, key=lambda x: len(str(x["source"])), reverse=True)


def load_glossary(settings_store=None) -> list[dict[str, Any]]:
    """Load the shared glossary from the application's QSettings object."""
    try:
        if settings_store is None:
            from PySide6.QtCore import QSettings
            settings_store = QSettings("RadioTVStorySegmenter", "RadioTVStorySegmenter")
        return parse_glossary(settings_store.value("glossary", ""))
    except Exception:
        return []


def _replace_term(text: str, source: str, preferred: str) -> str:
    if not text or not source:
        return text
    # Unicode-aware \w boundaries work for accented Latin names and avoid
    # changing a term embedded inside a larger word.
    pattern = re.compile(
        rf"(?<!\w){re.escape(source)}(?!\w)", re.IGNORECASE
    )
    return pattern.sub(lambda _m: preferred, text)


def normalize_text(text: str, glossary: Iterable[dict[str, Any]]) -> str:
    """Canonicalize glossary terms without changing surrounding punctuation."""
    result = str(text or "")
    for entry in glossary:
        result = _replace_term(
            result, str(entry["source"]), str(entry["preferred"])
        )
    return result


def normalize_transcript(transcript: dict[str, Any], glossary=None) -> dict[str, Any]:
    """Normalize transcript text and word labels for every ASR backend."""
    if not isinstance(transcript, dict):
        return transcript
    rules = parse_glossary(glossary) if glossary is not None else load_glossary()
    if not rules:
        return transcript

    # Copy only the structures we mutate; callers retain the rest of the payload.
    result = dict(transcript)
    segments = []
    for segment in transcript.get("segments", []) or []:
        if not isinstance(segment, dict):
            segments.append(segment)
            continue
        seg = dict(segment)
        words = []
        for word in segment.get("words", []) or []:
            if isinstance(word, dict):
                w = dict(word)
                if "word" in w:
                    w["word"] = normalize_text(w["word"], rules)
                words.append(w)
            else:
                words.append(word)
        if words:
            seg["words"] = words
        if "text" in seg:
            # Prefer rebuilding from timed words when available so the
            # displayed segment and its word-level representation agree.
            if words and all(isinstance(w, dict) and "word" in w for w in words):
                seg["text"] = " ".join(str(w["word"]).strip() for w in words).strip()
            else:
                seg["text"] = normalize_text(seg["text"], rules)
        segments.append(seg)

    result["segments"] = segments
    result["text"] = " ".join(
        str(s.get("text", "")).strip()
        for s in segments
        if isinstance(s, dict) and str(s.get("text", "")).strip()
    )
    return result


def translation_rules(glossary=None) -> list[dict[str, Any]]:
    """Return only entries explicitly marked as protected for translation."""
    rules = parse_glossary(glossary) if glossary is not None else load_glossary()
    return [r for r in rules if r.get("do_not_translate", True)]


def replace_term(text: str, source: str, preferred: str) -> str:
    """Public replacement helper for translation workers."""
    return _replace_term(text, source, preferred)


def split_protected_text(text: str, glossary=None) -> list[tuple[str, bool]]:
    """Split text into translatable and protected pieces.

    Protected glossary terms are deliberately removed from the text sent to a
    translation engine.  This is stronger than translating the whole sentence
    and trying to repair the output afterward: a protected term can never be
    translated because it is never presented to the model.
    """
    text = str(text or "")
    rules = translation_rules(glossary) if glossary is not None else translation_rules()
    if not text or not rules:
        return [(text, False)] if text else []

    # Longest-first rules prevent a shorter protected term from consuming part
    # of a longer one.  Use Unicode-aware word boundaries.
    alternatives = [str(r["source"]) for r in rules if str(r.get("source", "")).strip()]
    if not alternatives:
        return [(text, False)]
    pattern = re.compile(
        r"(?<!\w)(?:" + "|".join(re.escape(x) for x in alternatives) + r")(?!\w)",
        re.IGNORECASE,
    )
    by_source = {str(r["source"]).casefold(): str(r["preferred"]) for r in rules}
    pieces: list[tuple[str, bool]] = []
    last = 0
    for match in pattern.finditer(text):
        if match.start() > last:
            pieces.append((text[last:match.start()], False))
        source = match.group(0)
        preferred = by_source.get(source.casefold(), source)
        pieces.append((preferred, True))
        last = match.end()
    if last < len(text):
        pieces.append((text[last:], False))
    return pieces or [(text, False)]


def export_glossary_to_json(glossary: list[dict[str, Any]]) -> str:
    """Serialize glossary entries to formatted JSON string."""
    entries = parse_glossary(glossary)
    return json.dumps(entries, indent=2, ensure_ascii=False)


def export_glossary_to_csv(glossary: list[dict[str, Any]]) -> str:
    """Serialize glossary entries to standard CSV string."""
    import csv
    import io

    entries = parse_glossary(glossary)
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["source", "preferred", "do_not_translate"])
    for entry in entries:
        writer.writerow([
            entry.get("source", ""),
            entry.get("preferred", ""),
            "true" if entry.get("do_not_translate", True) else "false"
        ])
    return output.getvalue()


def import_glossary_from_text(raw_text: str) -> list[dict[str, Any]]:
    """Parse glossary entries from JSON, CSV, TSV, or plain text lines."""
    import csv
    import io

    raw_text = str(raw_text or "").strip()
    if not raw_text:
        return []

    # Attempt 1: Standard JSON parsing
    if raw_text.startswith("[") or raw_text.startswith("{"):
        try:
            return parse_glossary(json.loads(raw_text))
        except Exception:
            pass

    # Attempt 2: CSV / TSV with headers or delimiters
    delimiter = "\t" if "\t" in raw_text.splitlines()[0] else ","
    try:
        reader = csv.reader(io.StringIO(raw_text), delimiter=delimiter)
        rows = list(reader)
        if rows:
            first_row = [c.strip().lower() for c in rows[0]]
            has_header = any(h in first_row for h in ("source", "preferred", "term", "translation", "do_not_translate"))
            data_rows = rows[1:] if has_header else rows
            entries: list[dict[str, Any]] = []
            for row in data_rows:
                if not row or not any(cell.strip() for cell in row):
                    continue
                if len(row) >= 3:
                    src = row[0].strip()
                    pref = row[1].strip() or src
                    dnt = row[2].strip().lower() not in ("false", "0", "no", "n")
                    if src:
                        entries.append({"source": src, "preferred": pref, "do_not_translate": dnt})
                elif len(row) == 2:
                    src = row[0].strip()
                    pref = row[1].strip() or src
                    if src:
                        entries.append({"source": src, "preferred": pref, "do_not_translate": True})
                elif len(row) == 1:
                    line = row[0].strip()
                    if line:
                        if "->" in line:
                            s, p = (x.strip() for x in line.split("->", 1))
                            entries.append({"source": s, "preferred": p, "do_not_translate": True})
                        else:
                            entries.append({"source": line, "preferred": line, "do_not_translate": True})
            if entries:
                return parse_glossary(entries)
    except Exception:
        pass

    # Attempt 3: Line-by-line fallback
    lines = [l.strip() for l in raw_text.splitlines() if l.strip()]
    return parse_glossary(lines)

