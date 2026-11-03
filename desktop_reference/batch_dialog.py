"""Radio & TV Story Segmenter — Batch Processing Dialog & Droppable File List.

Extracted from prs_shared.py as part of Phase 2 Modularization.

Provides:
- BatchFileListWidget: Drag-and-drop QListWidget supporting direct media/document drops.
- BatchProcessingDialog: Multi-file batch transcription, diarization, and story segmentation dialog.
"""

from __future__ import annotations

import os
import sys
from urllib.parse import unquote
from pathlib import Path
from typing import Optional, List, Dict, Any

from PySide6.QtCore import Qt, Signal, QSettings, QUrl, QEvent
from PySide6.QtGui import QPainter, QColor, QPen
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QDoubleSpinBox,
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QVBoxLayout,
    QWidget,
)

from core_utils import make_dialog_maximizable, extract_dropped_file_paths
from theme_tokens import ThemeTokens


class BatchFileListWidget(QListWidget):
    filesDropped = Signal(list)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAcceptDrops(True)
        if self.viewport():
            self.viewport().setAcceptDrops(True)
        self.setDragDropMode(QAbstractItemView.DragDropMode.DropOnly)
        self.setDefaultDropAction(Qt.DropAction.CopyAction)
        self._is_drag_active = False

    def dragEnterEvent(self, event):
        paths = extract_dropped_file_paths(event.mimeData())
        if paths or event.mimeData().hasUrls() or event.mimeData().hasText():
            self._is_drag_active = True
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
            self.viewport().update()
        else:
            event.ignore()

    def dragMoveEvent(self, event):
        paths = extract_dropped_file_paths(event.mimeData())
        if paths or event.mimeData().hasUrls() or event.mimeData().hasText():
            self._is_drag_active = True
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
        else:
            event.ignore()

    def dragLeaveEvent(self, event):
        self._is_drag_active = False
        self.viewport().update()
        event.accept()

    def dropEvent(self, event):
        self._is_drag_active = False
        paths = extract_dropped_file_paths(event.mimeData())
        if paths:
            self.filesDropped.emit(paths)
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
        else:
            event.ignore()
        self.viewport().update()

    def paintEvent(self, event):
        super().paintEvent(event)
        tokens = ThemeTokens()
        accent_color = QColor(getattr(tokens, "accent_primary", "#38bdf8"))
        
        # Display an empty-state drag & drop guide when list is empty
        if self.count() == 0:
            painter = QPainter(self.viewport())
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            rect = self.viewport().rect().adjusted(16, 16, -16, -16)

            if self._is_drag_active:
                pen = QPen(accent_color)
                pen.setStyle(Qt.PenStyle.DashLine)
                pen.setWidth(2)
                painter.setPen(pen)
                painter.setBrush(QColor(56, 189, 248, 25))
                painter.drawRoundedRect(rect, 8, 8)

                painter.setPen(QColor(getattr(tokens, "text_primary", "#ffffff")))
                font = painter.font()
                font.setPointSize(11)
                font.setBold(True)
                painter.setFont(font)
                wrap_flags = int(Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap)
                painter.drawText(rect, wrap_flags, "Drop files or folders here to add to batch queue")
            else:
                pen = QPen(QColor(getattr(tokens, "border_subtle", "#282c35")))
                pen.setStyle(Qt.PenStyle.DashLine)
                pen.setWidth(1)
                painter.setPen(pen)
                painter.setBrush(Qt.BrushStyle.NoBrush)
                painter.drawRoundedRect(rect, 8, 8)

                painter.setPen(QColor(getattr(tokens, "text_muted", "#888888")))
                font = painter.font()
                font.setPointSize(10)
                painter.setFont(font)
                wrap_flags = int(Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap)
                painter.drawText(
                    rect,
                    wrap_flags,
                    "Drag & drop audio/video files or folders here\nor click 'Add Files…' below"
                )
            painter.end()
        elif self._is_drag_active:
            # Active drag overlay border when items already exist in the list
            painter = QPainter(self.viewport())
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            rect = self.viewport().rect().adjusted(4, 4, -4, -4)
            pen = QPen(accent_color)
            pen.setStyle(Qt.PenStyle.DashLine)
            pen.setWidth(2)
            painter.setPen(pen)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawRoundedRect(rect, 6, 6)
            painter.end()

class BatchProcessingDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Batch Processing")
        self.resize(700, 670)
        make_dialog_maximizable(self)
        self.setAcceptDrops(True)
        
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Add media files or documents. Drag and drop files or folders directly into the list below."))

        # File List View
        self.files = BatchFileListWidget()
        self.files.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        layout.addWidget(self.files, 1)

        row = QHBoxLayout()
        add = QPushButton("Add Files…")
        rem = QPushButton("Remove Selected")
        row.addWidget(add)
        row.addWidget(rem)
        row.addStretch()
        layout.addLayout(row)

        # Output / Project Directory Selector
        dir_group = QGroupBox("Target Save Location")
        dir_vbox = QVBoxLayout(dir_group)
        dir_layout = QHBoxLayout()
        self.output = QLineEdit()
        self.output.setPlaceholderText("Default (project folder or media file directory if empty)")
        browse = QPushButton("Browse Folder…")
        dir_layout.addWidget(self.output, 1)
        dir_layout.addWidget(browse)
        dir_vbox.addLayout(dir_layout)

        # Dedicated Transcripts Directory option (Option 1)
        self.unified_transcripts_check = QCheckBox("Save all transcripts to a single directory")
        self.unified_transcripts_check.setToolTip(
            "Consolidate transcripts, documents, and subtitle files for all batch items into a single folder\n"
            "instead of separate per-project subdirectories. Project sessions (.rtvs) will be saved in Projects/."
        )
        dir_vbox.addWidget(self.unified_transcripts_check)

        self.transcripts_dir_widget = QWidget()
        transcripts_dir_layout = QHBoxLayout(self.transcripts_dir_widget)
        transcripts_dir_layout.setContentsMargins(20, 0, 0, 0)
        self.transcripts_output = QLineEdit()
        self.transcripts_output.setPlaceholderText("Default (<Target Save Location>/Transcripts)")
        browse_transcripts = QPushButton("Browse Transcripts Folder…")
        transcripts_dir_layout.addWidget(self.transcripts_output, 1)
        transcripts_dir_layout.addWidget(browse_transcripts)
        dir_vbox.addWidget(self.transcripts_dir_widget)
        self.transcripts_dir_widget.setEnabled(False)

        layout.addWidget(dir_group)

        # --- Section 1: Pipeline & Process Selection ---
        proc_group = QGroupBox("1. Processing Pipeline Selection")
        proc_layout = QVBoxLayout(proc_group)

        self.pipeline_full_radio = QRadioButton("Run Full Processing Pipeline (Transcribe + Diarize + Detect Stories)")
        self.pipeline_full_radio.setChecked(True)
        self.pipeline_custom_radio = QRadioButton("Select Specific Processes to Run:")
        
        proc_layout.addWidget(self.pipeline_full_radio)
        proc_layout.addWidget(self.pipeline_custom_radio)

        # Custom process checkboxes
        self.custom_proc_widget = QWidget()
        custom_proc_layout = QVBoxLayout(self.custom_proc_widget)
        custom_proc_layout.setContentsMargins(20, 0, 0, 0)

        self.proc_transcribe = QCheckBox("Transcription")
        self.proc_transcribe.setChecked(True)
        custom_proc_layout.addWidget(self.proc_transcribe)

        # -- Speaker Detection (formerly "Diarize Speakers") --
        speaker_row = QHBoxLayout()
        self.proc_diarize = QCheckBox("Speaker Detection")
        self.proc_diarize.setChecked(True)
        speaker_row.addWidget(self.proc_diarize)
        speaker_row.addWidget(QLabel("Expected Speakers:"))
        self.batch_expected_speakers_combo = QComboBox()
        self.batch_expected_speakers_combo.addItem("Auto-Detect", "auto")
        self.batch_expected_speakers_combo.addItem("1 Speaker (Solo Fast-Path)", "1")
        self.batch_expected_speakers_combo.addItem("2 Speakers (Interview)", "2")
        self.batch_expected_speakers_combo.addItem("3+ Speakers (Panel / Group)", "3+")
        default_expected = str(getattr(parent, "expected_speakers", "auto") or "auto")
        default_idx = self.batch_expected_speakers_combo.findData(default_expected)
        self.batch_expected_speakers_combo.setCurrentIndex(default_idx if default_idx >= 0 else 0)
        speaker_row.addWidget(self.batch_expected_speakers_combo, 1)
        custom_proc_layout.addLayout(speaker_row)
        self.proc_diarize.toggled.connect(self.batch_expected_speakers_combo.setEnabled)
        self.batch_expected_speakers_combo.setEnabled(self.proc_diarize.isChecked())

        # -- Story Detection --
        stories_row = QHBoxLayout()
        self.proc_stories = QCheckBox("Story Detection")
        self.proc_stories.setChecked(True)
        stories_row.addWidget(self.proc_stories)
        stories_row.addWidget(QLabel("Silence Gap:"))
        self.batch_gap_spin = QDoubleSpinBox()
        self.batch_gap_spin.setRange(0.5, 30.0)
        self.batch_gap_spin.setSingleStep(0.5)
        self.batch_gap_spin.setSuffix(" sec")
        self.batch_gap_spin.setValue(3.0)
        stories_row.addWidget(self.batch_gap_spin)
        stories_row.addWidget(QLabel("Lead-in:"))
        self.batch_pad_spin = QDoubleSpinBox()
        self.batch_pad_spin.setRange(0.0, 5.0)
        self.batch_pad_spin.setSingleStep(0.1)
        self.batch_pad_spin.setSuffix(" sec")
        self.batch_pad_spin.setValue(0.2)
        stories_row.addWidget(self.batch_pad_spin)
        custom_proc_layout.addLayout(stories_row)
        self.proc_stories.toggled.connect(self.batch_gap_spin.setEnabled)
        self.proc_stories.toggled.connect(self.batch_pad_spin.setEnabled)
        self.batch_gap_spin.setEnabled(self.proc_stories.isChecked())
        self.batch_pad_spin.setEnabled(self.proc_stories.isChecked())

        # -- Translation (formerly "Spanish Translation") --
        translate_row = QHBoxLayout()
        self.proc_translate = QCheckBox("Translation")
        self.proc_translate.setChecked(False)
        translate_row.addWidget(self.proc_translate)
        translate_row.addWidget(QLabel("Direction:"))
        self.batch_translate_direction_combo = QComboBox()
        self.batch_translate_direction_combo.addItem("Auto-Detect (Flip EN \u2194 ES)", "auto")
        self.batch_translate_direction_combo.addItem("English to Spanish", "en-es")
        self.batch_translate_direction_combo.addItem("Spanish to English", "es-en")
        translate_row.addWidget(self.batch_translate_direction_combo, 1)
        custom_proc_layout.addLayout(translate_row)
        self.proc_translate.toggled.connect(self.batch_translate_direction_combo.setEnabled)
        self.batch_translate_direction_combo.setEnabled(self.proc_translate.isChecked())
        proc_layout.addWidget(self.custom_proc_widget)
        self.custom_proc_widget.setEnabled(False)

        self.pipeline_custom_radio.toggled.connect(self.custom_proc_widget.setEnabled)
        layout.addWidget(proc_group)

        # --- Section 2: Project & Save Options ---
        opts_group = QGroupBox("2. Project & Save Options")
        opts_layout = QVBoxLayout(opts_group)

        self.save_project_check = QCheckBox("Auto-save updated project (.rtvs) files to specified directory (or default)")
        self.save_project_check.setChecked(True)
        opts_layout.addWidget(self.save_project_check)

        self.skip_existing_check = QCheckBox("Skip re-processing if requested output files already exist")
        self.skip_existing_check.setChecked(True)
        opts_layout.addWidget(self.skip_existing_check)

        layout.addWidget(opts_group)

        # --- Section 3: Output Formats & Scope (for standard exports) ---
        self.export_group = QGroupBox("3. Output Formats & Scope")
        export_layout = QVBoxLayout(self.export_group)

        self.save_project_only_check = QCheckBox("Save projects only (do not export text or media files)")
        self.save_project_only_check.setChecked(False)
        self.save_project_only_check.setToolTip("Run the selected processing and save the resulting project files without creating transcript, subtitle, document, or media exports.")
        export_layout.addWidget(self.save_project_only_check)

        scope_row = QHBoxLayout()
        scope_row.addWidget(QLabel("Export Scope:"))
        self.scope_combo = QComboBox()
        self.scope_combo.addItem("Full Transcripts Only", "full")
        self.scope_combo.addItem("Individual Stories Only", "stories")
        self.scope_combo.addItem("Full Transcripts + Individual Stories", "both")
        scope_row.addWidget(self.scope_combo, 1)
        export_layout.addLayout(scope_row)

        fmt_row = QHBoxLayout()
        self.fmt_txt = QCheckBox("TXT (.txt)")
        self.fmt_docx = QCheckBox("DOCX (.docx)")
        self.fmt_srt = QCheckBox("SRT (.srt)")
        self.fmt_vtt = QCheckBox("VTT (.vtt)")
        self.fmt_txt.setChecked(True)
        self.fmt_docx.setChecked(True)
        fmt_row.addWidget(self.fmt_txt)
        fmt_row.addWidget(self.fmt_docx)
        fmt_row.addWidget(self.fmt_srt)
        fmt_row.addWidget(self.fmt_vtt)
        export_layout.addLayout(fmt_row)

        details_row = QHBoxLayout()
        self.include_speakers = QCheckBox("Include speaker labels")
        self.include_speakers.setChecked(True)
        self.include_times = QCheckBox("Include timestamps")
        self.include_times.setChecked(False)
        self.translate_check = QCheckBox("Translate")
        details_row.addWidget(self.include_speakers)
        details_row.addWidget(self.include_times)
        details_row.addWidget(self.translate_check)
        export_layout.addLayout(details_row)

        self.batch_export_translate_direction_combo = QComboBox()
        self.batch_export_translate_direction_combo.addItem("Auto-Detect", "auto")
        self.batch_export_translate_direction_combo.addItem("English → Spanish", "en-es")
        self.batch_export_translate_direction_combo.addItem("Spanish → English", "es-en")
        export_translate_row = QHBoxLayout()
        export_translate_row.addWidget(QLabel("Translation Direction:"))
        export_translate_row.addWidget(self.batch_export_translate_direction_combo, 1)
        export_layout.addLayout(export_translate_row)

        layout.addWidget(self.export_group)

        def _on_save_project_only_toggled(checked):
            # Keep the group accessible but disable the file format & scope pickers
            self.scope_combo.setEnabled(not checked)
            self.fmt_txt.setEnabled(not checked)
            self.fmt_docx.setEnabled(not checked)
            self.fmt_srt.setEnabled(not checked)
            self.fmt_vtt.setEnabled(not checked)
            self.include_speakers.setEnabled(not checked)
            self.include_times.setEnabled(not checked)
            self.translate_check.setEnabled(not checked)
            self.batch_export_translate_direction_combo.setEnabled((not checked) and self.translate_check.isChecked())
            self.unified_transcripts_check.setEnabled(not checked)
            self.transcripts_dir_widget.setEnabled((not checked) and self.unified_transcripts_check.isChecked())

        self.save_project_only_check.toggled.connect(_on_save_project_only_toggled)
        self.unified_transcripts_check.toggled.connect(
            lambda checked: self.transcripts_dir_widget.setEnabled(checked and not self.save_project_only_check.isChecked())
        )
        self.translate_check.toggled.connect(self.batch_export_translate_direction_combo.setEnabled)
        self.batch_export_translate_direction_combo.setEnabled(self.translate_check.isChecked())
        self.batch_translate_direction_combo.currentIndexChanged.connect(
            lambda idx: self.batch_export_translate_direction_combo.setCurrentIndex(idx)
            if 0 <= idx < self.batch_export_translate_direction_combo.count() else None
        )
        self.batch_export_translate_direction_combo.currentIndexChanged.connect(
            lambda idx: self.batch_translate_direction_combo.setCurrentIndex(idx)
            if 0 <= idx < self.batch_translate_direction_combo.count() else None
        )

        # Dialog Action Buttons and Default Management
        btns = QHBoxLayout()
        self.save_defaults_btn = QPushButton("Save Options as Default")
        self.reset_defaults_btn = QPushButton("Reset to Defaults")
        self.start = QPushButton("Start Batch")
        self.start.setDefault(True)
        cancel = QPushButton("Cancel")
        btns.addWidget(self.save_defaults_btn)
        btns.addWidget(self.reset_defaults_btn)
        btns.addStretch()
        btns.addWidget(self.start)
        btns.addWidget(cancel)
        layout.addLayout(btns)

        # Load remembered preferences or defaults
        self._load_saved_options()

        # Signal Connections
        add.clicked.connect(self.add_files)
        rem.clicked.connect(lambda: [self.files.takeItem(self.files.row(i)) for i in self.files.selectedItems()])
        browse.clicked.connect(self.choose_output)
        browse_transcripts.clicked.connect(self.choose_transcripts_output)
        self.save_defaults_btn.clicked.connect(lambda: self.save_options_to_settings(as_default=True))
        self.reset_defaults_btn.clicked.connect(self.reset_options_to_defaults)
        cancel.clicked.connect(self.reject)
        self.start.clicked.connect(self._on_start_clicked)
        
        self.files.filesDropped.connect(self.add_paths)
        self.files.model().rowsInserted.connect(self.auto_detect_options)
        self.files.model().rowsRemoved.connect(self.auto_detect_options)

        # Install drag and drop event filter on dialog child widgets
        for child in self.findChildren(QWidget):
            if child is not self.files and child is not self.files.viewport() and not isinstance(child, QLineEdit):
                child.setAcceptDrops(True)
                child.installEventFilter(self)

    def eventFilter(self, watched, event):
        if event.type() in (QEvent.Type.DragEnter, QEvent.Type.DragMove):
            paths = extract_dropped_file_paths(event.mimeData())
            if paths or event.mimeData().hasUrls() or event.mimeData().hasText():
                event.setDropAction(Qt.DropAction.CopyAction)
                event.acceptProposedAction()
                event.accept()
                return True
        elif event.type() == QEvent.Type.Drop:
            paths = extract_dropped_file_paths(event.mimeData())
            if paths:
                self.add_paths(paths)
                event.setDropAction(Qt.DropAction.CopyAction)
                event.acceptProposedAction()
                event.accept()
                return True
        return super().eventFilter(watched, event)

    def _load_saved_options(self):
        parent = self.parent()
        settings = getattr(parent, "settings_store", None)
        if not settings:
            return
        pipe_mode = str(settings.value("batch_opt_pipeline_mode", "full"))
        if pipe_mode == "custom":
            self.pipeline_custom_radio.setChecked(True)
        else:
            self.pipeline_full_radio.setChecked(True)

        def _to_bool(val, default):
            if val is None:
                return default
            return str(val).lower() in ("true", "1", "yes")

        self.proc_transcribe.setChecked(_to_bool(settings.value("batch_opt_proc_transcribe"), True))
        self.proc_diarize.setChecked(_to_bool(settings.value("batch_opt_proc_diarize"), True))
        self.proc_stories.setChecked(_to_bool(settings.value("batch_opt_proc_stories"), True))
        self.proc_translate.setChecked(_to_bool(settings.value("batch_opt_proc_translate"), False))

        expected = str(settings.value("batch_opt_expected_speakers", getattr(parent, "expected_speakers", "auto") or "auto"))
        idx_exp = self.batch_expected_speakers_combo.findData(expected)
        if idx_exp >= 0:
            self.batch_expected_speakers_combo.setCurrentIndex(idx_exp)
        try:
            self.batch_gap_spin.setValue(float(settings.value("batch_opt_story_gap", 3.0) or 3.0))
            self.batch_pad_spin.setValue(float(settings.value("batch_opt_story_pad", 0.2) or 0.2))
        except (TypeError, ValueError):
            pass
        translate_dir = str(settings.value("batch_opt_translate_direction", "auto"))
        idx_dir = self.batch_translate_direction_combo.findData(translate_dir)
        if idx_dir >= 0:
            self.batch_translate_direction_combo.setCurrentIndex(idx_dir)
            self.batch_export_translate_direction_combo.setCurrentIndex(idx_dir)

        self.save_project_check.setChecked(_to_bool(settings.value("batch_opt_save_project"), True))
        self.skip_existing_check.setChecked(_to_bool(settings.value("batch_opt_skip_existing"), True))
        self.save_project_only_check.setChecked(_to_bool(settings.value("batch_opt_save_project_only"), False))

        scope = str(settings.value("batch_opt_scope", "full"))
        idx = self.scope_combo.findData(scope)
        if idx >= 0:
            self.scope_combo.setCurrentIndex(idx)

        self.fmt_txt.setChecked(_to_bool(settings.value("batch_opt_fmt_txt"), True))
        self.fmt_docx.setChecked(_to_bool(settings.value("batch_opt_fmt_docx"), True))
        self.fmt_srt.setChecked(_to_bool(settings.value("batch_opt_fmt_srt"), False))
        self.fmt_vtt.setChecked(_to_bool(settings.value("batch_opt_fmt_vtt"), False))

        self.include_speakers.setChecked(_to_bool(settings.value("batch_opt_include_speakers"), True))
        self.include_times.setChecked(_to_bool(settings.value("batch_opt_include_times"), False))
        self.translate_check.setChecked(_to_bool(settings.value("batch_opt_translate_check"), False))

        self.unified_transcripts_check.setChecked(_to_bool(settings.value("batch_opt_unified_transcripts"), False))
        self.transcripts_output.setText(str(settings.value("batch_opt_transcripts_output", "") or ""))
        self.transcripts_dir_widget.setEnabled(
            self.unified_transcripts_check.isChecked() and not self.save_project_only_check.isChecked()
        )

    def save_options_to_settings(self, as_default=False):
        parent = self.parent()
        settings = getattr(parent, "settings_store", None)
        if not settings:
            return
        pipe_mode = "custom" if self.pipeline_custom_radio.isChecked() else "full"
        settings.setValue("batch_opt_pipeline_mode", pipe_mode)
        settings.setValue("batch_opt_proc_transcribe", self.proc_transcribe.isChecked())
        settings.setValue("batch_opt_proc_diarize", self.proc_diarize.isChecked())
        settings.setValue("batch_opt_proc_stories", self.proc_stories.isChecked())
        settings.setValue("batch_opt_proc_translate", self.proc_translate.isChecked())
        settings.setValue("batch_opt_expected_speakers", self.batch_expected_speakers_combo.currentData())
        settings.setValue("batch_opt_story_gap", self.batch_gap_spin.value())
        settings.setValue("batch_opt_story_pad", self.batch_pad_spin.value())
        settings.setValue("batch_opt_translate_direction", self.batch_export_translate_direction_combo.currentData())

        settings.setValue("batch_opt_save_project", self.save_project_check.isChecked())
        settings.setValue("batch_opt_skip_existing", self.skip_existing_check.isChecked())
        settings.setValue("batch_opt_save_project_only", self.save_project_only_check.isChecked())

        settings.setValue("batch_opt_scope", self.scope_combo.currentData())
        settings.setValue("batch_opt_fmt_txt", self.fmt_txt.isChecked())
        settings.setValue("batch_opt_fmt_docx", self.fmt_docx.isChecked())
        settings.setValue("batch_opt_fmt_srt", self.fmt_srt.isChecked())
        settings.setValue("batch_opt_fmt_vtt", self.fmt_vtt.isChecked())

        settings.setValue("batch_opt_include_speakers", self.include_speakers.isChecked())
        settings.setValue("batch_opt_include_times", self.include_times.isChecked())
        settings.setValue("batch_opt_translate_check", self.translate_check.isChecked())

        settings.setValue("batch_opt_unified_transcripts", self.unified_transcripts_check.isChecked())
        settings.setValue("batch_opt_transcripts_output", self.transcripts_output.text().strip())
        if as_default:
            QMessageBox.information(self, "Batch Options", "Current batch export options saved as defaults.")

    def reset_options_to_defaults(self):
        self.pipeline_full_radio.setChecked(True)
        self.proc_transcribe.setChecked(True)
        self.proc_diarize.setChecked(True)
        self.proc_stories.setChecked(True)
        self.proc_translate.setChecked(False)
        idx_exp = self.batch_expected_speakers_combo.findData("auto")
        if idx_exp >= 0:
            self.batch_expected_speakers_combo.setCurrentIndex(idx_exp)
        self.batch_gap_spin.setValue(3.0)
        self.batch_pad_spin.setValue(0.2)
        idx_dir = self.batch_translate_direction_combo.findData("auto")
        if idx_dir >= 0:
            self.batch_translate_direction_combo.setCurrentIndex(idx_dir)
            self.batch_export_translate_direction_combo.setCurrentIndex(idx_dir)

        self.save_project_check.setChecked(True)
        self.skip_existing_check.setChecked(True)
        self.save_project_only_check.setChecked(False)

        idx = self.scope_combo.findData("full")
        if idx >= 0:
            self.scope_combo.setCurrentIndex(idx)

        self.fmt_txt.setChecked(True)
        self.fmt_docx.setChecked(True)
        self.fmt_srt.setChecked(False)
        self.fmt_vtt.setChecked(False)

        self.include_speakers.setChecked(True)
        self.include_times.setChecked(False)
        self.translate_check.setChecked(False)

        self.unified_transcripts_check.setChecked(False)
        self.transcripts_output.clear()
        self.transcripts_dir_widget.setEnabled(False)
       
        self.save_options_to_settings()
        QMessageBox.information(self, "Batch Options", "Batch export options reset to factory defaults.")

    def _on_start_clicked(self):
        self.save_options_to_settings(as_default=False)
        self.accept()

    def dragEnterEvent(self, event):
        paths = extract_dropped_file_paths(event.mimeData())
        if paths or event.mimeData().hasUrls() or event.mimeData().hasText():
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
        else:
            event.ignore()

    def dragMoveEvent(self, event):
        paths = extract_dropped_file_paths(event.mimeData())
        if paths or event.mimeData().hasUrls() or event.mimeData().hasText():
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
        else:
            event.ignore()

    def dropEvent(self, event):
        paths = extract_dropped_file_paths(event.mimeData())
        if paths:
            self.add_paths(paths)
            event.setDropAction(Qt.DropAction.CopyAction)
            event.acceptProposedAction()
            event.accept()
        else:
            event.ignore()

    def add_paths(self, paths):
        for f in paths:
            if not f:
                continue
            cleaned_str = str(f).strip().strip('"').strip("'")
            if not cleaned_str:
                continue
            p = Path(cleaned_str)
            if p.is_file() and not any(self.files.item(i).text() == str(p) for i in range(self.files.count())):
                self.files.addItem(str(p))
            elif p.is_dir():
                for sub in sorted(p.rglob("*")):
                    if sub.is_file() and not any(self.files.item(i).text() == str(sub) for i in range(self.files.count())):
                        self.files.addItem(str(sub))
        self.auto_detect_options()

    def add_files(self):
        filters = "All Supported Files (*.*)"
        parent = self.parent()
        settings = getattr(parent, "settings_store", None)
        default_dir = ""
        if settings is not None:
            default_dir = str(settings.value("batch_add_files_directory", "") or "")
        if not default_dir:
            default_dir = getattr(parent, "_dialog_directory", lambda: "")()
        files, _ = QFileDialog.getOpenFileNames(self, "Add Files", default_dir, filters)
        if files and settings is not None:
            settings.setValue("batch_add_files_directory", str(Path(files[0]).resolve().parent))
        self.add_paths(files)

    def choose_output(self):
        d = QFileDialog.getExistingDirectory(
            self, "Choose Output Folder", getattr(self.parent(), "_dialog_directory", lambda: "")()
        )
        if d:
            self.output.setText(d)

    def choose_transcripts_output(self):
        curr = self.transcripts_output.text().strip()
        if not curr or not os.path.exists(curr):
            base = self.output.text().strip()
            if base and os.path.exists(base):
                curr = base
            else:
                curr = getattr(self.parent(), "_dialog_directory", lambda: "")()
        d = QFileDialog.getExistingDirectory(self, "Choose Transcripts Folder", curr)
        if d:
            self.transcripts_output.setText(d)

    def auto_detect_options(self):
        """Auto-detect available options based on imported file extensions."""
        file_paths = [self.files.item(i).text() for i in range(self.files.count())]
        
        has_media = any(
            Path(p).suffix.lower() in {
                ".mp3", ".wav", ".m4a", ".flac", ".mp4", ".mov", ".mkv", ".avi", ".webm"
            } for p in file_paths
        )
        has_docs = any(
            Path(p).suffix.lower() in {
                ".txt", ".docx", ".pdf", ".html", ".htm", ".md"
            } for p in file_paths
        )

        # Adjust option controls depending on file types found in queue
        self.include_speakers.setEnabled(has_media)
        self.include_times.setEnabled(has_media)
        self.fmt_srt.setEnabled(has_media)
        self.fmt_vtt.setEnabled(has_media)
        self.scope_combo.setEnabled(has_media)


# ============================================================
# Cache Inspection, Purging, & ClearCacheDialog (Extracted to cache_manager.py)
# ============================================================
from cache_manager import (
    format_byte_size,
    cleanup_old_thumbnail_cache,
    get_cache_disk_usage,
    purge_caches,
    ClearCacheDialog,
)
