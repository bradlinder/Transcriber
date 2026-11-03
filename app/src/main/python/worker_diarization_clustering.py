"""worker_diarization_clustering.py - Speaker Clustering, Centroid Matching & Math Helpers.

Provides:
- O(N^2) Agglomerative Hierarchical Clustering (AHC) on unit hypersphere
- Cosine distance sensitivity thresholding from environment / user settings
- Short-utterance (< 0.8s) centroid assignment with audio context padding
- Interval embedding aggregation and spherical normalization
- Global patches for SpectralClustering to prevent O(N^3) scaling stalls
- Speaker label normalization to playback order
"""

from __future__ import annotations

import os
from typing import Any, Dict, List, Optional, Tuple, Union

SHORT_FRAGMENT_MERGE_THRESHOLD = 0.7


def _normalize_expected_speakers(value: Any) -> str:
    """Map a CLI/UI expected-speakers hint to one of 'auto', '1', '2', '3+'."""
    text = str(value).strip().lower()
    if text in ("1", "solo"):
        return "1"
    if text == "2":
        return "2"
    if text in ("3+", "3", "panel", "group"):
        return "3+"
    return "auto"


def _normalize_speaker_labels(segments: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Relabel raw diarize-package speaker ids to 'Speaker 1', 'Speaker 2',
    etc, in order of first appearance so the numbering matches playback
    order rather than internal alphabetical ids.
    """
    order = []
    for seg in segments:
        raw = seg.get("speaker")
        if raw not in order:
            order.append(raw)
    label_map = {raw: f"Speaker {i + 1}" for i, raw in enumerate(order)}
    for seg in segments:
        seg["speaker"] = label_map.get(seg["speaker"], seg["speaker"])
    return segments


def _sensitivity_distance_threshold() -> float:
    """Cosine-distance clustering threshold derived from PRS_DIARIZATION_SENSITIVITY.
    Shared by fast_cluster_ahc() and diarize(), so the sensitivity setting means
    the same thing regardless of which code path runs the clustering.
    """
    sens_env = os.environ.get("PRS_DIARIZATION_SENSITIVITY", "normal").strip().lower()
    sens_map = {"low": 0.78, "normal": 0.68, "high": 0.58, "very_high": 0.48, "ultra": 0.38}
    return sens_map.get(sens_env, 0.68)


def fast_cluster_ahc(embeddings, k=None, distance_threshold=None):
    """Agglomerative Hierarchical Clustering with cosine metric and average linkage (O(N^2)).
    Supports distance threshold sensitivity control for AHC on unit hypersphere.
    """
    import numpy as np
    n = len(embeddings)
    if n == 0:
        return np.array([], dtype=int)

    norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    norm_emb = embeddings / norms

    from sklearn.cluster import AgglomerativeClustering

    if distance_threshold is None:
        distance_threshold = _sensitivity_distance_threshold()

    use_fixed_k = False
    if k is not None and not isinstance(k, str) and int(k) >= 1:
        use_fixed_k = True
        k_val = max(1, min(int(k), n))
    elif isinstance(k, str) and k.isdigit() and int(k) >= 1:
        use_fixed_k = True
        k_val = max(1, min(int(k), n))

    if use_fixed_k:
        if k_val == 1:
            return np.zeros(n, dtype=int)
        try:
            clusterer = AgglomerativeClustering(n_clusters=k_val, metric="cosine", linkage="average")
        except TypeError:
            clusterer = AgglomerativeClustering(n_clusters=k_val, affinity="cosine", linkage="average")
    else:
        try:
            clusterer = AgglomerativeClustering(
                n_clusters=None,
                distance_threshold=float(distance_threshold),
                metric="cosine",
                linkage="average",
            )
        except TypeError:
            clusterer = AgglomerativeClustering(
                n_clusters=None,
                distance_threshold=float(distance_threshold),
                affinity="cosine",
                linkage="average",
            )

    labels = clusterer.fit_predict(norm_emb)

    try:
        from diarize.clustering import _refine_labels_spherical
        labels = _refine_labels_spherical(embeddings, labels)
    except Exception:
        pass
    return labels


def apply_clustering_patches():
    """Globally monkey-patch SpectralClustering and diarize.clustering to use O(N^2) AHC."""
    try:
        import sklearn.cluster

        class PatchedSpectralClustering:
            def __init__(self, n_clusters=2, *args, **kwargs):
                self.n_clusters = n_clusters
                self.labels_ = None
                self.affinity = kwargs.get("affinity", "cosine")

            def fit(self, X, y=None):
                self.labels_ = fast_cluster_ahc(X, self.n_clusters)
                return self

            def fit_predict(self, X, y=None):
                self.fit(X, y)
                return self.labels_

        sklearn.cluster.SpectralClustering = PatchedSpectralClustering
    except Exception:
        pass

    try:
        import diarize.clustering
        diarize.clustering.cluster_spectral = fast_cluster_ahc
    except Exception:
        pass

    try:
        import diarize
        diarize.cluster_spectral = fast_cluster_ahc
    except Exception:
        pass


def assign_short_segments_to_centroids(
    short_segments,
    audio_path,
    centroids,
    label_values,
    raw_segments=None,
    min_confidence=0.45,
    get_wespeaker_model_fn=None,
    safe_tensor_fn=None,
):
    """Extract acoustic embeddings for short segments (< 0.8s) with symmetric context padding,
    and assign them to the nearest speaker centroid via cosine similarity.
    """
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio.compliance.kaldi as kaldi

    if len(centroids) == 0:
        return []

    if get_wespeaker_model_fn is not None:
        model = get_wespeaker_model_fn()
    else:
        import wespeakerruntime as wespeaker_rt
        model = wespeaker_rt.Speaker(lang="en")
    session = model.session

    def _to_tensor(arr):
        if safe_tensor_fn:
            return safe_tensor_fn(arr)
        if isinstance(arr, np.ndarray) and (not arr.flags.writeable or not arr.flags.c_contiguous):
            arr = np.ascontiguousarray(arr, dtype=np.float32).copy()
        try:
            return torch.from_numpy(arr)
        except Exception:
            return torch.as_tensor(arr, dtype=torch.float32)

    audio_data, sr = sf.read(str(audio_path), dtype="float32")
    if audio_data.ndim > 1:
        audio_data = audio_data.mean(axis=1)

    if sr != 16000:
        import torchaudio
        resampler = torchaudio.transforms.Resample(orig_freq=sr, new_freq=16000)
        base_tensor = _to_tensor(audio_data).unsqueeze(0)
        audio_tensor = resampler(base_tensor).squeeze(0)
        sr = 16000
    else:
        audio_tensor = _to_tensor(audio_data)

    total_samples = len(audio_tensor)
    assigned = []

    for seg in short_segments:
        start_sec = float(seg.start if hasattr(seg, "start") else seg["start"])
        end_sec = float(seg.end if hasattr(seg, "end") else seg["end"])
        duration = end_sec - start_sec

        target_dur = max(0.4, duration)
        pad_needed = min(0.05, max(0.0, target_dur - duration) / 2.0)
        pad_start = max(0.0, start_sec - pad_needed)
        pad_end = min(float(total_samples) / float(sr), end_sec + pad_needed)

        start_samp = int(pad_start * sr)
        end_samp = int(pad_end * sr)
        chunk = audio_tensor[start_samp:end_samp]

        emb = None
        if len(chunk) >= int(0.15 * sr):
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
                single_in = np.expand_dims(mat, 0).astype(np.float32)
                emb = session.run(output_names=["embs"], input_feed={"feats": single_in})[0][0]
            except Exception:
                emb = None

        chosen_speaker = None
        if emb is not None:
            norm = np.linalg.norm(emb)
            if norm > 0:
                norm_emb = emb / norm
                sims = norm_emb @ centroids.T
                best_idx = int(np.argmax(sims))
                if sims[best_idx] >= min_confidence or len(label_values) == 1:
                    chosen_speaker = f"SPEAKER_{label_values[best_idx]:02d}"

        if not chosen_speaker and raw_segments:
            seg_mid = (start_sec + end_sec) / 2.0
            best_dist = float("inf")
            for raw in raw_segments:
                raw_mid = (raw.start + raw.end) / 2.0
                dist = abs(seg_mid - raw_mid)
                if dist < best_dist:
                    best_dist = dist
                    chosen_speaker = raw.speaker

        if not chosen_speaker:
            chosen_speaker = f"SPEAKER_{label_values[0]:02d}" if len(label_values) > 0 else "SPEAKER_00"

        from diarize import _RawSegment
        seg_obj = _RawSegment(start=start_sec, end=end_sec, speaker=chosen_speaker)
        assigned.append(seg_obj)

    return assigned


def _calculate_embedding_for_interval(start, end, subsegments, embeddings):
    """Compute unit-normalized 256-dimensional embedding vector for an interval [start, end]."""
    if embeddings is None or len(embeddings) == 0 or not subsegments:
        return None
    try:
        import numpy as np
        sub_starts = np.array([float(s.start) for s in subsegments])
        sub_ends = np.array([float(s.end) for s in subsegments])
        s_st = float(start)
        s_en = float(end)
        overlap_mask = (sub_ends >= s_st - 0.05) & (sub_starts <= s_en + 0.05)
        if np.any(overlap_mask):
            matched = embeddings[overlap_mask]
            mean_emb = np.mean(matched, axis=0)
        else:
            sub_mids = (sub_starts + sub_ends) / 2.0
            seg_mid = (s_st + s_en) / 2.0
            closest_idx = int(np.argmin(np.abs(sub_mids - seg_mid)))
            mean_emb = embeddings[closest_idx]

        norm = np.linalg.norm(mean_emb)
        if norm > 0:
            mean_emb = mean_emb / norm
        return [round(float(x), 6) for x in mean_emb.tolist()]
    except Exception:
        return None
