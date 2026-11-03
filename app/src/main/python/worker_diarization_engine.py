"""worker_diarization_engine.py - Speaker Diarization, Neural VAD & Embedding Pipeline.

Provides:
- FFmpeg 16 kHz mono audio normalization with highpass/lowpass conditioning
- Silero Neural VAD with Two-Pass Pause Bridging (< 100ms)
- Batched WeSpeaker ResNet34-LM ONNX embedding extraction (256-d vectors)
- Solo fast-path speaker detection (1-2s in-memory execution)
- Fast transcript-guided speaker assignment
- Multi-speaker AHC clustering with duration smoothing and micro-fragment merging
"""

from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

# Pre-import torch ONCE at module load time before onnxruntime or diarize
# to prevent C++ symbol/DLL collisions and duplicate TORCH_LIBRARY("prims", "DEF")
# registration errors if a lazy import inside a function fails or retries.
try:
    import torch as _GLOBAL_TORCH
except Exception as _torch_init_err:
    _GLOBAL_TORCH = None

from worker_diarization_clustering import (
    SHORT_FRAGMENT_MERGE_THRESHOLD,
    _calculate_embedding_for_interval,
    _normalize_expected_speakers,
    _normalize_speaker_labels,
    _sensitivity_distance_threshold,
    apply_clustering_patches,
    assign_short_segments_to_centroids,
    fast_cluster_ahc,
)

# Callback for IPC message emission; default to stdout JSON
_EMIT_FN: Optional[Callable[..., None]] = None


def set_emit_callback(fn: Callable[..., None]):
    """Set custom IPC emitter function."""
    global _EMIT_FN
    _EMIT_FN = fn


def _emit(message_type: str, **payload):
    if _EMIT_FN is not None:
        _EMIT_FN(message_type, **payload)
    else:
        message = {"type": message_type, **payload}
        sys.stdout.buffer.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
        sys.stdout.buffer.flush()


def _safe_tensor_from_numpy(arr):
    """Safely convert a numpy float32 array into a PyTorch tensor."""
    import torch
    import numpy as np
    try:
        if isinstance(arr, np.ndarray):
            if not arr.flags.writeable or not arr.flags.c_contiguous:
                arr = np.ascontiguousarray(arr, dtype=np.float32).copy()
        return torch.from_numpy(arr)
    except (RuntimeError, UserWarning, AttributeError, Exception):
        pass
    try:
        return torch.as_tensor(arr, dtype=torch.float32)
    except (RuntimeError, UserWarning, AttributeError, Exception):
        pass
    return torch.tensor(arr.tolist(), dtype=torch.float32)


def normalize_audio_for_diarization(source_path):
    """Extract a normalized 16 kHz mono WAV using the local FFmpeg binary."""
    ffmpeg = None
    filename = "ffmpeg.exe" if os.name == "nt" else "ffmpeg"
    candidates = [
        Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / "runtime" / "bin" / filename,
        Path(sys.executable).resolve().parent / "runtime" / "bin" / filename,
        Path(__file__).resolve().parent / "runtime" / "bin" / filename,
    ]
    for candidate in candidates:
        if candidate.is_file():
            ffmpeg = str(candidate)
            break
    if ffmpeg is None:
        ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError(
            "FFmpeg could not be found on PATH. Speaker Detection requires the "
            "local FFmpeg installation to decode this media file."
        )

    source = Path(source_path).resolve()
    temp_dir = tempfile.TemporaryDirectory(prefix="prs_diarization_")
    wav_path = Path(temp_dir.name) / "audio_16khz_mono.wav"

    _emit("progress", percent=18, message="Extracting and normalizing audio with local FFmpeg...")
    command = [
        ffmpeg,
        "-y",
        "-v", "error",
        "-i", str(source),
        "-vn", "-sn", "-dn",
        "-map", "0:a:0?",
        "-af", "highpass=f=85,lowpass=f=7500",
        "-ac", "1",
        "-ar", "16000",
        "-c:a", "pcm_s16le",
        str(wav_path),
    ]
    try:
        creationflags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        result = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=900,
            creationflags=creationflags,
        )
    except subprocess.TimeoutExpired as exc:
        temp_dir.cleanup()
        raise RuntimeError("FFmpeg audio normalization timed out after 15 minutes.") from exc
    if result.returncode != 0 or not wav_path.exists() or wav_path.stat().st_size == 0:
        detail = result.stderr.strip() or "FFmpeg did not produce an audio file."
        temp_dir.cleanup()
        raise RuntimeError(f"FFmpeg could not extract an audio stream: {detail}")

    _emit("progress", percent=32, message="Audio extracted and normalized to 16 kHz mono WAV.")
    return temp_dir, wav_path


