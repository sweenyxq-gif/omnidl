"""
OmniDownloader Desktop - Modern CustomTkinter GUI
Features:
- Midnight Obsidian dark theme matching mobile edition
- Multi-connection HTTP/HTTPS acceleration with up to 16 parallel threads
- 1-click clipboard paste & live URL probe (filename, size, range capability)
- Custom User-Agent dropdown & multiline HTTP headers editor
- Real-time rolling speed HUD (KB/s, MB/s), ETA, and connection counters
- Direct file open and folder reveal actions
"""

import os
import sys
import subprocess
import threading
from typing import Optional, Dict
import tkinter as tk
from tkinter import filedialog, messagebox

import customtkinter as ctk

from desktop.presets import (
    USER_AGENT_PRESETS,
    DEFAULT_USER_AGENT,
    parse_headers,
    parse_request_dump,
    format_headers_str,
    detect_category,
    format_bytes,
    format_duration
)
from desktop.engine import (
    DownloadManager,
    DownloadTask,
    TaskStatus,
    probe_url
)

# Set appearance and default color theme
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

# Theme Palette (Midnight Obsidian)
BG_COLOR = "#0D1117"
SURFACE_COLOR = "#161B22"
SURFACE_VARIANT = "#21262D"
BORDER_COLOR = "#30363D"
ACCENT_PRIMARY = "#2563EB"
ACCENT_CYAN = "#06B6D4"
TEXT_PRIMARY = "#F0F6FC"
TEXT_SECONDARY = "#8B949E"
SUCCESS_COLOR = "#10B981"
ERROR_COLOR = "#EF4444"


