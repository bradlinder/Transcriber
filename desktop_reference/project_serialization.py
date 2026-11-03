"""Radio & TV Segmenter — Project file serialization and safe decompression.

Provides transparent gzip compression for .rtvs project files, legacy JSON compatibility,
gzip-bomb defense via bounded streaming decompression, and project data sanitization.
"""

from __future__ import annotations

import gzip
import io
import json
import math
import os
from pathlib import Path

from core_utils import safe_replace

MAX_DECOMPRESSED_PROJECT_BYTES = 200 * 1024 * 1024  # 200 MB; far larger than any legitimate project JSON


def sanitize_project_data_for_storage(data, parent_key: str = ""):
    """Recursively optimize project data before serialization:
    - Omit heavy binary/derived waveform peaks, thumbnails, and undo histories.
    - Round audio timestamps, durations, and offsets to millisecond precision (round(t, 3)).
    - Round confidence scores, logprobs, and temperatures to practical precision (round(p, 3)).
    - Round other general floating-point values to 4 decimal places.
    """
    if isinstance(data, dict):
        cleaned = {}
        for k, v in data.items():
            if k in ("waveform_peaks", "undo_stack", "history", "video_thumbnails", "thumbnail_cache"):
                continue
            cleaned[k] = sanitize_project_data_for_storage(v, parent_key=k)
        return cleaned
    elif isinstance(data, list):
        return [sanitize_project_data_for_storage(item, parent_key=parent_key) for item in data]
    elif isinstance(data, float):
        if math.isnan(data) or math.isinf(data):
            return 0.0
        k_lower = parent_key.lower()
        if any(k in k_lower for k in ("embedding", "embeddings", "centroid", "voiceprint", "vector", "profile")):
            r = round(data, 6)
        elif any(ts_key in k_lower for ts_key in ("start", "end", "duration", "position", "time", "offset", "padding", "threshold")):
            r = round(data, 3)
        elif any(prob_key in k_lower for prob_key in ("prob", "confidence", "logprob", "temperature", "ratio", "score")):
            r = round(data, 3)
        else:
            r = round(data, 4)
        return 0.0 if r == -0.0 else r
    else:
        return data


def serialize_rtvs_project(data: dict) -> bytes:
    """Serialize project dictionary into a transparently gzip-compressed payload."""
    clean_data = sanitize_project_data_for_storage(data)
    json_bytes = json.dumps(clean_data, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return gzip.compress(json_bytes, compresslevel=6)


def _decompress_gzip_bounded(raw_bytes: bytes, max_bytes: int = MAX_DECOMPRESSED_PROJECT_BYTES) -> bytes:
    """Decompress a gzip payload in chunks, stopping with a clear error the
    moment the decompressed size would exceed max_bytes, instead of
    materializing an unbounded amount of data in one gzip.decompress() call.
    """
    chunk_size = 1024 * 1024
    out = bytearray()
    with gzip.GzipFile(fileobj=io.BytesIO(raw_bytes), mode="rb") as gz:
        while True:
            chunk = gz.read(chunk_size)
            if not chunk:
                break
            out += chunk
            if len(out) > max_bytes:
                raise ValueError(
                    f"Project file decompresses to more than {max_bytes // (1024 * 1024)} MB; "
                    "refusing to load (the file may be corrupted or invalid)."
                )
    return bytes(out)


def deserialize_rtvs_project(raw_bytes: bytes) -> dict:
    """Deserialize project payload, transparently handling both gzip-compressed (.rtvs)
    and legacy uncompressed UTF-8 JSON files.

    Decompression is streamed with an upper ceiling (MAX_DECOMPRESSED_PROJECT_BYTES)
    rather than done in one unbounded gzip.decompress() call, so a corrupted or
    maliciously crafted small gzip payload that expands to gigabytes can't exhaust
    memory and crash the app -- legitimate project JSON is orders of magnitude
    smaller than the ceiling.
    """
    if not raw_bytes:
        return {}
    if len(raw_bytes) >= 2 and raw_bytes[:2] == b"\x1f\x8b":
        text = _decompress_gzip_bounded(raw_bytes).decode("utf-8")
    else:
        text = raw_bytes.decode("utf-8", errors="replace")
    return json.loads(text)


def read_rtvs_project_file(file_path) -> dict:
    """Transparently read an .rtvs or .json project file from disk (compressed or uncompressed)."""
    p = Path(file_path)
    st_size = p.stat().st_size
    if st_size > MAX_DECOMPRESSED_PROJECT_BYTES:
        raise ValueError(
            f"Project file exceeds maximum allowed size ({st_size} bytes > {MAX_DECOMPRESSED_PROJECT_BYTES} bytes)."
        )
    with open(p, "rb") as f:
        raw = f.read(MAX_DECOMPRESSED_PROJECT_BYTES + 1)
    if len(raw) > MAX_DECOMPRESSED_PROJECT_BYTES:
        raise ValueError("Project file exceeds maximum allowed size.")
    return deserialize_rtvs_project(raw)


def write_rtvs_project_file(file_path, data: dict):
    """Safely and atomically write a gzip-compressed .rtvs project file."""
    p = Path(file_path).resolve()
    p.parent.mkdir(parents=True, exist_ok=True)
    temp_file = p.with_name(p.name + ".tmp")
    blob = serialize_rtvs_project(data)
    with open(temp_file, "wb") as f:
        f.write(blob)
        f.flush()
        os.fsync(f.fileno())
    safe_replace(temp_file, p)