def configure_optimal_pytorch_threads():
    """Clamp torch's intra-op/inter-op thread pools to match the
    OMP/ORT clamp set at module import time, for the torch-based Silero VAD step.
    """
    try:
        import torch
        threads = max(1, min(int(os.environ.get("OMP_NUM_THREADS", "4")), 8))
        torch.set_num_threads(threads)
        try:
            torch.set_num_interop_threads(threads)
        except RuntimeError:
            pass
    except Exception:
        pass


def _diarize_solo_fast_path(audio_file, transcript_file=None):
    """Solo fast-path: a single expected speaker means there is nothing to
    embed or cluster. If an existing transcript is provided, map segments
    directly in memory. Otherwise run Silero VAD alone, tag every detected
    speech block as 'Speaker 1', and return in ~1-2s.
    """
    if transcript_file and os.path.exists(transcript_file):
        try:
            with open(transcript_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            t_segs = data.get("segments", [])
            if t_segs:
                segments = [
                    {
                        "start": float(seg["start"]),
                        "end": float(seg["end"]),
                        "speaker": "Speaker 1",
                    }
                    for seg in t_segs
                    if "start" in seg and "end" in seg and float(seg["end"]) > float(seg["start"])
                ]
                if segments:
                    last_end = max(s["end"] for s in segments)
                    output = {
                        "num_speakers": 1,
                        "speakers": ["Speaker 1"],
                        "audio_duration": float(last_end),
                        "segments": segments,
                    }
                    _emit("progress", percent=95, message="Transcript-guided solo assignment complete.")
                    _emit("finished", result=output)
                    return 0
        except Exception:
            pass

    configure_optimal_pytorch_threads()
    temp_dir = None
    try:
        _emit("progress", percent=5, message="Local Speaker Detection helper initialized (solo fast-path).")
        temp_dir, normalized_wav = normalize_audio_for_diarization(audio_file)
        _emit("progress", percent=45, message="Detecting speech regions (solo fast-path, no clustering needed)...")

        import torch
        import soundfile as sf
        from silero_vad import load_silero_vad, get_speech_timestamps

        data, sample_rate = sf.read(str(normalized_wav), dtype="float32")
        wav = _safe_tensor_from_numpy(data)
        if wav.ndim > 1:
            wav = wav.mean(dim=-1)

        model = load_silero_vad()
        timestamps = get_speech_timestamps(wav, model, sampling_rate=sample_rate, return_seconds=True)

        segments = [
            {"start": float(ts["start"]), "end": float(ts["end"]), "speaker": "Speaker 1"}
            for ts in timestamps
        ]
        audio_duration = float(len(wav)) / float(sample_rate) if sample_rate else 0.0

        output = {
            "num_speakers": 1 if segments else 0,
            "speakers": ["Speaker 1"] if segments else [],
            "audio_duration": audio_duration,
            "segments": segments,
        }
        _emit("progress", percent=95, message="Speech regions detected; single speaker assigned.")
        _emit("finished", result=output)
        return 0
    except Exception as exc:
        _emit("error", message=f"{type(exc).__name__}: {exc}\n\n{traceback.format_exc()}")
        return 4
    finally:
        if temp_dir is not None:
            temp_dir.cleanup()


_LAST_EXTRACTED_EMBEDDINGS = None
_WESPEAKER_MODEL_SINGLETON = None
_WESPEAKER_MODEL_LOCK = threading.Lock()


def _get_wespeaker_model():
    """Retrieve or initialize the cached WeSpeaker ONNX model instance."""
    global _WESPEAKER_MODEL_SINGLETON
    if _WESPEAKER_MODEL_SINGLETON is None:
        with _WESPEAKER_MODEL_LOCK:
            if _WESPEAKER_MODEL_SINGLETON is None:
                import wespeakerruntime as wespeaker_rt
                _WESPEAKER_MODEL_SINGLETON = wespeaker_rt.Speaker(lang="en")
    return _WESPEAKER_MODEL_SINGLETON


def extract_embeddings_batched(
    audio_path,
    speech_segments,
    batch_size=32,
    min_segment_duration=0.4,
    embedding_window=1.2,
    embedding_step=0.6,
    progress_callback=None,
):
    """Extract 256-dim speaker embeddings using WeSpeaker ResNet34-LM (ONNX)
    with in-memory Fbank computation and batched ONNX session inference (batch size 16-32).
    """
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio.compliance.kaldi as kaldi
    from diarize.utils import SubSegment

    model = _get_wespeaker_model()
    session = model.session

    audio_data, sr = sf.read(str(audio_path), dtype="float32")
    if audio_data.ndim > 1:
        audio_data = audio_data.mean(axis=1)

    if sr != 16000:
        import torchaudio
        resampler = torchaudio.transforms.Resample(orig_freq=sr, new_freq=16000)
        base_tensor = _safe_tensor_from_numpy(audio_data).unsqueeze(0)
        audio_tensor = resampler(base_tensor).squeeze(0)
        audio_data = audio_tensor.detach().cpu().numpy()
        sr = 16000
    else:
        audio_tensor = _safe_tensor_from_numpy(audio_data)

    window_slices = []

    for idx, seg in enumerate(speech_segments):
        seg_duration = getattr(seg, "duration", float(seg.end) - float(seg.start))
        if seg_duration < min_segment_duration:
            continue

        if seg_duration <= embedding_window * 1.5:
            windows = [(float(seg.start), float(seg.end))]
        else:
            windows = []
            win_start = float(seg.start)
            seg_end = float(seg.end)
            while win_start + min_segment_duration < seg_end:
                win_end = min(win_start + embedding_window, seg_end)
                windows.append((win_start, win_end))
                win_start += embedding_step

        for win_start, win_end in windows:
            start_sample = int(win_start * sr)
            end_sample = int(win_end * sr)
            if end_sample <= start_sample:
                continue
            window_slices.append((win_start, win_end, start_sample, end_sample, idx))

    if not window_slices:
        return np.empty((0, 256), dtype=np.float32), []

    all_feats = []
    valid_subsegments = []

    for win_start, win_end, start_samp, end_samp, parent_idx in window_slices:
        chunk = audio_tensor[start_samp:end_samp]
        if len(chunk) < int(0.1 * sr):
            continue
        try:
            chunk_wave = chunk.unsqueeze(0) * (1 << 15)
            mat = kaldi.fbank(
                chunk_wave,
                num_mel_bins=80,
                frame_length=25,
                frame_shift=10,
                dither=0.0,
                sample_frequency=sr,
                window_type="hamming",
                use_energy=False,
            )
            mat = mat.numpy()
            mat = mat - np.mean(mat, axis=0)
            all_feats.append(mat)
            valid_subsegments.append(SubSegment(start=win_start, end=win_end, parent_idx=parent_idx))
        except Exception:
            continue

    if not all_feats:
        return np.empty((0, 256), dtype=np.float32), []

    embeddings = []
    total_feats = len(all_feats)
    total_batches = (total_feats + batch_size - 1) // batch_size

    for b in range(total_batches):
        batch_slice = all_feats[b * batch_size : (b + 1) * batch_size]
        max_t = max(f.shape[0] for f in batch_slice)
        batch_feats = np.zeros((len(batch_slice), max_t, 80), dtype=np.float32)
        for i, f in enumerate(batch_slice):
            batch_feats[i, : f.shape[0], :] = f

        try:
            embs = session.run(output_names=["embs"], input_feed={"feats": batch_feats})[0]
            for emb in embs:
                embeddings.append(emb)
        except Exception:
            for f in batch_slice:
                single_in = np.expand_dims(f, 0).astype(np.float32)
                emb = session.run(output_names=["embs"], input_feed={"feats": single_in})[0][0]
                embeddings.append(emb)

        if progress_callback:
            progress_callback(len(embeddings), total_feats)

    X = np.stack(embeddings)
    global _LAST_EXTRACTED_EMBEDDINGS
    _LAST_EXTRACTED_EMBEDDINGS = (valid_subsegments, X)
    return X, valid_subsegments


def run_vad_two_pass(
    audio_path,
    threshold=0.60,
    min_speech_duration_ms=250,
    min_silence_duration_ms=300,
    speech_pad_ms=10,
    bridge_pause_threshold_sec=0.10,
):
    """Silero VAD with Pass 1 Pause Bridging (< 100ms)."""
    import soundfile as sf
    import torch
    from silero_vad import get_speech_timestamps, load_silero_vad
    from diarize.utils import SpeechSegment

    data, sample_rate = sf.read(str(audio_path), dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=-1)

    if sample_rate != 16000:
        try:
            from scipy.signal import resample_poly
            from math import gcd
            g = gcd(sample_rate, 16000)
            data = resample_poly(data, 16000 // g, sample_rate // g).astype("float32")
            sample_rate = 16000
        except Exception:
            try:
                import torchaudio
                t_wav = _safe_tensor_from_numpy(data)
                resampler = torchaudio.transforms.Resample(orig_freq=sample_rate, new_freq=16000)
                data = resampler(t_wav).detach().cpu().numpy()
                sample_rate = 16000
            except Exception:
                pass

    wav = _safe_tensor_from_numpy(data)

    try:
        vad_model = load_silero_vad(onnx=True)
    except Exception:
        vad_model = load_silero_vad()
    speech_timestamps = get_speech_timestamps(
        wav,
        vad_model,
        sampling_rate=16000,
        threshold=threshold,
        min_speech_duration_ms=min_speech_duration_ms,
        min_silence_duration_ms=min_silence_duration_ms,
        speech_pad_ms=speech_pad_ms,
        return_seconds=True,
    )

    if not speech_timestamps:
        return []

    speech_timestamps.sort(key=lambda ts: float(ts["start"]))

    merged_timestamps = [
        {"start": float(speech_timestamps[0]["start"]), "end": float(speech_timestamps[0]["end"])}
    ]
    for ts in speech_timestamps[1:]:
        curr_start = float(ts["start"])
        curr_end = float(ts["end"])
        prev = merged_timestamps[-1]
        gap = curr_start - prev["end"]
        if gap < bridge_pause_threshold_sec:
            prev["end"] = max(prev["end"], curr_end)
        else:
            merged_timestamps.append({"start": curr_start, "end": curr_end})

    return [SpeechSegment(start=ts["start"], end=ts["end"]) for ts in merged_timestamps]


def run_transcript_guided_diarization(
    normalized_wav,
    transcript_file,
    expected_speakers="auto",
    sensitivity="normal",
    progress_callback=None,
):
    """Execute transcript-guided speaker diarization directly from sentence boundaries."""
    import json
    import numpy as np
    from worker_diarization_clustering import fast_cluster_ahc, _normalize_speaker_labels, _sensitivity_distance_threshold

    class SpeechSegment:
        def __init__(self, start, end, speaker="Speaker 1"):
            self.start = float(start)
            self.end = float(end)
            self.duration = float(end) - float(start)
            self.speaker = speaker

    if sensitivity:
        os.environ["PRS_DIARIZATION_SENSITIVITY"] = str(sensitivity)

    with open(transcript_file, "r", encoding="utf-8") as f:
        t_data = json.load(f)
    raw_transcript_segments = t_data.get("segments", [])

    valid_segments = []
    for s in raw_transcript_segments:
        if "start" in s and "end" in s:
            st = float(s["start"])
            en = float(s["end"])
            if en - st > 0.05:
                valid_segments.append({"start": st, "end": en, "text": s.get("text", "")})

    valid_segments.sort(key=lambda x: x["start"])
    if not valid_segments:
        return None

    primary_speech_segments = []
    short_speech_segments = []

    for s in valid_segments:
        st = s["start"]
        en = s["end"]
        dur = en - st

        if dur < 0.8:
            short_speech_segments.append(SpeechSegment(start=st, end=en))
        elif dur > 6.0:
            curr_start = st
            while curr_start + 1.0 < en:
                curr_end = min(curr_start + 3.0, en)
                primary_speech_segments.append(SpeechSegment(start=curr_start, end=curr_end))
                curr_start += 1.5
        else:
            primary_speech_segments.append(SpeechSegment(start=st, end=en))

    if len(primary_speech_segments) < 2 and short_speech_segments:
        primary_speech_segments.extend(short_speech_segments)
        short_speech_segments = []

    embeddings, subsegments = extract_embeddings_batched(
        normalized_wav,
        primary_speech_segments,
        batch_size=32,
        min_segment_duration=0.4,
    )

    if len(embeddings) == 0:
        return {"segments": valid_segments}

    labels = fast_cluster_ahc(embeddings, distance_threshold=_sensitivity_distance_threshold())

    result_segments = []
    for idx, s in enumerate(valid_segments):
        raw_label = f"SPEAKER_{labels[idx % len(labels)]:02d}" if len(labels) > 0 else "SPEAKER_00"
        result_segments.append({"start": s["start"], "end": s["end"], "text": s["text"], "speaker": raw_label})

    normalized_segments = _normalize_speaker_labels(result_segments)
    return {"segments": normalized_segments}
    )


def diarize(audio_file, expected_speakers="auto", transcript_file=None, sensitivity="normal"):
    """Execute complete speaker diarization pipeline on an audio media file."""
    expected_speakers = _normalize_expected_speakers(expected_speakers)
    if sensitivity:
        os.environ["PRS_DIARIZATION_SENSITIVITY"] = str(sensitivity)

    if expected_speakers == "1":
        return _diarize_solo_fast_path(audio_file, transcript_file=transcript_file)

    configure_optimal_pytorch_threads()

    try:
        import torch
        import soundfile as sf
        import torchaudio

        def safe_torchaudio_load(filepath, *args, **kwargs):
            data, sample_rate = sf.read(str(filepath), dtype="float32")
            tensor = _safe_tensor_from_numpy(data)
            if tensor.ndim == 1:
                tensor = tensor.unsqueeze(0)
            else:
                tensor = tensor.T
            return tensor, sample_rate

        torchaudio.load = safe_torchaudio_load

        try:
            import silero_vad.utils_vad as silero_utils
            def safe_silero_read_audio(path: str, sampling_rate: int = 16000):
                data, sr = sf.read(str(path), dtype="float32")
                wav = _safe_tensor_from_numpy(data)
                if wav.ndim > 1:
                    wav = wav.mean(dim=-1)
                if sr != sampling_rate:
                    transform = torchaudio.transforms.Resample(orig_freq=sr, new_freq=sampling_rate)
                    wav = transform(wav)
                return wav

            silero_utils.read_audio = safe_silero_read_audio
        except Exception:
            pass

    except Exception as exc:
        _emit("warning", message=f"Could not patch torchaudio.load: {exc}")

    try:
        import diarize
        import diarize.embeddings
        import diarize.vad
        from diarize import diarize as diarize_fn

        apply_clustering_patches()
        diarize.embeddings.extract_embeddings = extract_embeddings_batched
        diarize.extract_embeddings = extract_embeddings_batched
        diarize.vad.run_vad = run_vad_two_pass
        diarize.run_vad = run_vad_two_pass
        if hasattr(diarize_fn, "__globals__"):
            diarize_fn.__globals__["run_vad"] = run_vad_two_pass
            diarize_fn.__globals__["extract_embeddings"] = extract_embeddings_batched
    except Exception as exc:
        _emit("error", message=f"Could not load the local diarization package: {type(exc).__name__}: {exc}")
        return 3

    diarization_start_time = time.time()
    global_last_tqdm_time = [0.0]
    global_tqdm_completed = [False]

    def _format_elapsed(start_epoch):
        elapsed = time.time() - start_epoch
        mins, secs = divmod(int(elapsed), 60)
        return f"{mins:02d}m {secs:02d}s"

    try:
        import tqdm
        class ProgressTqdm(tqdm.tqdm):
            def update(self, n=1):
                super().update(n)
                now = time.time()
                global_last_tqdm_time[0] = now
                if self.total and self.total > 0:
                    frac = min(1.0, max(0.0, float(self.n) / float(self.total)))
                    pct = int(42 + frac * 32)
                    if frac >= 0.99:
                        global_tqdm_completed[0] = True
                    elapsed_str = _format_elapsed(diarization_start_time)
                    _emit(
                        "progress",
                        percent=pct,
                        message=f"Extracting speaker signatures ({elapsed_str} elapsed - {pct}%)..."
                    )
        tqdm.tqdm = ProgressTqdm
        if hasattr(tqdm, "auto"):
            tqdm.auto.tqdm = ProgressTqdm
    except Exception:
        pass

    temp_dir = None
    try:
        _emit("progress", percent=5, message="Local Speaker Detection helper initialized.")
        _emit("progress", percent=12, message="Loading local VAD and speaker-embedding models...")
        temp_dir, normalized_wav = normalize_audio_for_diarization(audio_file)

        import wave
        with wave.open(str(normalized_wav), "rb") as wf:
            total_audio_sec = wf.getnframes() / float(wf.getframerate())
        total_audio_min = total_audio_sec / 60.0

        is_transcript_guided = bool(transcript_file and os.path.exists(transcript_file))
        if is_transcript_guided:
            _emit(
                "progress",
                percent=36,
                message=f"Transcript-guided analysis ({total_audio_min:.1f}m audio, bypassing raw VAD)..."
            )
        else:
            _emit(
                "progress",
                percent=36,
                message=f"Analyzing speech regions with Two-Pass VAD ({total_audio_min:.1f}m audio)..."
            )

        _emit("progress", percent=42, message="Extracting batched speaker acoustic embeddings...")

        stop_ticker = threading.Event()

        def ticker():
            clustering_start_t = None
            curr_pct = 42

            while not stop_ticker.wait(1.5):
                now = time.time()
                elapsed_str = _format_elapsed(diarization_start_time)

                time_since_tqdm = now - global_last_tqdm_time[0] if global_last_tqdm_time[0] > 0 else 999.0
                is_clustering = global_tqdm_completed[0] or (time_since_tqdm > 3.0 and curr_pct >= 70)

                if is_clustering:
                    if clustering_start_t is None:
                        clustering_start_t = now

                    c_elapsed = now - clustering_start_t
                    asymptotic_target = 75 + int(19.0 * (1.0 - math.exp(-c_elapsed / 120.0)))
                    curr_pct = max(curr_pct, min(94, asymptotic_target))

                    _emit(
                        "progress",
                        percent=curr_pct,
                        message=f"Clustering speaker signatures (AHC O(N^2) - {elapsed_str} elapsed)..."
                    )
                else:
                    if curr_pct < 74:
                        curr_pct = min(74, curr_pct + 1)
                    _emit(
                        "progress",
                        percent=curr_pct,
                        message=f"Extracting speaker signatures ({elapsed_str} elapsed)..."
                    )

        ticker_thread = threading.Thread(target=ticker, daemon=True)
        ticker_thread.start()

        try:
            result = None
            if is_transcript_guided:
                try:
                    result = run_transcript_guided_diarization(
                        normalized_wav,
                        transcript_file,
                        expected_speakers=expected_speakers,
                        progress_callback=lambda p, msg: _emit("progress", percent=p, message=msg),
                    )
                except Exception as tg_exc:
                    _emit(
                        "warning",
                        message=f"Transcript-guided diarization fallback to standalone VAD: {tg_exc}"
                    )
                    result = None

            if result is None:
                kwargs = {}
                if expected_speakers == "2":
                    kwargs["num_speakers"] = 2
                elif expected_speakers == "3+":
                    kwargs["min_speakers"] = 3

                import inspect
                try:
                    sig = inspect.signature(diarize_fn)
                    accepts_distance_threshold = (
                        "distance_threshold" in sig.parameters
                        or any(
                            p.kind == inspect.Parameter.VAR_KEYWORD
                            for p in sig.parameters.values()
                        )
                    )
                except Exception:
                    accepts_distance_threshold = False

                call_kwargs = dict(kwargs)
                if accepts_distance_threshold:
                    call_kwargs["distance_threshold"] = _sensitivity_distance_threshold(sensitivity)
                result = diarize_fn(str(normalized_wav), **call_kwargs)
        finally:
            stop_ticker.set()
            ticker_thread.join(timeout=1.0)

        _emit("progress", percent=89, message="Post-processing and smoothing speaker transitions...")

        global _LAST_EXTRACTED_EMBEDDINGS
        last_subsegs, last_embs = (None, None)
        if _LAST_EXTRACTED_EMBEDDINGS is not None:
            last_subsegs, last_embs = _LAST_EXTRACTED_EMBEDDINGS

        segments = []
        for segment in result.segments:
            seg_dict = {
                "start": float(segment.start),
                "end": float(segment.end),
                "speaker": str(segment.speaker),
            }
            if last_subsegs is not None and last_embs is not None and len(last_embs) > 0:
                calc_emb = _calculate_embedding_for_interval(segment.start, segment.end, last_subsegs, last_embs)
                if calc_emb is not None:
                    seg_dict["embedding"] = calc_emb
            segments.append(seg_dict)

        merged_count = 0
        if len(segments) > 2:
            import numpy as np

            starts = np.array([s["start"] for s in segments], dtype=np.float64)
            ends = np.array([s["end"] for s in segments], dtype=np.float64)
            durations = np.maximum(0.0, ends - starts)
            speakers = [s["speaker"] for s in segments]

            short_mask = (durations[1:-1] <= SHORT_FRAGMENT_MERGE_THRESHOLD)

            for offset_i, is_short in enumerate(short_mask):
                i = offset_i + 1
                if is_short and speakers[i - 1] == speakers[i + 1] and speakers[i] != speakers[i - 1]:
                    segments[i]["speaker"] = speakers[i - 1]
                    speakers[i] = speakers[i - 1]
                    merged_count += 1

        merged_segments = []
        for segment in segments:
            if (
                merged_segments
                and merged_segments[-1]["speaker"] == segment["speaker"]
                and segment["start"] <= merged_segments[-1]["end"] + 0.05
            ):
                merged_segments[-1]["end"] = max(merged_segments[-1]["end"], segment["end"])
                e1 = merged_segments[-1].get("embedding")
                e2 = segment.get("embedding")
                if e1 is not None and e2 is not None:
                    try:
                        import numpy as np
                        v1 = np.array(e1, dtype=np.float32)
                        v2 = np.array(e2, dtype=np.float32)
                        v_mean = (v1 + v2) / 2.0
                        norm = np.linalg.norm(v_mean)
                        if norm > 0:
                            v_mean = v_mean / norm
                        merged_segments[-1]["embedding"] = [round(float(x), 6) for x in v_mean.tolist()]
                    except Exception:
                        pass
                elif e2 is not None and e1 is None:
                    merged_segments[-1]["embedding"] = e2
            else:
                merged_segments.append(segment)
        segments = merged_segments
        segments = _normalize_speaker_labels(segments)

        speakers = sorted({seg["speaker"] for seg in segments if seg.get("speaker")})
        _emit(
            "progress",
            percent=93,
            message=f"Detected {len(speakers)} speaker(s); merged {merged_count} short fragment(s).",
        )

        window_embeddings = []
        if last_subsegs is not None and last_embs is not None and len(last_embs) > 0:
            try:
                import numpy as np
                for s_item, e_vec in zip(last_subsegs, last_embs):
                    norm_v = np.array(e_vec, dtype=np.float32)
                    norm_val = np.linalg.norm(norm_v)
                    if norm_val > 0:
                        norm_v = norm_v / norm_val
                    window_embeddings.append({
                        "start": float(s_item.start),
                        "end": float(s_item.end),
                        "embedding": [round(float(x), 6) for x in norm_v.tolist()],
                    })
            except Exception:
                window_embeddings = []

        output = {
            "num_speakers": len(speakers),
            "speakers": speakers,
            "audio_duration": float(result.audio_duration),
            "segments": segments,
            "window_embeddings": window_embeddings,
            "embeddings": {
                str(i): seg["embedding"]
                for i, seg in enumerate(segments)
                if "embedding" in seg
            },
        }
        _emit("progress", percent=97, message="Aligning speaker boundaries with timeline...")
        _emit("finished", result=output)
        return 0
    except Exception as exc:
        _emit("error", message=f"{type(exc).__name__}: {exc}\n\n{traceback.format_exc()}")
        return 4
    finally:
        if temp_dir is not None:
            temp_dir.cleanup()
