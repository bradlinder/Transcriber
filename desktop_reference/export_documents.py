"""Radio & TV Story Segmenter — Document, Transcript & Timeline Interchange Exporter.

Formats and exports transcripts across multiple standard document and interchange formats:
- Plain text (.txt), Word (.docx), Vector PDF (.pdf)
- Subtitles (.srt, .vtt)
- DAW timelines: REAPER (.rpp), Samplitude EDL (.edl), Audition/FCP (.xml), FCPX (.fcpxml),
  AAF (.aaf), Hindenburg (.nhx), Audacity labels (.txt), CUE sheets (.cue), CSV markers (.csv)
- YouTube chapter markers & clipboard copy
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox

from core_utils import safe_filename, is_sentence_end
from export_dialogs import show_export_completion_dialog
from export.pdf import TranscriptPdfWriter
from export.docx import create_story_docx
from export.subtitles import (
    seconds_to_cue_time,
    generate_cue_sheet,
    generate_youtube_chapters,
    generate_srt_content,
    generate_vtt_content,
    generate_cue_content,
)
from export.timeline import (
    generate_reaper_project,
    generate_samplitude_edl,
    generate_fcp7_xml,
    generate_fcpxml,
    generate_aaf_interchange,
    generate_hindenburg_session,
    generate_audacity_labels,
    generate_audition_xml,
    generate_cue_sheet as generate_timeline_cue_sheet,
    generate_daw_marker_csv,
)

MIN_WORDS_PER_PARAGRAPH = 35


class ExportDocumentsMixin:
    """Provides document formatting, subtitle generation, and DAW interchange exports."""

    def _subtitle_timestamp(self, seconds: float, vtt: bool = False) -> str:
        seconds = max(0.0, float(seconds))
        h = int(seconds // 3600)
        m = int((seconds % 3600) // 60)
        s = seconds % 60
        ms = int(round((s - int(s)) * 1000))
        sec = int(s)
        if ms >= 1000:
            sec += 1
            ms = 0
        return f"{h:02d}:{m:02d}:{sec:02d}{'.' if vtt else ','}{ms:03d}"

    def write_subtitles(self, blocks: List[Dict[str, Any]], path: str, fmt: str = "srt", include_speakers: bool = True):
        lines = []
        if fmt == "vtt":
            lines.append("WEBVTT\n")
        for i, block in enumerate(blocks, 1):
            start = block.get("start", 0.0)
            end = block.get("end", start + 1.0)
            text = self.clean_export_text(block.get("text", ""), block.get("speaker", ""))
            if include_speakers and block.get("speaker"):
                text = f"{block['speaker']}: {text}"
            if not text:
                continue
            if fmt == "srt":
                lines.extend([str(i), f"{self._subtitle_timestamp(start)} --> {self._subtitle_timestamp(end)}", text, ""])
            else:
                lines.extend([f"{self._subtitle_timestamp(start, True)} --> {self._subtitle_timestamp(end, True)}", text, ""])
        Path(path).write_text("\n".join(lines), encoding="utf-8")

    def transcript_for_range(self, start: Optional[float], end: Optional[float]) -> List[Dict[str, Any]]:
        transcript = getattr(self, "transcript", None)
        if not transcript:
            return []

        start_time = float(start) if start is not None else 0.0
        end_time = float(end) if end is not None else float("inf")

        result = []
        for source_index, segment in enumerate(transcript.get("segments", [])):
            s_start = float(segment.get("start", 0.0))
            s_end = float(segment.get("end", s_start))
            if s_end <= start_time or s_start >= end_time:
                continue
            copied = dict(segment)
            copied["_source_index"] = source_index
            result.append(copied)

        return result

    def speaker_for_segment(self, segment: Any) -> str:
        if not segment:
            return ""
        start = segment.get("start", 0.0) if isinstance(segment, dict) else getattr(segment, "start", 0.0)
        end = segment.get("end", start) if isinstance(segment, dict) else getattr(segment, "end", start)
        if hasattr(self, "speaker_at_time"):
            return self.speaker_at_time(start, end)
        return segment.get("speaker", "") if isinstance(segment, dict) else getattr(segment, "speaker", "")

    def clean_export_text(self, text: Any, current_speaker: str = "") -> str:
        text = str(text or "").strip()
        if not text:
            return ""

        candidates = set()
        if current_speaker:
            candidates.add(str(current_speaker).strip())
        speaker_names = getattr(self, "speaker_names", {}) or {}
        for value in speaker_names.values():
            if value:
                candidates.add(str(value).strip())
        if getattr(self, "diarization", None):
            if hasattr(self, "_ensure_diar_speaker_index"):
                self._ensure_diar_speaker_index()
            diar_labels = getattr(self, "_diar_speaker_labels", []) or []
            candidates.update(diar_labels)

        candidates_list = sorted((c for c in candidates if c), key=len, reverse=True)
        if not candidates_list:
            return text

        # Strip speaker labels matched with any standard punctuation (: - —) or brackets
        changed = True
        while changed:
            changed = False
            for name in candidates_list:
                pattern = rf"^\s*(?:\[{re.escape(name)}\]|\({re.escape(name)}\)|{re.escape(name)})\s*[:\-\—]?\s*"
                new_text = re.sub(pattern, "", text, count=1, flags=re.IGNORECASE)
                if new_text != text:
                    text = new_text.strip()
                    changed = True
                    break
        return text

    def build_story_blocks(self, segments: List[Any]) -> List[Dict[str, Any]]:
        if not segments:
            return []

        word_tokens = []
        for seg_idx, segment in enumerate(segments):
            start = segment.get("start", 0.0) if isinstance(segment, dict) else getattr(segment, "start", 0.0)
            end = segment.get("end", start) if isinstance(segment, dict) else getattr(segment, "end", start)
            source_idx = segment.get("_source_index", seg_idx) if isinstance(segment, dict) else getattr(segment, "_source_index", seg_idx)
            spk_name = segment.get("speaker") if isinstance(segment, dict) else getattr(segment, "speaker", None)
            if spk_name is None and hasattr(self, "get_effective_speaker_name"):
                spk_name = self.get_effective_speaker_name(source_idx, segment)
            spk_name = (spk_name or "").strip()

            words = segment.get("words", []) if isinstance(segment, dict) else getattr(segment, "words", [])
            if words:
                for w in words:
                    w_text = w.get("word", "").strip()
                    if not w_text or bool(w.get("deleted", False)):
                        continue
                    word_tokens.append({
                        "word": w_text,
                        "start": w.get("start", start),
                        "end": w.get("end", end),
                        "seg_idx": source_idx,
                        "speaker_name": spk_name,
                    })
            else:
                seg_text = segment.get("text", "").strip() if isinstance(segment, dict) else getattr(segment, "text", "").strip()
                cleaned_seg_text = self.clean_export_text(seg_text, spk_name)
                for word in cleaned_seg_text.split():
                    word_tokens.append({
                        "word": word,
                        "start": start,
                        "end": end,
                        "seg_idx": source_idx,
                        "speaker_name": spk_name,
                    })

        if not word_tokens:
            return []

        blocks = []
        curr_para_words = []
        curr_speaker_name = None
        last_rendered_speaker_name = None

        def flush_para():
            nonlocal curr_para_words, curr_speaker_name, last_rendered_speaker_name
            if not curr_para_words:
                return
            p_text = " ".join(t["word"] for t in curr_para_words).strip()
            p_text = self.clean_export_text(p_text, curr_speaker_name)
            if p_text:
                is_change = (curr_speaker_name != last_rendered_speaker_name)
                para_source_indices = []
                for t in curr_para_words:
                    s_idx = t.get("seg_idx")
                    if s_idx is not None and s_idx not in para_source_indices:
                        para_source_indices.append(s_idx)

                # Extract any comments anchored to the source segments
                source_segs = self.transcript.get("segments", []) if (hasattr(self, "transcript") and self.transcript) else []
                para_comments = []
                for s_idx in para_source_indices:
                    if 0 <= s_idx < len(source_segs):
                        c = (source_segs[s_idx].get("comments") or source_segs[s_idx].get("notes", "")).strip()
                        if c and c not in para_comments:
                            para_comments.append(c)

                # Also inspect segments list passed directly to build_story_blocks
                if not para_comments and segments:
                    for s in segments:
                        if isinstance(s, dict):
                            s_idx = s.get("_source_index")
                            if s_idx in para_source_indices or s.get("seg_idx") in para_source_indices:
                                c = (s.get("comments") or s.get("notes", "")).strip()
                                if c and c not in para_comments:
                                    para_comments.append(c)

                block_dict = {
                    "speaker": curr_speaker_name or "",
                    "text": p_text,
                    "start": curr_para_words[0]["start"],
                    "end": curr_para_words[-1]["end"],
                    "is_speaker_change": is_change,
                    "_source_index": para_source_indices[0] if para_source_indices else None,
                    "source_indices": para_source_indices,
                }
                if para_comments:
                    block_dict["comments"] = "\n".join(para_comments)
                    block_dict["notes"] = block_dict["comments"]

                blocks.append(block_dict)
                last_rendered_speaker_name = curr_speaker_name
            curr_para_words = []

        for token in word_tokens:
            spk_name = token["speaker_name"]

            if curr_speaker_name is None:
                curr_speaker_name = spk_name

            # Inherit current speaker over short unassigned gaps
            if not spk_name and curr_speaker_name:
                spk_name = curr_speaker_name

            speaker_changed = (spk_name != curr_speaker_name)
            word_count_exceeded = (len(curr_para_words) >= MIN_WORDS_PER_PARAGRAPH)
            prev_word_ended_sentence = curr_para_words and is_sentence_end(curr_para_words[-1]["word"])

            if curr_para_words and (speaker_changed or (word_count_exceeded and prev_word_ended_sentence)):
                flush_para()
                curr_para_words = [token]
                curr_speaker_name = spk_name
            else:
                curr_para_words.append(token)

        if curr_para_words:
            flush_para()

        return blocks

    def story_text(self, segments: List[Any]) -> str:
        blocks = self.build_story_blocks(segments)
        output = []
        last_speaker = None
        for block in blocks:
            speaker = (block.get("speaker") or "").strip()
            paragraph = block.get("text", "").strip()
            if not paragraph:
                continue
            is_speaker_change = block.get("is_speaker_change", (speaker != last_speaker))
            if speaker and is_speaker_change and speaker != last_speaker:
                output.append(f"{speaker}: {paragraph}")
                last_speaker = speaker
            else:
                output.append(paragraph)

        return "\n\n".join(output)

    def add_story_to_docx(self, document: Any, segments: List[Any]):
        blocks = self.build_story_blocks(segments)
        last_speaker = None
        for block in blocks:
            speaker = (block.get("speaker") or "").strip()
            paragraph = block.get("text", "").strip()
            if not paragraph:
                continue
            doc_paragraph = document.add_paragraph()
            is_speaker_change = block.get("is_speaker_change", (speaker != last_speaker))
            if speaker and is_speaker_change and speaker != last_speaker:
                speaker_run = doc_paragraph.add_run(f"{speaker}: ")
                speaker_run.bold = True
                last_speaker = speaker
            doc_paragraph.add_run(paragraph)

    def _get_transcript_text_slice(self, start: float, end: float | None = None) -> str:
        segments = self.transcript_for_range(start, end)
        parts = []
        for s in segments:
            t = s.get("text", "").strip()
            if t:
                parts.append(t)
        return " ".join(parts)

    def export_cue_sheet(self, destination_path: Optional[str] = None) -> bool:
        """Export story/song segments to a standard .cue sheet."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        album_title = project_file.stem if project_file else (audio_file.stem if audio_file else "Album")
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export CUE Sheet",
                str(Path(dialog_dir) / f"{base}.cue"),
                "CUE Sheet (*.cue)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        media_name = audio_file.name if audio_file else ""
        content = generate_cue_sheet(stories, media_name, album_title)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported CUE sheet to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"CUE sheet exported successfully to:\n{save_path}", save_path)
        return True

    def export_tracklist(self, destination_path: Optional[str] = None) -> bool:
        """Export story/song segments as YouTube chapters / tracklist formatted text."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export Tracklist / Chapters",
                str(Path(dialog_dir) / f"{base}_chapters.txt"),
                "Text File (*.txt)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        content = generate_youtube_chapters(stories)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported tracklist / chapters to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"Tracklist / Chapters exported successfully to:\n{save_path}", save_path)
        return True

    def _get_unselected_audio_mode_pref(self) -> str:
        try:
            settings = QSettings("RadioTVStorySegmenter", "RadioTVStorySegmenter")
            return str(settings.value("export_opt_unselected_audio_mode", "exclude")).lower().strip()
        except Exception:
            return "exclude"

    def export_reaper_project(self, destination_path: Optional[str] = None, unselected_audio_mode: Optional[str] = None) -> bool:
        """Export story segments as a Cockos REAPER project file (.rpp)."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export REAPER Project",
                str(Path(dialog_dir) / f"{base}.rpp"),
                "Cockos REAPER Project (*.rpp)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        mode = unselected_audio_mode if unselected_audio_mode is not None else self._get_unselected_audio_mode_pref()
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        media_name = audio_file.name if audio_file else ""
        content = generate_reaper_project(stories, media_name, base, apply_fades=True, total_duration=tot_dur, unselected_audio_mode=mode)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported REAPER project (.rpp) to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"REAPER project exported successfully to:\n{save_path}", save_path)
        return True

    def export_samplitude_edl(self, destination_path: Optional[str] = None, unselected_audio_mode: Optional[str] = None) -> bool:
        """Export story segments as a Magix Samplitude EDL (v1.5) broadcast edit decision list."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export Samplitude EDL",
                str(Path(dialog_dir) / f"{base}.edl"),
                "Samplitude EDL (*.edl)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        mode = unselected_audio_mode if unselected_audio_mode is not None else self._get_unselected_audio_mode_pref()
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        media_name = audio_file.name if audio_file else ""
        content = generate_samplitude_edl(stories, media_name, base, apply_fades=True, total_duration=tot_dur, unselected_audio_mode=mode)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported Samplitude EDL (.edl) to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"Samplitude EDL exported successfully to:\n{save_path}", save_path)
        return True

    def export_audacity_labels(self, destination_path: Optional[str] = None, unselected_audio_mode: Optional[str] = None) -> bool:
        """Export story segments as an Audacity Label Track (.txt)."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export Audacity Labels",
                str(Path(dialog_dir) / f"{base}_labels.txt"),
                "Text File (*.txt)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        mode = unselected_audio_mode if unselected_audio_mode is not None else self._get_unselected_audio_mode_pref()
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        content = generate_audacity_labels(stories, total_duration=tot_dur, unselected_audio_mode=mode)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported Audacity label track to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"Audacity label track exported successfully to:\n{save_path}", save_path)
        return True

    def export_audition_xml(self, destination_path: Optional[str] = None, unselected_audio_mode: Optional[str] = None) -> bool:
        """Export story segments as Adobe Audition / Final Cut Pro XML (.xml)."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export Adobe Audition / FCP XML",
                str(Path(dialog_dir) / f"{base}.xml"),
                "XML Sequence (*.xml)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        mode = unselected_audio_mode if unselected_audio_mode is not None else self._get_unselected_audio_mode_pref()
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        media_name = audio_file.name if audio_file else ""
        content = generate_audition_xml(stories, media_name, base, total_duration=tot_dur, unselected_audio_mode=mode)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported Audition / FCP XML to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"Audition / FCP XML sequence exported successfully to:\n{save_path}", save_path)
        return True

    def export_daw_marker_csv(self, destination_path: Optional[str] = None, unselected_audio_mode: Optional[str] = None) -> bool:
        """Export story segments as a Universal DAW Marker List (.csv)."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"
        stories = getattr(self, "stories", [])
        if not stories:
            QMessageBox.warning(self, f"No {term_plural}", f"There are no {term_plural.lower()} to export.")
            return False
        project_file = getattr(self, "project_file", None)
        audio_file = getattr(self, "audio_file", None)
        base = safe_filename(project_file.stem if project_file else (audio_file.stem if audio_file else "project"))
        if not destination_path:
            dialog_dir = self._dialog_directory() if hasattr(self, "_dialog_directory") else str(Path.home())
            save_path, _ = QFileDialog.getSaveFileName(
                self, "Export DAW Marker List",
                str(Path(dialog_dir) / f"{base}_markers.csv"),
                "CSV Marker List (*.csv)"
            )
            if not save_path:
                return False
        else:
            save_path = destination_path

        mode = unselected_audio_mode if unselected_audio_mode is not None else self._get_unselected_audio_mode_pref()
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        content = generate_daw_marker_csv(stories, total_duration=tot_dur, unselected_audio_mode=mode)
        with open(save_path, "w", encoding="utf-8") as f:
            f.write(content)
        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported DAW marker list (.csv) to {save_path}")
        if not destination_path:
            show_export_completion_dialog(self, "Export Complete", f"DAW marker list (.csv) exported successfully to:\n{save_path}", save_path)
        return True

    def copy_youtube_chapters_to_clipboard(self) -> bool:
        """Format story segments as YouTube chapters and copy them directly to the system clipboard."""
        stories = getattr(self, "stories", [])
        if not stories:
            if hasattr(self, "statusBar") and self.statusBar():
                self.statusBar().showMessage("No stories available to copy YouTube chapters.", 3000)
            return False
        chapters_text = generate_youtube_chapters(stories)
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.setText(chapters_text)
            msg = f"Copied {len(stories)} YouTube chapter markers to clipboard!"
            if hasattr(self, "statusBar") and self.statusBar():
                self.statusBar().showMessage(msg, 4000)
            if hasattr(self, "log_activity"):
                self.log_activity(f"[EXPORT] {msg}")
            return True
        return False

    def export_timeline_interchange(
        self,
        formats: dict,
        base: str,
        options: dict,
        directory: str,
        scope: str = "full",
        is_custom_location: bool = False,
    ) -> bool:
        """Export multi-platform timeline and DAW interchange project files."""
        is_music = getattr(self, "story_detection_mode", "voice") == "music"
        term_plural = "Songs" if is_music else "Stories"

        all_stories = getattr(self, "stories", []) or []
        if scope == "selected_stories":
            sel_idx = getattr(self, "current_selected_story_indices", [])
            stories = [all_stories[i] for i in sel_idx if 0 <= i < len(all_stories)]
            if not stories:
                QMessageBox.warning(self, f"No {term_plural} Selected", f"Please select one or more {term_plural.lower()} to export.")
                return False
        else:
            stories = all_stories

        audio_file = getattr(self, "audio_file", None)
        if not stories and not audio_file:
            QMessageBox.warning(self, "Nothing to Export", f"There are no {term_plural.lower()} or audio media loaded to export.")
            return False

        out = Path(directory)
        settings_store = getattr(self, "settings_store", None) or QSettings("RadioTVStorySegmenter", "RadioTVStorySegmenter")
        create_bundle = str(settings_store.value("create_project_subfolders", "true")).lower() in {"1", "true", "yes"}
        if create_bundle and not is_custom_location:
            transcripts_out = out / "Transcripts"
            transcripts_out.mkdir(parents=True, exist_ok=True)
        else:
            transcripts_out = out

        media_name = audio_file.name if audio_file else "audio.wav"
        tot_dur = float(getattr(self, "duration", 0.0) or getattr(self, "audio_duration", 0.0) or 0.0)
        sample_rate = int(options.get("sample_rate", 48000))
        apply_fades = bool(options.get("apply_audio_fades", True))
        unselected_mode = options.get("unselected_audio_mode", "exclude")
        track_name = options.get("track_name", "Segmented Stories")

        exported_files: List[str] = []

        if formats.get("rpp"):
            rpp_file = transcripts_out / f"{base}.rpp"
            content = generate_reaper_project(
                stories,
                media_name,
                base,
                apply_fades=apply_fades,
                sample_rate=sample_rate,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                scope=scope,
                all_stories=all_stories,
            )
            with open(rpp_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(rpp_file))

        if formats.get("edl"):
            edl_file = transcripts_out / f"{base}.edl"
            content = generate_samplitude_edl(
                stories,
                media_name,
                base,
                sample_rate=sample_rate,
                apply_fades=apply_fades,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                scope=scope,
                all_stories=all_stories,
            )
            with open(edl_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(edl_file))

        if formats.get("fcp7_xml") or formats.get("audition_xml"):
            xml_file = transcripts_out / f"{base}.xml"
            content = generate_fcp7_xml(
                stories,
                media_name,
                base,
                sample_rate=sample_rate,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                scope=scope,
                all_stories=all_stories,
            )
            with open(xml_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(xml_file))

        if formats.get("fcpxml"):
            fcpxml_file = transcripts_out / f"{base}.fcpxml"
            content = generate_fcpxml(
                stories,
                media_name,
                base,
                sample_rate=sample_rate,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                scope=scope,
                all_stories=all_stories,
            )
            with open(fcpxml_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(fcpxml_file))

        if formats.get("aaf"):
            aaf_file = transcripts_out / f"{base}.aaf"
            content = generate_aaf_interchange(
                stories,
                media_name,
                base,
                sample_rate=sample_rate,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                scope=scope,
                all_stories=all_stories,
            )
            with open(aaf_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(aaf_file))

        if formats.get("hindenburg"):
            nhx_file = transcripts_out / f"{base}.nhx"
            content = generate_hindenburg_session(
                stories,
                media_name,
                base,
                sample_rate=sample_rate,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                track_name=track_name,
                apply_fades=apply_fades,
                scope=scope,
                all_stories=all_stories,
            )
            with open(nhx_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(nhx_file))

        if formats.get("audacity"):
            aud_file = transcripts_out / f"{base}_labels.txt"
            content = generate_audacity_labels(
                stories,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                scope=scope,
                all_stories=all_stories,
            )
            with open(aud_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(aud_file))

        if formats.get("cue"):
            cue_file = transcripts_out / f"{base}.cue"
            content = generate_timeline_cue_sheet(
                stories,
                media_filename=media_name,
                project_title=base,
                total_duration=tot_dur,
            )
            with open(cue_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(cue_file))

        if formats.get("daw_csv"):
            csv_file = transcripts_out / f"{base}_markers.csv"
            content = generate_daw_marker_csv(
                stories,
                total_duration=tot_dur,
                unselected_audio_mode=unselected_mode,
                scope=scope,
                all_stories=all_stories,
            )
            with open(csv_file, "w", encoding="utf-8") as f:
                f.write(content)
            exported_files.append(str(csv_file))

        if hasattr(self, "log_activity"):
            self.log_activity(f"[EXPORT] Exported {len(exported_files)} Timeline / DAW file(s) to {transcripts_out}")
        show_export_completion_dialog(
            self,
            "Timeline Export Complete",
            f"Exported {len(exported_files)} timeline file(s) to:\n{transcripts_out}",
            str(transcripts_out),
        )
        return True
