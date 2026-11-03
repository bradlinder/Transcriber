"""Radio & TV Story Segmenter — Speaker Label, Renaming & Segment Editing Mixin.

Provides:
- SpeakerEditingMixin: Methods for inserting speaker labels, splitting segments
  at exact audio timestamps, renaming speakers (single, subsequent, all),
  removing speaker labels, merging contiguous turns, active learning turn confirmation,
  competitive dialog refinement, and word-level alignment diffs.
"""

from __future__ import annotations
import difflib
import html
import re
from typing import Any, Dict, List, Optional, Tuple

try:
    from PySide6.QtCore import Qt, QSettings
    from PySide6.QtWidgets import (
        QComboBox,
        QDialog,
        QDialogButtonBox,
        QFormLayout,
        QGroupBox,
        QInputDialog,
        QLabel,
        QLineEdit,
        QMessageBox,
        QVBoxLayout,
    )
except ImportError:
    from qt_compat import _ensure_pyside6_fallback
    _ensure_pyside6_fallback()
    from PySide6.QtCore import Qt, QSettings
    from PySide6.QtWidgets import (
        QComboBox,
        QDialog,
        QDialogButtonBox,
        QFormLayout,
        QGroupBox,
        QInputDialog,
        QLabel,
        QLineEdit,
        QMessageBox,
        QVBoxLayout,
    )

from core_utils import INTERNAL_APP_ID, format_time, make_dialog_maximizable
from speaker_dialogs import ChangeSpeakerDialog, SpeakerManagerDialog
from speaker_dialog_refinement_mixin import SpeakerDialogRefinementMixin