class AddDownloadDialog(ctk.CTkToplevel):
    """Modern modal dialog for adding and probing a new HTTP/HTTPS download."""

    def __init__(self, parent, on_submit):
        super().__init__(parent)
        self.on_submit = on_submit
        self.title("New Download - OmniDownloader Desktop")
        self.geometry("640x720")
        self.minsize(560, 600)
        self.configure(fg_color=BG_COLOR)

        # Center on parent
        self.transient(parent)
        self.grab_set()

        self.default_dest_dir = os.path.join(os.path.expanduser("~"), "Downloads")
        self.probe_data = None

        self._build_ui()
        self._check_clipboard_for_url()

    def _build_ui(self):
        main_frame = ctk.CTkScrollableFrame(self, fg_color="transparent")
        main_frame.pack(fill="both", expand=True, padx=20, pady=20)

        # Header Title
        title_row = ctk.CTkFrame(main_frame, fg_color="transparent")
        title_row.pack(fill="x", pady=(0, 14))

        title_lbl = ctk.CTkLabel(
            title_row,
            text="⚡ Add HTTP / HTTPS Download",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=TEXT_PRIMARY
        )
        title_lbl.pack(side="left")

        # URL Input Section
        url_lbl = ctk.CTkLabel(main_frame, text="Download Link (HTTP / HTTPS):", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        url_lbl.pack(anchor="w", pady=(0, 4))

        url_row = ctk.CTkFrame(main_frame, fg_color="transparent")
        url_row.pack(fill="x", pady=(0, 10))

        self.url_entry = ctk.CTkEntry(
            url_row,
            placeholder_text="https://example.com/file.zip",
            height=40,
            fg_color=SURFACE_COLOR,
            border_color=BORDER_COLOR,
            text_color=TEXT_PRIMARY
        )
        self.url_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.url_entry.bind("<KeyRelease>", lambda e: self._on_url_typed())

        paste_btn = ctk.CTkButton(
            url_row,
            text="📋 Paste",
            width=80,
            height=40,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self._paste_clipboard
        )
        paste_btn.pack(side="left", padx=(0, 8))

        self.probe_btn = ctk.CTkButton(
            url_row,
            text="🔍 Probe Link",
            width=100,
            height=40,
            fg_color=ACCENT_PRIMARY,
            command=self._start_probe
        )
        self.probe_btn.pack(side="left")

        # Probe Information Card
        self.probe_card = ctk.CTkFrame(main_frame, fg_color=SURFACE_COLOR, border_color=BORDER_COLOR, border_width=1, corner_radius=12)
        self.probe_card.pack(fill="x", pady=(0, 14))

        self.probe_status_lbl = ctk.CTkLabel(
            self.probe_card,
            text="Enter a URL and click 'Probe Link' or fill details below.",
            font=ctk.CTkFont(size=12),
            text_color=TEXT_SECONDARY
        )
        self.probe_status_lbl.pack(padx=14, pady=12, anchor="w")

        # Filename Input
        fn_lbl = ctk.CTkLabel(main_frame, text="Save As (Filename):", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        fn_lbl.pack(anchor="w", pady=(0, 4))

        self.fn_entry = ctk.CTkEntry(
            main_frame,
            placeholder_text="Auto-detected from server or URL",
            height=38,
            fg_color=SURFACE_COLOR,
            border_color=BORDER_COLOR,
            text_color=TEXT_PRIMARY
        )
        self.fn_entry.pack(fill="x", pady=(0, 12))

        # Destination Folder Input
        folder_lbl = ctk.CTkLabel(main_frame, text="Save Folder:", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        folder_lbl.pack(anchor="w", pady=(0, 4))

        folder_row = ctk.CTkFrame(main_frame, fg_color="transparent")
        folder_row.pack(fill="x", pady=(0, 14))

        self.folder_entry = ctk.CTkEntry(
            folder_row,
            height=38,
            fg_color=SURFACE_COLOR,
            border_color=BORDER_COLOR,
            text_color=TEXT_PRIMARY
        )
        self.folder_entry.insert(0, self.default_dest_dir)
        self.folder_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))

        browse_btn = ctk.CTkButton(
            folder_row,
            text="📁 Browse",
            width=90,
            height=38,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self._browse_folder
        )
        browse_btn.pack(side="left")

        # Parallel Connections Slider (1 to 16)
        conn_header_row = ctk.CTkFrame(main_frame, fg_color="transparent")
        conn_header_row.pack(fill="x", pady=(0, 4))

        conn_lbl = ctk.CTkLabel(conn_header_row, text="Accelerated Connections (Threads):", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        conn_lbl.pack(side="left")

        self.conn_value_lbl = ctk.CTkLabel(
            conn_header_row,
            text="16 Connections (Maximum Speed)",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color=ACCENT_CYAN
        )
        self.conn_value_lbl.pack(side="right")

        self.conn_slider = ctk.CTkSlider(
            main_frame,
            from_=1,
            to=16,
            number_of_steps=15,
            command=self._on_slider_change,
            progress_color=ACCENT_CYAN,
            button_color=ACCENT_CYAN
        )
        self.conn_slider.set(16)
        self.conn_slider.pack(fill="x", pady=(0, 14))

        # User-Agent Selector
        ua_lbl = ctk.CTkLabel(main_frame, text="User-Agent:", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        ua_lbl.pack(anchor="w", pady=(0, 4))

        ua_presets_list = list(USER_AGENT_PRESETS.keys()) + ["Custom..."]
        self.ua_dropdown = ctk.CTkOptionMenu(
            main_frame,
            values=ua_presets_list,
            command=self._on_ua_select,
            height=36,
            fg_color=SURFACE_COLOR,
            button_color=SURFACE_VARIANT,
            text_color=TEXT_PRIMARY
        )
        self.ua_dropdown.set("Pixel 9 (Android 15 / Chrome 152) [Default]")
        self.ua_dropdown.pack(fill="x", pady=(0, 6))

        self.ua_custom_entry = ctk.CTkEntry(
            main_frame,
            height=36,
            placeholder_text="Enter custom User-Agent string",
            fg_color=SURFACE_COLOR,
            border_color=BORDER_COLOR,
            text_color=TEXT_PRIMARY
        )
        self.ua_custom_entry.insert(0, DEFAULT_USER_AGENT)
        self.ua_custom_entry.pack(fill="x", pady=(0, 14))

        # Custom HTTP Headers Section
        headers_lbl = ctk.CTkLabel(main_frame, text="Custom HTTP Headers (Optional):", font=ctk.CTkFont(size=13, weight="bold"), text_color=TEXT_PRIMARY)
        headers_lbl.pack(anchor="w", pady=(0, 2))

        headers_hint = ctk.CTkLabel(
            main_frame,
            text="One 'Header: Value' per line or paste raw DevTools request dump. Extracts Cookie, Referer, Tokens automatically.",
            font=ctk.CTkFont(size=11),
            text_color=TEXT_SECONDARY
        )
        headers_hint.pack(anchor="w", pady=(0, 4))

        self.headers_text = ctk.CTkTextbox(
            main_frame,
            height=90,
            fg_color=SURFACE_COLOR,
            border_color=BORDER_COLOR,
            border_width=1,
            text_color=TEXT_PRIMARY
        )
        self.headers_text.pack(fill="x", pady=(0, 14))

        # Action Buttons
        btn_row = ctk.CTkFrame(self, fg_color="transparent")
        btn_row.pack(fill="x", side="bottom", padx=20, pady=(0, 16))

        cancel_btn = ctk.CTkButton(
            btn_row,
            text="Cancel",
            width=100,
            height=40,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self.destroy
        )
        cancel_btn.pack(side="right", padx=(10, 0))

        submit_btn = ctk.CTkButton(
            btn_row,
            text="🚀 Start Download",
            width=160,
            height=40,
            fg_color=ACCENT_PRIMARY,
            command=self._on_submit
        )
        submit_btn.pack(side="right")

    def _parse_and_apply_text(self, text: str):
        text = text.strip()
        if not text:
            return
        extracted_url, extracted_ua, extracted_headers = parse_request_dump(text)
        if extracted_url:
            self.url_entry.delete(0, "end")
            self.url_entry.insert(0, extracted_url)
            self._on_url_typed()

        if extracted_ua:
            self.ua_custom_entry.delete(0, "end")
            self.ua_custom_entry.insert(0, extracted_ua)
            matched = False
            for name, val in USER_AGENT_PRESETS.items():
                if val.strip() == extracted_ua.strip():
                    self.ua_dropdown.set(name)
                    matched = True
                    break
            if not matched:
                self.ua_dropdown.set("Custom...")

        if extracted_headers:
            self.headers_text.delete("1.0", "end")
            self.headers_text.insert("1.0", format_headers_str(extracted_headers))

    def _check_clipboard_for_url(self):
        try:
            clip = self.clipboard_get().strip()
            self._parse_and_apply_text(clip)
        except Exception:
            pass

    def _paste_clipboard(self):
        try:
            clip = self.clipboard_get().strip()
            self._parse_and_apply_text(clip)
        except Exception:
            pass

    def _on_url_typed(self):
        url = self.url_entry.get().strip()
        if url and not self.fn_entry.get().strip():
            # Guess filename from URL
            import urllib.parse
            path = urllib.parse.unquote(urllib.parse.urlparse(url).path)
            base = os.path.basename(path.rstrip("/"))
            if base and "." in base:
                self.fn_entry.delete(0, "end")
                self.fn_entry.insert(0, base)

    def _browse_folder(self):
        chosen = filedialog.askdirectory(initialdir=self.folder_entry.get())
        if chosen:
            self.folder_entry.delete(0, "end")
            self.folder_entry.insert(0, chosen)

    def _on_slider_change(self, val):
        count = int(val)
        speed_tag = "(Maximum Speed)" if count == 16 else "(High Speed)" if count >= 8 else "(Standard)"
        self.conn_value_lbl.configure(text=f"{count} Connection{'s' if count > 1 else ''} {speed_tag}")

    def _on_ua_select(self, choice):
        if choice in USER_AGENT_PRESETS:
            self.ua_custom_entry.delete(0, "end")
            self.ua_custom_entry.insert(0, USER_AGENT_PRESETS[choice])

    def _start_probe(self):
        url = self.url_entry.get().strip()
        if not url:
            messagebox.showwarning("Missing URL", "Please enter a valid HTTP or HTTPS URL first.", parent=self)
            return

        self.probe_btn.configure(state="disabled", text="Probing…")
        self.probe_status_lbl.configure(text="Connecting and reading server metadata…", text_color=ACCENT_CYAN)

        ua = self.ua_custom_entry.get().strip() or DEFAULT_USER_AGENT
        headers = parse_headers(self.headers_text.get("1.0", "end"))

        def probe_thread():
            try:
                res = probe_url(url=url, user_agent=ua, custom_headers=headers)
                self.after(0, lambda: self._apply_probe_result(res))
            except Exception as e:
                self.after(0, lambda: self._apply_probe_error(str(e)))

        threading.Thread(target=probe_thread, daemon=True).start()

    def _apply_probe_result(self, res):
        self.probe_btn.configure(state="normal", text="🔍 Probe Link")
        self.probe_data = res

        if res.filename and not self.fn_entry.get().strip():
            self.fn_entry.delete(0, "end")
            self.fn_entry.insert(0, res.filename)

        size_text = format_bytes(res.file_size) if res.file_size > 0 else "Unknown size"
        range_text = "⚡ Range Supported (Up to 16 Multi-threaded Connections)" if res.accept_ranges else "⚠️ Single-stream only (Range not supported)"
        range_color = SUCCESS_COLOR if res.accept_ranges else "#F59E0B"

        summary = (
            f"📄 Filename: {res.filename}\n"
            f"📦 Size: {size_text} • Type: {res.content_type or 'binary/octet-stream'}\n"
            f"{range_text}"
        )
        self.probe_status_lbl.configure(text=summary, text_color=range_color)

    def _apply_probe_error(self, err_msg):
        self.probe_btn.configure(state="normal", text="🔍 Probe Link")
        self.probe_status_lbl.configure(text=f"Probe failed: {err_msg}", text_color=ERROR_COLOR)

    def _on_submit(self):
        url = self.url_entry.get().strip()
        if not (url.startswith("http://") or url.startswith("https://")):
            messagebox.showerror("Invalid URL", "Only HTTP and HTTPS URLs are supported.", parent=self)
            return

        dest_dir = self.folder_entry.get().strip()
        if not dest_dir:
            messagebox.showerror("Invalid Folder", "Please select a destination save folder.", parent=self)
            return

        filename = self.fn_entry.get().strip()
        connections = int(self.conn_slider.get())
        ua = self.ua_custom_entry.get().strip() or DEFAULT_USER_AGENT
        headers = parse_headers(self.headers_text.get("1.0", "end"))

        self.on_submit(
            url=url,
            dest_dir=dest_dir,
            filename=filename,
            connections=connections,
            user_agent=ua,
            custom_headers=headers
        )
        self.destroy()


class DownloadItemCard(ctk.CTkFrame):
    """Visual download task card with category badge, progress bar, HUD, and actions."""

    def __init__(self, parent, task: DownloadTask, on_pause, on_resume, on_cancel):
        super().__init__(parent, fg_color=SURFACE_COLOR, border_color=BORDER_COLOR, border_width=1, corner_radius=14)
        self.task = task
        self.on_pause = on_pause
        self.on_resume = on_resume
        self.on_cancel = on_cancel

        cat_name, cat_icon, cat_color = detect_category(task.filename, task.content_type)
        self.cat_color = cat_color

        self._build_ui(cat_name, cat_icon, cat_color)

    def _build_ui(self, cat_name: str, cat_icon: str, cat_color: str):
        self.pack(fill="x", padx=16, pady=6)

        content = ctk.CTkFrame(self, fg_color="transparent")
        content.pack(fill="x", padx=16, pady=14)

        # Top row: Icon + Filename + Status Badge
        top_row = ctk.CTkFrame(content, fg_color="transparent")
        top_row.pack(fill="x", pady=(0, 8))

        # Squircle Category Badge
        cat_badge = ctk.CTkLabel(
            top_row,
            text=cat_icon,
            font=ctk.CTkFont(size=20),
            width=42,
            height=42,
            fg_color=SURFACE_VARIANT,
            corner_radius=10
        )
        cat_badge.pack(side="left", padx=(0, 12))

        # Title & Subtitle
        text_col = ctk.CTkFrame(top_row, fg_color="transparent")
        text_col.pack(side="left", fill="x", expand=True)

        self.title_lbl = ctk.CTkLabel(
            text_col,
            text=self.task.filename or "Initializing transfer...",
            font=ctk.CTkFont(size=14, weight="bold"),
            text_color=TEXT_PRIMARY,
            anchor="w"
        )
        self.title_lbl.pack(fill="x")

        self.sub_lbl = ctk.CTkLabel(
            text_col,
            text=f"{cat_name} • {format_bytes(self.task.total_bytes)}",
            font=ctk.CTkFont(size=11),
            text_color=TEXT_SECONDARY,
            anchor="w"
        )
        self.sub_lbl.pack(fill="x")

        # Status Badge Pill
        self.status_pill = ctk.CTkLabel(
            top_row,
            text=self.task.status.value,
            font=ctk.CTkFont(size=11, weight="bold"),
            fg_color=SURFACE_VARIANT,
            text_color=ACCENT_CYAN,
            corner_radius=8,
            padx=10,
            pady=4
        )
        self.status_pill.pack(side="right")

        # Progress Bar
        self.prog_bar = ctk.CTkProgressBar(
            content,
            height=10,
            corner_radius=5,
            progress_color=ACCENT_PRIMARY,
            fg_color=SURFACE_VARIANT
        )
        self.prog_bar.set(self.task.progress_fraction)
        self.prog_bar.pack(fill="x", pady=(0, 6))

        # HUD Row: Progress Bytes, Speed, ETA, Connections
        hud_row = ctk.CTkFrame(content, fg_color="transparent")
        hud_row.pack(fill="x", pady=(0, 10))

        self.bytes_lbl = ctk.CTkLabel(
            hud_row,
            text=f"{format_bytes(self.task.downloaded_bytes)} of {format_bytes(self.task.total_bytes)} ({self.task.progress_percent:.1f}%)",
            font=ctk.CTkFont(size=11),
            text_color=TEXT_SECONDARY
        )
        self.bytes_lbl.pack(side="left")

        self.hud_right_lbl = ctk.CTkLabel(
            hud_row,
            text="",
            font=ctk.CTkFont(size=11, weight="bold"),
            text_color=TEXT_PRIMARY
        )
        self.hud_right_lbl.pack(side="right")

        # Actions Row
        self.act_row = ctk.CTkFrame(content, fg_color="transparent")
        self.act_row.pack(fill="x")

        self.primary_btn = ctk.CTkButton(
            self.act_row,
            text="Pause",
            width=80,
            height=30,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self._on_primary_click
        )
        self.primary_btn.pack(side="right", padx=(8, 0))

        self.secondary_btn = ctk.CTkButton(
            self.act_row,
            text="Cancel",
            width=80,
            height=30,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self._on_secondary_click
        )
        self.secondary_btn.pack(side="right")

        self.update_view()

    def update_view(self):
        """Refresh progress bar, labels, and action buttons according to live task state."""
        self.title_lbl.configure(text=self.task.filename or "Downloading...")
        cat_name, _, _ = detect_category(self.task.filename, self.task.content_type)
        self.sub_lbl.configure(text=f"{cat_name} • {format_bytes(self.task.total_bytes)}")

        self.prog_bar.set(self.task.progress_fraction)
        self.bytes_lbl.configure(
            text=f"{format_bytes(self.task.downloaded_bytes)} of {format_bytes(self.task.total_bytes)} ({self.task.progress_percent:.1f}%)"
        )

        status = self.task.status
        if status == TaskStatus.DOWNLOADING:
            self.status_pill.configure(text="Downloading", text_color=ACCENT_CYAN, fg_color=SURFACE_VARIANT)
            speed_str = f"↓ {format_bytes(int(self.task.speed_bps))}/s"
            eta_str = f"ETA {format_duration(self.task.eta_seconds)}" if self.task.eta_seconds > 0 else ""
            conns_str = f"{self.task.connections} streams"
            self.hud_right_lbl.configure(text=f"{speed_str}  •  {eta_str}  •  {conns_str}")
            self.primary_btn.configure(text="Pause", fg_color=SURFACE_VARIANT, state="normal")
            self.secondary_btn.configure(text="Cancel", fg_color=SURFACE_VARIANT, state="normal")

        elif status == TaskStatus.PAUSED:
            self.status_pill.configure(text="Paused", text_color="#F59E0B", fg_color=SURFACE_VARIANT)
            self.hud_right_lbl.configure(text="Transfer paused")
            self.primary_btn.configure(text="Resume", fg_color=ACCENT_PRIMARY, state="normal")
            self.secondary_btn.configure(text="Delete", fg_color=SURFACE_VARIANT, state="normal")

        elif status == TaskStatus.COMPLETED:
            self.status_pill.configure(text="Completed", text_color=SUCCESS_COLOR, fg_color=SURFACE_VARIANT)
            self.hud_right_lbl.configure(text="File finished")
            self.primary_btn.configure(text="Open", fg_color=ACCENT_PRIMARY, state="normal")
            self.secondary_btn.configure(text="Folder", fg_color=SURFACE_VARIANT, state="normal")

        elif status == TaskStatus.FAILED:
            self.status_pill.configure(text="Failed", text_color=ERROR_COLOR, fg_color=SURFACE_VARIANT)
            self.hud_right_lbl.configure(text=self.task.error_message or "Error occurred")
            self.primary_btn.configure(text="Retry", fg_color=ACCENT_PRIMARY, state="normal")
            self.secondary_btn.configure(text="Delete", fg_color=SURFACE_VARIANT, state="normal")

        elif status == TaskStatus.PROBING:
            self.status_pill.configure(text="Probing", text_color=ACCENT_CYAN, fg_color=SURFACE_VARIANT)
            self.hud_right_lbl.configure(text="Negotiating chunk streams…")

    def _on_primary_click(self):
        if self.task.status == TaskStatus.DOWNLOADING:
            self.on_pause(self.task.id)
        elif self.task.status in (TaskStatus.PAUSED, TaskStatus.FAILED):
            self.on_resume(self.task.id)
        elif self.task.status == TaskStatus.COMPLETED:
            # Open file with default system handler
            try:
                if os.name == "nt":
                    os.startfile(self.task.dest_path)
                else:
                    subprocess.run(["xdg-open", self.task.dest_path], check=False)
            except Exception as e:
                messagebox.showerror("Cannot Open File", str(e), parent=self)

    def _on_secondary_click(self):
        if self.task.status == TaskStatus.COMPLETED:
            # Open containing folder
            try:
                if os.name == "nt":
                    subprocess.run(["explorer", "/select,", os.path.normpath(self.task.dest_path)], check=False)
                else:
                    subprocess.run(["xdg-open", self.task.dest_dir], check=False)
            except Exception as e:
                messagebox.showerror("Cannot Open Folder", str(e), parent=self)
        else:
            self.on_cancel(self.task.id)


class OmniDownloaderApp(ctk.CTk):
    """Main Desktop Application Window."""

    def __init__(self):
        super().__init__()
        self.title("OmniDownloader Desktop Pro")
        self.geometry("900x680")
        self.minsize(720, 500)
        self.configure(fg_color=BG_COLOR)

        self.manager = DownloadManager(max_concurrent_downloads=4)
        self.card_widgets: Dict[str, DownloadItemCard] = {}

        self._build_header()
        self._build_list_container()
        self._build_empty_state()

        # Start periodic GUI refresh loop (5 times a second)
        self._schedule_refresh()

    def _build_header(self):
        header = ctk.CTkFrame(self, fg_color=SURFACE_COLOR, height=72, corner_radius=0)
        header.pack(fill="x", side="top")

        content = ctk.CTkFrame(header, fg_color="transparent")
        content.pack(fill="both", expand=True, padx=20, pady=14)

        # Brand / Logo
        brand_frame = ctk.CTkFrame(content, fg_color="transparent")
        brand_frame.pack(side="left")

        logo_lbl = ctk.CTkLabel(
            brand_frame,
            text="⚡ OmniDownloader",
            font=ctk.CTkFont(size=18, weight="bold"),
            text_color=TEXT_PRIMARY
        )
        logo_lbl.pack(side="left", padx=(0, 8))

        pro_badge = ctk.CTkLabel(
            brand_frame,
            text="DESKTOP PRO",
            font=ctk.CTkFont(size=10, weight="bold"),
            fg_color=ACCENT_PRIMARY,
            text_color="#FFFFFF",
            corner_radius=6,
            padx=6,
            pady=2
        )
        pro_badge.pack(side="left")

        # Action Buttons
        actions_frame = ctk.CTkFrame(content, fg_color="transparent")
        actions_frame.pack(side="right")

        # Global Speed HUD Badge
        self.speed_badge = ctk.CTkLabel(
            actions_frame,
            text="↓ 0.0 KB/s",
            font=ctk.CTkFont(size=12, weight="bold"),
            fg_color=SURFACE_VARIANT,
            text_color=ACCENT_CYAN,
            corner_radius=10,
            padx=12,
            pady=6
        )
        self.speed_badge.pack(side="left", padx=(0, 12))

        pause_all_btn = ctk.CTkButton(
            actions_frame,
            text="⏸ Pause All",
            width=90,
            height=34,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self.manager.pause_all
        )
        pause_all_btn.pack(side="left", padx=(0, 8))

        resume_all_btn = ctk.CTkButton(
            actions_frame,
            text="▶ Resume All",
            width=95,
            height=34,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self.manager.resume_all
        )
        resume_all_btn.pack(side="left", padx=(0, 8))

        clear_btn = ctk.CTkButton(
            actions_frame,
            text="🗑 Clear Done",
            width=95,
            height=34,
            fg_color=SURFACE_VARIANT,
            hover_color=BORDER_COLOR,
            command=self._clear_completed
        )
        clear_btn.pack(side="left", padx=(0, 8))

        new_dl_btn = ctk.CTkButton(
            actions_frame,
            text="+ New Download",
            width=130,
            height=34,
            fg_color=ACCENT_PRIMARY,
            command=self._open_new_download_dialog
        )
        new_dl_btn.pack(side="left")

    def _build_list_container(self):
        self.scroll_frame = ctk.CTkScrollableFrame(self, fg_color="transparent")
        self.scroll_frame.pack(fill="both", expand=True, padx=10, pady=10)

    def _build_empty_state(self):
        self.empty_frame = ctk.CTkFrame(self.scroll_frame, fg_color="transparent")
        self.empty_frame.pack(fill="both", expand=True, pady=100)

        icon_lbl = ctk.CTkLabel(
            self.empty_frame,
            text="⚡",
            font=ctk.CTkFont(size=48),
            text_color=ACCENT_CYAN
        )
        icon_lbl.pack(pady=(0, 10))

        title_lbl = ctk.CTkLabel(
            self.empty_frame,
            text="Ready for High-Speed Transfers",
            font=ctk.CTkFont(size=18, weight="bold"),
            text_color=TEXT_PRIMARY
        )
        title_lbl.pack(pady=(0, 4))

        sub_lbl = ctk.CTkLabel(
            self.empty_frame,
            text="Accelerated multi-connection HTTP and HTTPS downloading with up to 16 threads.",
            font=ctk.CTkFont(size=13),
            text_color=TEXT_SECONDARY
        )
        sub_lbl.pack(pady=(0, 18))

        add_btn = ctk.CTkButton(
            self.empty_frame,
            text="+ Add First Download",
            height=40,
            width=180,
            fg_color=ACCENT_PRIMARY,
            command=self._open_new_download_dialog
        )
        add_btn.pack()

    def _open_new_download_dialog(self):
        AddDownloadDialog(self, on_submit=self._create_download_task)

    def _create_download_task(self, url, dest_dir, filename, connections, user_agent, custom_headers):
        task = DownloadTask(
            url=url,
            dest_dir=dest_dir,
            filename=filename,
            connections=connections,
            user_agent=user_agent,
            custom_headers=custom_headers
        )
        self.manager.add_task(task, auto_start=True)
        self._sync_task_cards()

    def _clear_completed(self):
        self.manager.clear_completed()
        self._sync_task_cards()

    def _sync_task_cards(self):
        # Hide/show empty state
        if not self.manager.tasks:
            self.empty_frame.pack(fill="both", expand=True, pady=100)
        else:
            self.empty_frame.pack_forget()

        # Remove cards for deleted tasks
        active_ids = {t.id for t in self.manager.tasks}
        for task_id in list(self.card_widgets.keys()):
            if task_id not in active_ids:
                card = self.card_widgets.pop(task_id)
                card.destroy()

        # Add or update cards
        for task in self.manager.tasks:
            if task.id not in self.card_widgets:
                card = DownloadItemCard(
                    parent=self.scroll_frame,
                    task=task,
                    on_pause=self.manager.pause_task,
                    on_resume=self.manager.resume_task,
                    on_cancel=lambda tid: self._remove_task_prompt(tid)
                )
                self.card_widgets[task.id] = card
            else:
                self.card_widgets[task.id].update_view()

    def _remove_task_prompt(self, task_id: str):
        task = self.manager.get_task(task_id)
        if not task:
            return
        ans = messagebox.askyesno(
            "Remove Download",
            f"Remove '{task.filename or 'this download'}' from the list?",
            parent=self
        )
        if ans:
            self.manager.remove_task(task_id, delete_file=False)
            self._sync_task_cards()

    def _schedule_refresh(self):
        # Update cards
        for card in self.card_widgets.values():
            card.update_view()

        # Update global speed
        total_speed = self.manager.total_speed_bps
        if total_speed > 0:
            self.speed_badge.configure(
                text=f"↓ {format_bytes(int(total_speed))}/s",
                text_color=ACCENT_CYAN,
                fg_color=SURFACE_VARIANT
            )
        else:
            self.speed_badge.configure(
                text="↓ 0.0 KB/s",
                text_color=TEXT_SECONDARY,
                fg_color=SURFACE_VARIANT
            )

        self.after(200, self._schedule_refresh)
