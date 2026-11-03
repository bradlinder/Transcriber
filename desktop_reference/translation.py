"""Radio & TV Segmenter — Local Translation Mixin.

Provides offline neural machine translation via TranslationWorker (CTranslate2 / Opus-MT),
translation state management, UI language toggling, and asynchronous model verification.
"""

from __future__ import annotations

import sys
import copy
import json
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

from prs_shared import format_time, register_process, unregister_process


def _translation_worker_class():
    """Load the translation worker only when the translation plugin is installed."""
    from plugins.translation.worker import TranslationWorker
    return TranslationWorker

from PySide6.QtCore import QObject, Qt, QThread, QTimer, QProcess, QProcessEnvironment, Signal
from PySide6.QtWidgets import (
    QApplication,
    QMessageBox,
    QProgressDialog,
)


class TranslationEnvSetupWorker(QObject):
    """Background worker that sets up the isolated translation runtime environment."""
    progress = Signal(float, str)
    finished = Signal(object)  # None on success, error message (str) on failure

    def __init__(self, plugin_dir: Path | None = None, runtime_mgr=None):
        super().__init__()
        self.plugin_dir = plugin_dir
        self.runtime_mgr = runtime_mgr
        self._cancelled = False
        self.cancel_event = threading.Event()

    def cancel(self):
        self._cancelled = True
        self.cancel_event.set()

    def run(self):
        error = None
        try:
            manager = self.runtime_mgr if self.runtime_mgr is not None else _translation_runtime_manager()
            if not hasattr(manager, "ensure_environment"):
                import runtime_manager
                manager = runtime_manager.RuntimeManager()

            def _on_progress(pct_or_msg, msg=""):
                if self._cancelled or self.cancel_event.is_set():
                    return
                if isinstance(pct_or_msg, (int, float)):
                    pct = float(pct_or_msg)
                    text = str(msg)
                else:
                    pct = 0.0
                    text = str(pct_or_msg)
                self.progress.emit(pct, text)

            ensure_fn = getattr(manager, "ensure_environment", None)
            if ensure_fn is None:
                raise AttributeError("RuntimeManager has no 'ensure_environment' method.")

            ok = ensure_fn(
                "translate",
                progress_cb=_on_progress,
                plugin_dir=self.plugin_dir,
                cancel_event=self.cancel_event,
            )
            if not ok and not self._cancelled and not self.cancel_event.is_set():
                err_detail = getattr(manager, "get_last_error", lambda: "")()
                error = "The isolated translation runtime could not be installed or updated."
                if err_detail:
                    error = f"{error}\n\nDetails:\n{err_detail}"
            elif self._cancelled or self.cancel_event.is_set():
                error = "Translation environment preparation was cancelled."
        except Exception as exc:
            if not self._cancelled and not self.cancel_event.is_set():
                error = f"{type(exc).__name__}: {exc}"
            else:
                error = "Translation environment preparation was cancelled."
        self.finished.emit(error)


def _translation_runtime_entry_path(app=None) -> Path:
    manager = getattr(app, "plugin_manager", None) if app is not None else None
    if manager is not None:
        folder = manager.plugin_paths.get("translation")
        if folder:
            folder = Path(folder)
            if folder.is_dir():
                candidate = folder / "runtime_entry.py"
                if candidate.exists():
                    return candidate

    try:
        from plugins.manager import PluginManager
        candidates = [
            Path(__file__).resolve().parent / "plugins" / "translation" / "runtime_entry.py",
            PluginManager.get_user_plugins_dir() / "translation" / "runtime_entry.py",
            PluginManager.get_bundled_plugins_dir() / "translation" / "runtime_entry.py",
        ]
        for c in candidates:
            if c.is_file():
                return c
    except Exception:
        fallback = Path(__file__).resolve().parent / "plugins" / "translation" / "runtime_entry.py"
        if fallback.is_file():
            return fallback

    return Path()

def _translation_runtime_manager(app=None):
    if app is not None and hasattr(app, "runtime_mgr") and app.runtime_mgr is not None:
        return app.runtime_mgr
    import runtime_manager
    if hasattr(runtime_manager, "RuntimeManager"):
        return runtime_manager.RuntimeManager()
    from runtime_manager import RuntimeManager
    return RuntimeManager()