class SpeakerEditingMixin(SpeakerDialogRefinementMixin):
    """Mixin for speaker labeling, splitting, renaming, and dialog turn refinement."""

    def handle_insert_speaker_request(self, seg_idx, split_time, speaker_name):
        if speaker_name == "__NEW__":
            self.add_speaker_label_at(seg_idx, split_time, name=None)
        else:
            self.add_speaker_label_at(seg_idx, split_time, name=speaker_name)

    def add_speaker_label_at(self, seg_idx, split_time, name=None):
        if not getattr(self, "transcript", None) or "segments" not in self.transcript:
            return False

        segments = self.transcript.get("segments", [])
        if not segments:
            return False

        if seg_idx is None or seg_idx < 0 or seg_idx >= len(segments):
            for i, seg in enumerate(segments):
                s_start = float(seg.get("start", 0.0))
                s_end = float(seg.get("end", s_start))
                if s_start <= split_time <= s_end:
                    seg_idx = i
                    break
            if seg_idx is None:
                seg_idx = max(0, min(len(segments) - 1, int(seg_idx or 0)))

        if name is None:
            known = self.get_all_known_speakers() if hasattr(self, "get_all_known_speakers") else []
            if known:
                name, accepted = QInputDialog.getItem(
                    self, "Add Speaker Label", "Speaker name for this label:", known, 0, True
                )
            else:
                name, accepted = QInputDialog.getText(self, "Add Speaker Label", "Speaker name for this label:")
            if not accepted:
                return False

        name = (name or "").strip()
        if not name:
            return False

        target_seg = segments[seg_idx]
        words = target_seg.get("words", [])

        is_at_segment_start = False
        if words:
            if split_time <= words[0].get("start", target_seg["start"]) + 0.05:
                is_at_segment_start = True
        else:
            if split_time <= float(target_seg.get("start", 0.0)) + 0.1:
                is_at_segment_start = True

        if is_at_segment_start:
            if hasattr(self, "flush_pending_transcript_undo"):
                self.flush_pending_transcript_undo()
            before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None

            current_name = self.get_effective_speaker_name(seg_idx, segments[seg_idx])
            section_indices = []
            for i in range(seg_idx, len(segments)):
                if self.get_effective_speaker_name(i, segments[i]) == current_name:
                    section_indices.append(i)
                else:
                    break

            if not section_indices:
                section_indices = [seg_idx]

            for idx in section_indices:
                override_key = f"SEG_{idx}_SPEAKER"
                self.speaker_names[override_key] = name
                self.segment_speaker_overrides[idx] = override_key
            self._diar_index_key = None

            if before_state is not None and hasattr(self, "_commit_project_state_change"):
                self._commit_project_state_change(before_state, f"Add Speaker Label ({name})")

            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(name)
            self.save_project()
            self.render_transcript()
            return True

        if not self.split_segment_at_time(seg_idx, split_time, new_speaker_name=name):
            if hasattr(self, "flush_pending_transcript_undo"):
                self.flush_pending_transcript_undo()
            before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None

            current_name = self.get_effective_speaker_name(seg_idx, segments[seg_idx])
            section_indices = []
            for i in range(seg_idx, len(segments)):
                if self.get_effective_speaker_name(i, segments[i]) == current_name:
                    section_indices.append(i)
                else:
                    break

            if not section_indices:
                section_indices = [seg_idx]

            for idx in section_indices:
                override_key = f"SEG_{idx}_SPEAKER"
                self.speaker_names[override_key] = name
                self.segment_speaker_overrides[idx] = override_key
            self._diar_index_key = None

            if before_state is not None and hasattr(self, "_commit_project_state_change"):
                self._commit_project_state_change(before_state, f"Add Speaker Label ({name})")

            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(name)
            self.save_project()
            self.render_transcript()
            return True

        if hasattr(self, "add_custom_speaker_to_glossary"):
            self.add_custom_speaker_to_glossary(name)
        return True

    def split_segment_at_time(self, seg_idx, split_time, new_speaker_name=None):
        if not getattr(self, "transcript", None) or "segments" not in self.transcript:
            return False

        segments = self.transcript.get("segments", [])
        if seg_idx < 0 or seg_idx >= len(segments):
            return False

        target_seg = segments[seg_idx]
        words = target_seg.get("words", [])

        if words:
            split_idx = -1
            for w_i, w in enumerate(words):
                if w.get("start", target_seg["start"]) >= split_time - 0.01:
                    split_idx = w_i
                    break

            if split_idx <= 0 or split_idx >= len(words):
                return False

            left_words = words[:split_idx]
            right_words = words[split_idx:]

            seg1 = dict(target_seg)
            seg1["end"] = left_words[-1].get("end", split_time)
            seg1["words"] = left_words
            seg1["text"] = " ".join(w.get("word", "") for w in left_words)

            seg2 = dict(target_seg)
            seg2["start"] = right_words[0].get("start", split_time)
            seg2["words"] = right_words
            seg2["text"] = " ".join(w.get("word", "") for w in right_words)
        else:
            seg_text = target_seg.get("text", "").split()
            if len(seg_text) < 2:
                return False

            seg_start = float(target_seg.get("start", split_time))
            seg_end = float(target_seg.get("end", split_time))
            duration = max(0.0, seg_end - seg_start)
            ratio = max(0.0, min(1.0, (float(split_time) - seg_start) / duration)) if duration > 0 else 0.5
            split_word = max(1, min(len(seg_text) - 1, round(len(seg_text) * ratio)))

            seg1 = dict(target_seg)
            seg1["end"] = float(split_time)
            seg1["text"] = " ".join(seg_text[:split_word])

            seg2 = dict(target_seg)
            seg2["start"] = float(split_time)
            seg2["text"] = " ".join(seg_text[split_word:])

        if hasattr(self, "flush_pending_transcript_undo"):
            self.flush_pending_transcript_undo()
        before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None

        orig_spk_name = self.get_effective_speaker_name(seg_idx, target_seg)
        segments[seg_idx] = seg1
        segments.insert(seg_idx + 1, seg2)

        new_overrides = {}
        for idx_k, spk in self.segment_speaker_overrides.items():
            k = int(idx_k)
            if k <= seg_idx:
                new_overrides[k] = spk
            else:
                new_overrides[k + 1] = spk
        self.segment_speaker_overrides = new_overrides

        if new_speaker_name:
            target_spk = str(new_speaker_name).strip()
            section_indices = [seg_idx + 1]
            for i in range(seg_idx + 2, len(segments)):
                if self.get_effective_speaker_name(i, segments[i]) == orig_spk_name:
                    section_indices.append(i)
                else:
                    break

            for idx in section_indices:
                override_key = f"SEG_{idx}_SPEAKER"
                self.speaker_names[override_key] = target_spk
                self.segment_speaker_overrides[idx] = override_key

        self._diar_index_key = None

        if before_state is not None and hasattr(self, "_commit_project_state_change"):
            self._commit_project_state_change(
                before_state,
                f"Add Speaker Label{' (' + str(new_speaker_name) + ')' if new_speaker_name else ''}"
            )

        segments[seg_idx].pop("embedding", None)
        segments[seg_idx + 1].pop("embedding", None)

        left_speaker = self.get_effective_speaker_name(seg_idx, segments[seg_idx])
        right_speaker = self.get_effective_speaker_name(seg_idx + 1, segments[seg_idx + 1])

        self.register_confirmed_speaker_turn(seg_idx, left_speaker)
        self.register_confirmed_speaker_turn(seg_idx + 1, right_speaker)
        
        self.save_project()
        self.render_transcript()
        return True

    def prompt_rename_custom_speaker(self, old_name):
        if hasattr(self, "flush_pending_transcript_undo"):
            self.flush_pending_transcript_undo()
        before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None
        new_name, accepted = QInputDialog.getText(
            self,
            "Rename Speaker",
            f"Enter new name for {old_name}:",
            QLineEdit.EchoMode.Normal,
            old_name,
        )
        if accepted and new_name.strip():
            self.speaker_names[f"CUSTOM_{old_name}"] = new_name.strip()
            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(new_name.strip())
            if before_state is not None and hasattr(self, "_commit_project_state_change"):
                self._commit_project_state_change(before_state, f"Rename Speaker: {old_name} → {new_name.strip()}")
            self.render_transcript()
            self.save_project()

    def display_speaker(self, speaker):
        if speaker is None:
            return ""

        speaker = str(speaker)
        custom_name = self.speaker_names.get(speaker)
        if custom_name:
            return custom_name

        match = re.search(r"(\d+)$", speaker)
        if match:
            number = int(match.group(1)) + 1
            return f"Speaker {number}"

        return speaker

    def get_all_known_speakers(self):
        speakers = set()
        if hasattr(self, "speaker_names") and self.speaker_names:
            for k, v in self.speaker_names.items():
                if v and isinstance(v, str) and v.strip() and not k.startswith("SEG_"):
                    speakers.add(v.strip())
        if getattr(self, "transcript", None) and isinstance(self.transcript, dict) and "segments" in self.transcript:
            for idx, seg in enumerate(self.transcript["segments"]):
                name = self.get_effective_speaker_name(idx, seg)
                if name and name.strip():
                    speakers.add(name.strip())
        diar_data = getattr(self, "diarization_result", None) or getattr(self, "diarization", None)
        if isinstance(diar_data, dict) and "segments" in diar_data:
            for seg in diar_data["segments"]:
                spk = seg.get("speaker")
                if spk:
                    disp = self.display_speaker(spk)
                    if disp and disp.strip():
                        speakers.add(disp.strip())
        if hasattr(self, "custom_speakers") and self.custom_speakers:
            for spk in self.custom_speakers:
                if spk and isinstance(spk, str) and spk.strip():
                    speakers.add(spk.strip())

        def natural_sort_key(s):
            is_speaker_num = s.startswith("Speaker ") and s[8:].isdigit()
            if is_speaker_num:
                return (0, int(s[8:]), s.lower())
            return (1, 0, [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)])

        return sorted(speakers, key=natural_sort_key)

    def execute_speaker_rename(self, seg_idx, raw_speaker, target_name):
        if not getattr(self, "transcript", None) or "segments" not in self.transcript:
            return
        segments = self.transcript.get("segments", [])
        if seg_idx < 0 or seg_idx >= len(segments):
            return

        v_scroll_before = self.transcript_view.verticalScrollBar().value() if hasattr(self, "transcript_view") and self.transcript_view else 0
        h_scroll_before = self.transcript_view.horizontalScrollBar().value() if hasattr(self, "transcript_view") and self.transcript_view else 0

        current_name = (
            self.get_effective_speaker_name(seg_idx, segments[seg_idx])
            if seg_idx < len(segments)
            else self.display_speaker(raw_speaker)
        )

        if target_name == "__NEW__":
            new_name, accepted = QInputDialog.getText(
                self,
                "New Speaker Name",
                f"Enter new name for '{current_name}':",
                QLineEdit.EchoMode.Normal,
                "",
            )
            if not accepted or not new_name.strip():
                if hasattr(self, "transcript_view") and self.transcript_view:
                    self.transcript_view.lock_scroll_position(v_scroll_before, h_scroll_before, duration_ms=200)
                return
            target_name = new_name.strip()
        else:
            target_name = str(target_name).strip()

        if not target_name or target_name == current_name:
            if hasattr(self, "transcript_view") and self.transcript_view:
                self.transcript_view.lock_scroll_position(v_scroll_before, h_scroll_before, duration_ms=200)
            return

        spk_dlg = ChangeSpeakerDialog(current_name, target_name, seg_idx=seg_idx, parent=self)
        spk_dlg.exec()
        if spk_dlg.choice not in ("all", "subsequent", "single"):
            if hasattr(self, "transcript_view") and self.transcript_view:
                self.transcript_view.lock_scroll_position(v_scroll_before, h_scroll_before, duration_ms=200)
            return

        if hasattr(self, "flush_pending_transcript_undo"):
            self.flush_pending_transcript_undo()
        before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None

        if spk_dlg.choice == "all":
            if raw_speaker:
                self.speaker_names[str(raw_speaker)] = target_name
            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(target_name)
            for idx, seg in enumerate(segments):
                if self.get_effective_speaker_name(idx, seg) == current_name:
                    override_key = f"SEG_{idx}_SPEAKER"
                    self.speaker_names[override_key] = target_name
                    self.segment_speaker_overrides[idx] = override_key
        elif spk_dlg.choice == "subsequent":
            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(target_name)
            start_seg_idx = seg_idx if seg_idx >= 0 else 0
            for idx in range(start_seg_idx, len(segments)):
                if self.get_effective_speaker_name(idx, segments[idx]) == current_name:
                    override_key = f"SEG_{idx}_SPEAKER"
                    self.speaker_names[override_key] = target_name
                    self.segment_speaker_overrides[idx] = override_key

            if getattr(self, "diarization", None) and isinstance(self.diarization, dict):
                sec_start = float(segments[start_seg_idx].get("start", 0.0))
                if "segments" in self.diarization:
                    for d_seg in self.diarization["segments"]:
                        d_start = float(d_seg.get("start", 0.0))
                        if d_start >= sec_start:
                            disp = self.display_speaker(str(d_seg.get("speaker", "")))
                            if disp == current_name or d_seg.get("speaker") == current_name:
                                d_seg["speaker"] = target_name
                    self._diar_index_key = None
        elif spk_dlg.choice == "single":
            section_indices = []
            for i in range(seg_idx, len(segments)):
                if self.get_effective_speaker_name(i, segments[i]) == current_name:
                    section_indices.append(i)
                else:
                    break

            if not section_indices:
                section_indices = [seg_idx]

            if hasattr(self, "add_custom_speaker_to_glossary"):
                self.add_custom_speaker_to_glossary(target_name)
            for idx in section_indices:
                override_key = f"SEG_{idx}_SPEAKER"
                self.speaker_names[override_key] = target_name
                self.segment_speaker_overrides[idx] = override_key

            if getattr(self, "diarization", None) and isinstance(self.diarization, dict):
                sec_start = float(segments[section_indices[0]].get("start", 0.0))
                sec_end = float(segments[section_indices[-1]].get("end", sec_start))
                if "segments" in self.diarization:
                    for d_seg in self.diarization["segments"]:
                        d_start = float(d_seg.get("start", 0.0))
                        d_end = float(d_seg.get("end", 0.0))
                        if d_start < sec_end and d_end > sec_start:
                            disp = self.display_speaker(str(d_seg.get("speaker", "")))
                            if disp == current_name or d_seg.get("speaker") == current_name:
                                d_seg["speaker"] = target_name
                    self._diar_index_key = None

        if before_state is not None and hasattr(self, "_commit_project_state_change"):
            self._commit_project_state_change(before_state, f"Change Speaker: {current_name} → {target_name}")

        if hasattr(self, "transcript_view") and self.transcript_view:
            self.transcript_view.lock_scroll_position(v_scroll_before, h_scroll_before, duration_ms=400)

        self.render_transcript()
        self.save_project()
        if hasattr(self, "statusBar") and self.statusBar():
            self.statusBar().showMessage(f"Updated speaker to: {target_name}")

    def prompt_rename_speaker(self, seg_idx, speaker):
        self.execute_speaker_rename(seg_idx, speaker, "__NEW__")

    def remove_speaker_label_at_segment(self, seg_idx):
        if not getattr(self, "transcript", None) or "segments" not in self.transcript:
            return False

        segments = self.transcript.get("segments", [])
        if seg_idx <= 0 or seg_idx >= len(segments):
            return False

        if hasattr(self, "flush_pending_transcript_undo"):
            self.flush_pending_transcript_undo()
        before_state = self._capture_project_state() if hasattr(self, "_capture_project_state") else None

        prev_seg_idx = seg_idx - 1
        target_name = self.get_effective_speaker_name(prev_seg_idx, segments[prev_seg_idx])
        target_raw = (
            self.segment_speaker_overrides.get(prev_seg_idx) 
            or self.speaker_for_segment(segments[prev_seg_idx])
        )

        removed_name = self.get_effective_speaker_name(seg_idx, segments[seg_idx])
        section_indices = []
        for i in range(seg_idx, len(segments)):
            if self.get_effective_speaker_name(i, segments[i]) == removed_name:
                section_indices.append(i)
            else:
                break

        if not section_indices:
            return False

        section_indices_set = set(section_indices)

        for idx in section_indices:
            instance_key = f"SEG_{idx}_SPEAKER"
            self.speaker_names[instance_key] = target_name
            self.segment_speaker_overrides[idx] = instance_key

        if getattr(self, "diarization", None) and isinstance(self.diarization, dict):
            sec_start = float(segments[section_indices[0]].get("start", 0.0))
            sec_end = float(segments[section_indices[-1]].get("end", sec_start))
            diar_segs = self.diarization.get("segments", [])
            updated_diar = False
            new_diar_speaker = target_raw or f"SEG_{prev_seg_idx}_SPEAKER"

            for d_seg in diar_segs:
                d_start = float(d_seg.get("start", 0.0))
                d_end = float(d_seg.get("end", d_start))
                overlap = min(sec_end, d_end) - max(sec_start, d_start)
                if overlap <= 0.001:
                    continue

                best_seg_idx = None
                best_seg_overlap = 0.0

                for s_idx in section_indices:
                    t_seg = segments[s_idx]
                    t_start = float(t_seg.get("start", 0.0))
                    t_end = float(t_seg.get("end", t_start))
                    cur_overlap = min(t_end, d_end) - max(t_start, d_start)
                    if cur_overlap > best_seg_overlap:
                        best_seg_overlap = cur_overlap
                        best_seg_idx = s_idx

                if best_seg_idx in section_indices_set:
                    d_seg["speaker"] = new_diar_speaker
                    updated_diar = True

            if updated_diar:
                self._diar_index_key = None

        if before_state is not None and hasattr(self, "_commit_project_state_change"):
            self._commit_project_state_change(
                before_state,
                f"Remove Speaker Label: {removed_name} → {target_name} (turn at #{seg_idx + 1})"
            )

        self.save_project()
        self.render_transcript()
        if hasattr(self, "statusBar") and self.statusBar():
            self.statusBar().showMessage(f"Removed '{removed_name}' label at {format_time(segments[seg_idx].get('start', 0))}.")
        return True

    def register_confirmed_speaker_turn(self, seg_idx: int, speaker_name: str):
        if not speaker_name or not getattr(self, "transcript", None) or "segments" not in self.transcript:
            return

        segments = self.transcript.get("segments", [])
        if not (0 <= seg_idx < len(segments)):
            return

        name = speaker_name.strip()
        emb = self.get_segment_embedding(seg_idx) if hasattr(self, "get_segment_embedding") else None
        if not emb:
            return

        if not hasattr(self, "_session_speaker_profiles"):
            self._session_speaker_profiles = {}

        if name not in self._session_speaker_profiles:
            self._session_speaker_profiles[name] = []

        self._session_speaker_profiles[name].append(emb)
        if len(self._session_speaker_profiles[name]) > 6:
            self._session_speaker_profiles[name].pop(0)

        if hasattr(self, "log_activity"):
            self.log_activity(
                f"[VOICE MODEL] Updated acoustic signature for '{name}' "
                f"from confirmed turn #{seg_idx + 1} ({len(self._session_speaker_profiles[name])} sample(s)).",
                mark_dirty=False,
            )

    def register_confirmed_speaker_sample(self, seg_idx: int, speaker_name: str):
        self.register_confirmed_speaker_turn(seg_idx, speaker_name)