class TranslationMixin:
    """Mixin class managing transcript translation, language toggling, and worker lifecycle."""

    def source_language_code(self) -> str:
        """Return the source language code for translation.

        Honors an explicit direction chosen via the batch dialog's
        Translation control ("en-es" or "es-en"); for "auto" (the default),
        flips based on the transcript's Whisper-detected language or project
        source_language metadata so a Spanish-language recording translates to English.
        """
        direction = str(getattr(self, "translation_direction", "auto") or "auto")
        if direction == "es-en":
            return "es"
        if direction == "en-es":
            return "en"
        meta_lang = ""
        if hasattr(self, "project_metadata") and isinstance(getattr(self, "project_metadata", None), object):
            meta_lang = str(getattr(self.project_metadata, "source_language", "") or "").lower()
        curr_mode = str(getattr(self, "translation_display_mode", "en") or "en").lower()
        trans_lang = str((self.transcript or {}).get("language", "") or "").lower()
        if not trans_lang and self.transcript and isinstance(self.transcript, dict):
            # Lightweight heuristics: check if the first 300 characters contain Spanish stopwords
            txt = str(self.transcript.get("text", "")).lower()[:300]
            if txt:
                import re
                spanish_words = {"hola", "bienvenidas", "el", "la", "en", "de", "los", "las", "un", "una", "y", "atrévete"}
                words = set(re.findall(r"\b\w+\b", txt))
                if len(words & spanish_words) >= 3:
                    trans_lang = "es"
        if meta_lang.startswith("es"):
            return "es"
        if trans_lang.startswith("es"):
            return "es"
            
        detected = meta_lang or trans_lang or curr_mode
        self.log_activity(f"[DEBUG] source_language_code: meta_lang={meta_lang}, trans_lang={trans_lang}, curr_mode={curr_mode}, detected={detected}")
        return "es" if detected.startswith("es") else "en"

    def target_language_code(self) -> str:
        """Return the target language code for translation (see source_language_code)."""
        direction = str(getattr(self, "translation_direction", "auto") or "auto")
        if direction == "es-en":
            return "en"
        if direction == "en-es":
            return "es"
        detected = str((self.transcript or {}).get("language", "en") or "en").lower()
        return "en" if detected.startswith("es") else "es"

    def translation_key(self, from_code: str = "en", to_code: str = "es") -> str:
        """Generate standardized dictionary key for language translation pair."""
        return f"{from_code}-{to_code}"

    def get_translation_item(self, from_code: str, to_code: str) -> dict | None:
        """Return a current translation for an explicit language pair."""
        if not isinstance(getattr(self, "translations", None), dict):
            return None
        data = self.translations.get(self.translation_key(from_code, to_code))
        if data is None:
            data = self.translations.get(f"{from_code}_{to_code}")
        if not isinstance(data, dict) or data.get("status") == "stale":
            return None
        return data if data.get("segments") else None

    def translation_export_spec(self, options: dict | None = None):
        """Return (source, target, suffix) for batch translation exports.

        Normal interactive exports remain English + optional Spanish. Batch
        exports may explicitly request either direction, so the exported
        translated document uses the actual requested target language.
        """
        options = options or {}
        direction = str(options.get("translation_direction", "en-es") or "en-es")
        if direction not in {"en-es", "es-en"}:
            direction = "es-en" if self.source_language_code() == "es" else "en-es"
        source, target = direction.split("-")
        suffix = f"_{target}"
        return source, target, suffix

    def translation_is_current(self, key: str | None = None) -> bool:
        """Check if translation for the given key exists, has content, and is not stale."""
        key = key or self.translation_key()
        if not hasattr(self, "translations") or not isinstance(self.translations, dict):
            return False
        data = self.translations.get(key)
        if not data or not isinstance(data, dict):
            alt_key = key.replace("-", "_")
            data = self.translations.get(alt_key)
        if not data or not isinstance(data, dict):
            return False
        if data.get("status") == "stale":
            return False
        segments = data.get("segments", [])
        return bool(segments)

    def has_spanish_translation(self) -> bool:
        """Check if an English-to-Spanish translation is available."""
        item = self.get_spanish_translation_item()
        if not item or not isinstance(item, dict):
            return False
        segments = item.get("segments", [])
        return bool(segments)

    def get_spanish_translation_item(self) -> dict | None:
        """Return the translation dictionary for en-es or es-en if present."""
        if not hasattr(self, "translations") or not isinstance(self.translations, dict):
            return None
        src_code = self.source_language_code() if hasattr(self, "source_language_code") else "en"
        if src_code == "es":
            return (
                self.translations.get("es-en")
                or self.translations.get("es_en")
                or self.translations.get("en-es")
                or self.translations.get("en_es")
                or None
            )
        else:
            return (
                self.translations.get("en-es")
                or self.translations.get("en_es")
                or self.translations.get("es-en")
                or self.translations.get("es_en")
                or None
            )

    def mark_stale_translations(self):
        """Mark all existing translations as stale when transcript text changes."""
        if hasattr(self, "translations") and isinstance(self.translations, dict):
            for key, val in self.translations.items():
                if isinstance(val, dict):
                    val["status"] = "stale"
        if hasattr(self, "update_translation_language_selector"):
            self.update_translation_language_selector()

    def update_translation_language_selector(self):
        """Update translation UI selector states (tabs / combobox / actions)."""
        src_code = self.source_language_code()
        tgt_code = self.target_language_code()

        trans_item = self.get_spanish_translation_item()
        has_trans = bool(trans_item and trans_item.get("segments"))
        has_es = has_trans or self.has_spanish_translation()
        is_stale = isinstance(trans_item, dict) and trans_item.get("status") == "stale"

        is_plugin_on = False
        if hasattr(self, "plugin_manager"):
            is_plugin_on = self.plugin_manager.is_plugin_enabled("translation")
        has_any_trans = has_trans or bool(getattr(self, "translations", {})) or getattr(self, "translation_display_mode", "en") not in {"en", "es"}

        if hasattr(self, "transcript_language_selector") and self.transcript_language_selector is not None:
            self.transcript_language_selector.blockSignals(True)
            self.transcript_language_selector.clear()

            if src_code == "es":
                self.transcript_language_selector.addItem("Español (Original)", "es")
                if has_trans:
                    lbl = "English (Translation - Outdated)" if is_stale else "English (Translation)"
                    self.transcript_language_selector.addItem(lbl, "en")
                    self.transcript_language_selector.addItem("Bilingual (Split)", "split")
            else:
                self.transcript_language_selector.addItem("English (Original)", "en")
                if has_trans:
                    lbl = "Español (Translation - Outdated)" if is_stale else "Español (Translation)"
                    self.transcript_language_selector.addItem(lbl, "es")
                    self.transcript_language_selector.addItem("Bilingual (Split)", "split")

            target_mode = getattr(self, "translation_display_mode", src_code)
            if target_mode == "bilingual":
                target_mode = "split"
            cur_idx = self.transcript_language_selector.findData(target_mode)
            if cur_idx < 0:
                cur_idx = 0
                self.translation_display_mode = src_code
            self.transcript_language_selector.setCurrentIndex(cur_idx)
            self.transcript_language_selector.blockSignals(False)
            self.transcript_language_selector.setVisible(is_plugin_on or has_any_trans)

        # Sync View -> Transcript -> Language Display menu actions
        cur_mode = getattr(self, "translation_display_mode", "en")
        if hasattr(self, "lang_en_action"):
            self.lang_en_action.blockSignals(True)
            self.lang_en_action.setChecked(cur_mode == "en")
            self.lang_en_action.blockSignals(False)
        if hasattr(self, "lang_es_action"):
            self.lang_es_action.blockSignals(True)
            self.lang_es_action.setChecked(cur_mode == "es")
            self.lang_es_action.setEnabled(has_es)
            self.lang_es_action.blockSignals(False)
        if hasattr(self, "lang_split_action"):
            self.lang_split_action.blockSignals(True)
            self.lang_split_action.setChecked(cur_mode in {"split", "bilingual"})
            self.lang_split_action.setEnabled(has_es)
            self.lang_split_action.blockSignals(False)

        if hasattr(self, "export_translation_action") and self.export_translation_action is not None:
            self.export_translation_action.setEnabled(has_es)

    def change_translation_display(self, mode: str | int | None = None):
        """Change the active translation display mode ('en', 'es', or 'split')."""
        if isinstance(mode, int):
            if hasattr(self, "transcript_language_selector") and self.transcript_language_selector is not None:
                mode = self.transcript_language_selector.itemData(mode)
            else:
                mode = "en"
        elif mode is None:
            if hasattr(self, "transcript_language_selector") and self.transcript_language_selector is not None:
                mode = self.transcript_language_selector.currentData()
            else:
                mode = "en"
        mode_str = str(mode or "en")
        if mode_str == "bilingual":
            mode_str = "split"
        self.translation_display_mode = mode_str
        if hasattr(self, "update_translation_language_selector"):
            self.update_translation_language_selector()
        self.render_transcript()

    def render_translation_view(self):
        """Re-render transcript or translation view based on current display settings."""
        self.render_transcript()

    def check_translation_models_async(self):
        """Asynchronously verify if required translation models are installed."""
        def _check():
            variant = getattr(self, "translation_model_variant", "tiny")
            try:
                if hasattr(self, "plugin_manager") and not self.plugin_manager.is_plugin_installed("translation"):
                    return
                is_installed = _translation_worker_class().model_is_installed("en", "es", variant)
            except Exception:
                return
            cache_key = f"{variant}:en-es"
            if hasattr(self, "translation_model_status_cache"):
                self.translation_model_status_cache[cache_key] = is_installed

        t = threading.Thread(target=_check, daemon=True)
        t.start()

    def _cleanup_translation_env_thread(self):
        worker = getattr(self, "_translation_env_worker", None)
        if worker is not None:
            try:
                worker.progress.disconnect()
            except Exception:
                pass
            try:
                worker.finished.disconnect()
            except Exception:
                pass
        thread = getattr(self, "_translation_env_qthread", None)
        if thread is not None:
            try:
                thread.quit()
                thread.wait(200)
            except Exception:
                pass
        self._translation_env_thread = None
        self._translation_env_qthread = None
        self._translation_env_worker = None

    def _on_translation_env_progress(self, percent_or_msg, message: str = ""):
        if isinstance(percent_or_msg, (int, float)):
            pct = float(percent_or_msg)
            msg = message or ""
        else:
            pct = 0.0
            msg = str(percent_or_msg)
        if msg:
            now = time.monotonic()
            last_log = getattr(self, "_last_translation_env_log_time", 0.0)
            if now - last_log >= 2.0 or pct >= 100.0 or "error" in msg.lower() or "ready" in msg.lower() or "success" in msg.lower():
                self._last_translation_env_log_time = now
                self.log_activity(f"[TRANSLATION] {msg}", mark_dirty=False)
        self.update_processing_progress(pct, msg)

    def stop_translation_worker(self, timeout_ms: int = 5000) -> bool:
        if getattr(self, "_translation_env_worker", None) is not None:
            try:
                self._translation_env_worker.cancel()
            except Exception:
                pass
        self._cleanup_translation_env_thread()
        self._pending_translation_setup = None

        proc = getattr(self, "translation_process", None)
        if proc is not None:
            try:
                unregister_process(proc)
            except Exception:
                pass
            try:
                proc.readyReadStandardOutput.disconnect()
            except Exception:
                pass
            try:
                proc.finished.disconnect()
            except Exception:
                pass
            try:
                proc.errorOccurred.disconnect()
            except Exception:
                pass
            try:
                if proc.state() != QProcess.ProcessState.NotRunning:
                    # On macOS/Unix, ML inference subprocesses (CTranslate2/OpenMP) often block SIGTERM.
                    # Attempt a fast 150ms graceful termination before using SIGKILL to prevent main GUI thread hangs.
                    proc.terminate()
                    if not proc.waitForFinished(150):
                        proc.kill()
                        proc.waitForFinished(500)
            except Exception:
                pass
            try:
                req_file = getattr(proc, "_rtvs_request_file", None)
                if req_file and Path(req_file).exists():
                    Path(req_file).unlink(missing_ok=True)
            except Exception:
                pass
            try:
                proc.deleteLater()
            except Exception:
                pass

        self.translation_process = None
        self.translation_thread = None
        self.translation_worker = None
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.hide()
        return True

    def _start_translation_runtime_request(self, request: dict, on_finished=None) -> bool:
        """Run translation code in the plugin-owned isolated Python environment."""
        if (
            getattr(self, "translation_process", None) is not None
            or getattr(self, "_translation_env_thread", None) is not None
        ):
            return False
        entry = _translation_runtime_entry_path(self)
        if not entry.exists():
            self._on_translation_error("The translation plugin runtime entry point is missing.")
            return False

        manager = _translation_runtime_manager(self)
        plugin_dir = entry.parent if entry and entry.exists() else None

        from_code = request.get("from_code", "en")
        to_code = request.get("to_code", "es")
        variant = request.get("model_variant") or request.get("variant") or "tiny"
        is_install_only = bool(request.get("installation_only", False))
        self._active_translation_from = from_code
        self._active_translation_to = to_code
        self._active_translation_variant = variant

        # Check if model is already downloaded
        needs_model = True
        try:
            worker_cls = _translation_worker_class()
            needs_model = not worker_cls.model_is_installed(from_code, to_code, variant)
        except Exception:
            needs_model = True

        # Fast path: check if environment is already up-to-date on disk
        is_ready = False
        try:
            raw_exe = manager._get_raw_executable("translate")
            is_ready = bool(raw_exe.exists() and manager.is_env_up_to_date("translate"))
        except Exception:
            is_ready = False

        stages = []
        if not is_ready:
            stages.append(("runtime_env", "Translation Runtime", "Installing dependencies"))
        if needs_model or is_install_only:
            stages.append(("model_download", "Model Download", f"OPUS-MT-{variant} ({from_code.upper()} → {to_code.upper()})"))
        if not is_install_only:
            stages.append(("translation", "Translation", f"{from_code.upper()} → {to_code.upper()}"))

        self._translation_pipeline_stages = stages

        if len(stages) > 1:
            self.pipeline_active = True
            self.pipeline_total_stages = len(stages)
            self.pipeline_current_stage_idx = 1
            self.pipeline_queue = [s[0] for s in stages[1:]]
            self.pipeline_start_monotonic = time.monotonic()

        if is_ready:
            python_exe = manager.get_executable("translate")
            if python_exe and Path(python_exe).exists():
                if stages:
                    self.set_processing_stage(stages[0][1], stages[0][2])
                return self._launch_translation_process(request, on_finished, python_exe, entry)

        # First-run or update: provision environment asynchronously to keep UI completely responsive
        self.set_processing_stage("Translation Runtime", "Installing dependencies")
        self.log_activity("[TRANSLATION] Preparing isolated translation environment in background...")
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.show()
            self.cancel_button.setEnabled(True)

        self._translation_env_qthread = QThread(self)
        self._translation_env_worker = TranslationEnvSetupWorker(
            plugin_dir=plugin_dir,
            runtime_mgr=getattr(self, "runtime_mgr", None) or manager
        )
        self._translation_env_worker.moveToThread(self._translation_env_qthread)
        if hasattr(self, "_track_worker_thread"):
            self._track_worker_thread(self._translation_env_qthread)

        self._translation_env_qthread.started.connect(self._translation_env_worker.run)
        self._translation_env_worker.progress.connect(self._on_translation_env_progress)

        self._pending_translation_setup = (request, on_finished, entry)
        self._translation_env_worker.finished.connect(self._on_translation_env_setup_finished)
        self._translation_env_thread = self._translation_env_qthread
        self._translation_env_qthread.start()
        return True

    def _on_translation_env_setup_finished(self, error):
        self._cleanup_translation_env_thread()
        pending = getattr(self, "_pending_translation_setup", None)
        self._pending_translation_setup = None
        if not pending:
            return
        request, on_finished, entry = pending

        if error:
            self.log_activity(f"[TRANSLATION] Environment setup error: {error}", mark_dirty=False)
            self._on_translation_error(error)
            if on_finished:
                try:
                    on_finished(-1, error, "")
                except Exception:
                    pass
            return

        manager = _translation_runtime_manager(self)
        py_exe = manager.get_executable("translate")
        if not py_exe or not Path(py_exe).exists():
            err = "The isolated translation Python runtime is unavailable."
            self._on_translation_error(err)
            if on_finished:
                try:
                    on_finished(-1, err, "")
                except Exception:
                    pass
            return

        # Advance to next stage in pipeline (e.g. Model Download or Translation)
        if getattr(self, "pipeline_active", False) and getattr(self, "_translation_pipeline_stages", []):
            next_stage = None
            for idx, stage_info in enumerate(self._translation_pipeline_stages):
                if stage_info[0] != "runtime_env":
                    next_stage = stage_info
                    self.pipeline_current_stage_idx = idx + 1
                    self.pipeline_queue = [s[0] for s in self._translation_pipeline_stages[idx + 1:]]
                    break
            if next_stage:
                self.set_processing_stage(next_stage[1], next_stage[2])
                self.update_processing_progress(0, f"Starting {next_stage[1]}…")

        self._launch_translation_process(request, on_finished, py_exe, entry)

    def _launch_translation_process(self, request: dict, on_finished, python_exe: str, entry: Path) -> bool:
        from prs_shared import get_models_storage_dir
        models_dir = str(get_models_storage_dir())
        request["models_dir"] = models_dir

        fd, request_path = tempfile.mkstemp(prefix="rtvs_translate_", suffix=".json")
        os.close(fd)
        request_file = Path(request_path)
        request_file.write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")

        proc = QProcess(self)
        proc_env = QProcessEnvironment.systemEnvironment()
        proc_env.insert("KMP_DUPLICATE_LIB_OK", "TRUE")
        proc_env.insert("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
        proc_env.insert("TOKENIZERS_PARALLELISM", "false")
        proc_env.insert("RTVS_MODELS_DIR", models_dir)
        proc_env.insert("HF_HOME", str(Path(models_dir) / "huggingface"))
        for key in ("SSL_CERT_FILE", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE"):
            val = os.environ.get(key)
            if val:
                proc_env.insert(key, val)
        if sys.platform == "darwin":
            existing_path = proc_env.value("PATH", "")
            mac_paths = [
                "/opt/homebrew/bin",
                "/opt/homebrew/sbin",
                "/usr/local/bin",
                "/usr/local/sbin",
                "/opt/local/bin",
                str(Path.home() / ".local" / "bin"),
                str(Path.home() / ".cargo" / "bin"),
            ]
            parts = existing_path.split(os.pathsep) if existing_path else []
            for p in reversed(mac_paths):
                if Path(p).is_dir() and p not in parts:
                    parts.insert(0, p)
            proc_env.insert("PATH", os.pathsep.join(parts))
            try:
                py_lib = Path(python_exe).resolve().parent.parent / "lib"
                if py_lib.is_dir():
                    existing_dyld = proc_env.value("DYLD_LIBRARY_PATH", "")
                    proc_env.insert("DYLD_LIBRARY_PATH", f"{py_lib}:{existing_dyld}" if existing_dyld else str(py_lib))
            except Exception:
                pass
        proc.setProcessEnvironment(proc_env)
        proc.setProgram(python_exe)
        proc.setArguments([str(entry), str(request_file)])
        proc.setProcessChannelMode(QProcess.ProcessChannelMode.SeparateChannels)
        proc._rtvs_request_file = request_file
        proc._rtvs_output = ""
        proc._rtvs_line_buffer = ""
        proc._rtvs_callback = on_finished
        self.translation_process = proc
        # Keep compatibility with existing busy checks.
        self.translation_thread = proc

        def read_output(force_flush=False):
            data = bytes(proc.readAllStandardOutput()).decode("utf-8", errors="replace")
            proc._rtvs_output += data
            proc._rtvs_line_buffer += data
            lines = proc._rtvs_line_buffer.split("\n")
            proc._rtvs_line_buffer = lines.pop() if lines else ""
            
            if force_flush and proc._rtvs_line_buffer.strip():
                lines.append(proc._rtvs_line_buffer)
                proc._rtvs_line_buffer = ""
                
            for line in lines:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except Exception as exc:
                    self.log_activity(f"[TRANSLATION] JSON parse error on final payload: {exc}", mark_dirty=False)
                    continue
                kind = msg.get("type")
                if kind == "progress":
                    self._on_translation_progress(msg.get("percent", 0), msg.get("message", ""))
                elif kind == "finished":
                    if not request.get("installation_only"):
                        try:
                            self._on_translation_finished(msg.get("result", []), msg.get("key", ""))
                        except Exception as exc:
                            self.log_activity(f"[TRANSLATION] Error during processing finished payload: {exc}", mark_dirty=False)
                elif kind == "cancelled":
                    self._on_translation_cancelled(msg.get("result", []), msg.get("key", ""))
                elif kind == "error":
                    self._on_translation_error(msg.get("message", "Unknown translation error"))

        def finished(exit_code, exit_status):
            read_output(force_flush=True)
            unregister_process(proc)
            stderr = bytes(proc.readAllStandardError()).decode("utf-8", errors="replace").strip()
            if proc._rtvs_callback:
                try:
                    proc._rtvs_callback(exit_code, stderr, proc._rtvs_output)
                except Exception as exc:
                    self.log_activity(f"[TRANSLATION] Runtime callback error: {exc}", mark_dirty=False)
            if exit_code != 0 or exit_status == QProcess.ExitStatus.CrashExit:
                err_msg = stderr[-4000:] if stderr else f"Process crashed or exited with code {exit_code}."
                self._on_translation_error(err_msg)
            try:
                proc._rtvs_request_file.unlink(missing_ok=True)
            except Exception:
                pass
            self.translation_process = None
            self.translation_thread = None
            self.translation_worker = None
            proc.deleteLater()

        proc.readyReadStandardOutput.connect(read_output)
        proc.finished.connect(finished)
        proc.errorOccurred.connect(lambda _err: unregister_process(proc))
        register_process(proc)
        proc.start()
        if not proc.waitForStarted(10000):
            unregister_process(proc)
            try:
                request_file.unlink(missing_ok=True)
            except Exception:
                pass
            self.translation_process = None
            self.translation_thread = None
            self._on_translation_error("Could not start the isolated translation runtime.")
            proc.deleteLater()
            return False
        return True

    def start_translation(self, from_code: str = None, to_code: str = None, install_if_missing: bool = True):
        """Start local translation in the translation plugin's isolated runtime."""
        if from_code is None or to_code is None or (from_code == "en" and to_code == "es" and self.source_language_code() == "es"):
            from_code = self.source_language_code()
            to_code = self.target_language_code()
        if hasattr(self, "plugin_manager") and not self.plugin_manager.is_plugin_enabled("translation"):
            QMessageBox.warning(self, "Plugin Disabled", "The Language Translation plugin is currently disabled.\nYou can enable it in Settings > Manage Plugins & Add-ons.")
            return
        if getattr(self, "translation_process", None) is not None:
            QMessageBox.information(self, "Translation Busy", "A translation task is already running.")
            return

        raw_segments = self.transcript.get("segments", []) if isinstance(self.transcript, dict) else (self.transcript if isinstance(self.transcript, list) else [])
        if not raw_segments:
            QMessageBox.warning(self, "No Transcript", "A transcript is required before running translation.")
            return

        variant = getattr(self, "translation_model_variant", "tiny")
        worker_cls = None
        try:
            worker_cls = _translation_worker_class()
        except Exception:
            pass

        model_installed = bool(worker_cls and worker_cls.model_is_installed(from_code, to_code, variant))
        if not model_installed:
            variant_display = "OPUS-MT-tiny" if variant == "tiny" else "OPUS-MT"
            reply = QMessageBox.question(
                self,
                "Translation Model Required",
                f"No translation model is currently installed for {from_code.upper()} → {to_code.upper()} ({variant_display}).\n\n"
                "Would you like to download and install this model now?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.Yes,
            )
            if reply != QMessageBox.StandardButton.Yes:
                self.log_activity(f"[TRANSLATION] Translation canceled: no installed model for {from_code}->{to_code}")
                if getattr(self, "pipeline_active", False):
                    self.pipeline_active = False
                    self.pipeline_queue = []
                    self.pipeline_rerun_confirmed = False
                self.set_processing_stage(None)
                self.set_tools_actions_enabled(True)
                if hasattr(self, "cancel_button") and self.cancel_button is not None:
                    self.cancel_button.hide()
                return

        self.set_processing_stage("Translating transcript", f"{from_code} → {to_code}")
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.show(); self.cancel_button.setEnabled(True)
        self.set_tools_actions_enabled(False)

        # Translation runs in an isolated subprocess. Pass the current
        # structured glossary explicitly rather than expecting the child
        # process to read the GUI process's QSettings store.
        try:
            from terminology import parse_glossary
            request_glossary = parse_glossary(getattr(self, "glossary", []))
        except Exception:
            request_glossary = []

        request = {
            "segments": copy.deepcopy(raw_segments),
            "from_code": from_code, "to_code": to_code,
            "install_if_missing": install_if_missing,
            "model_variant": variant, "variant": variant,
            "transcript": copy.deepcopy(self.transcript),
            "device": getattr(self, "translation_device", "cpu"),
            "glossary": request_glossary,
        }
        self.log_activity(f"[TRANSLATION] Starting isolated {from_code}->{to_code} translation ({variant}).")
        if not self._start_translation_runtime_request(request):
            self.set_tools_actions_enabled(True)
            self.set_processing_stage(None)
            if hasattr(self, "cancel_button") and self.cancel_button is not None: self.cancel_button.hide()

    def _on_translation_progress(self, percent: float, message: str):
        """Handle progress updates from _translation_worker_class()."""
        msg_lower = (message or "").lower()
        if "translated" in msg_lower or "loading opus-mt" in msg_lower or "optimizing" in msg_lower:
            if getattr(self, "pipeline_active", False) and getattr(self, "pipeline_current_stage_idx", 1) < getattr(self, "pipeline_total_stages", 1):
                self.pipeline_current_stage_idx = getattr(self, "pipeline_total_stages", 1)
                self.pipeline_queue = []
                from_code = getattr(self, "_active_translation_from", "en")
                to_code = getattr(self, "_active_translation_to", "es")
                self.set_processing_stage("Translation", f"{from_code.upper()} → {to_code.upper()}")
        self.update_processing_progress(percent, message)

    def _on_translation_finished(self, translated_transcript: Any, translation_key: str):
        """Handle successful translation results."""
        if not hasattr(self, "translations") or not isinstance(self.translations, dict):
            self.translations = {}

        # Remove obsolete/outdated translation directions to avoid state pollution
        if translation_key in ("es-en", "es_en"):
            self.translations.pop("en-es", None)
            self.translations.pop("en_es", None)
        elif translation_key in ("en-es", "en_es"):
            self.translations.pop("es-en", None)
            self.translations.pop("es_en", None)

        if isinstance(translated_transcript, list):
            translated_dict = copy.deepcopy(self.transcript) if isinstance(self.transcript, dict) else {}
            translated_dict["segments"] = translated_transcript
        elif isinstance(translated_transcript, dict):
            translated_dict = translated_transcript
        else:
            translated_dict = {"segments": []}

        translated_dict["status"] = "current"
        self.translations[translation_key] = translated_dict
        if hasattr(self, "processing_status") and isinstance(self.processing_status, dict):
            self.processing_status["translation"] = True
        if hasattr(self, "update_processing_stage_summary"):
            self.update_processing_stage_summary()

        self.log_activity(f"[TRANSLATION] Completed translation ({translation_key}).")
        self.mark_project_dirty("Translation completed")
        self.update_translation_language_selector()
        self.set_processing_stage("", "")
        self.set_tools_actions_enabled(True)
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.hide()

        # Maintain the current language view until the user selects a different view
        self.render_transcript()

        if getattr(self, "pipeline_active", False) and getattr(self, "pipeline_queue", []):
            QTimer.singleShot(0, self._run_next_selected_processing)
        elif getattr(self, "batch_active", False) and getattr(self, "pipeline_active", False):
            self.pipeline_active = False
            self.pipeline_rerun_confirmed = False
            self._batch_export_current()
            QTimer.singleShot(0, self._batch_next_media)
        elif getattr(self, "pipeline_active", False):
            self.pipeline_active = False
            self.pipeline_rerun_confirmed = False
            self.set_processing_stage(None)
            self.log_activity("[AUTOMATION] Selected processing complete.")

    def _on_translation_cancelled(self, results: Any, translation_key: str):
        """Handle translation worker cancellation."""
        self.log_activity(f"[TRANSLATION] Canceled by user ({translation_key}).")
        if getattr(self, "pipeline_active", False):
            self.pipeline_active = False
            self.pipeline_queue = []
            self.pipeline_rerun_confirmed = False
        self.set_processing_stage(None)
        self.set_tools_actions_enabled(True)
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.hide()

    def _on_translation_error(self, error_message: str):
        """Handle translation worker failures."""
        self.log_activity(f"[TRANSLATION ERROR] {error_message}")
        if getattr(self, "pipeline_active", False):
            self.pipeline_active = False
            self.pipeline_queue = []
            self.pipeline_rerun_confirmed = False
        self.set_processing_stage(None)
        self.set_tools_actions_enabled(True)
        if hasattr(self, "cancel_button") and self.cancel_button is not None:
            self.cancel_button.hide()
        QMessageBox.critical(self, "Translation Error", f"Translation failed:\n\n{error_message}")

    def _on_translation_thread_finished(self):
        """Clean up thread reference."""
        self.translation_thread = None
        self.translation_worker = None

    def _translation_thread_finished(self):
        """Alias for _on_translation_thread_finished."""
        self._on_translation_thread_finished()
