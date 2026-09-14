"""
OmniDownloader Desktop - Main Application Entry Point
Supports both full graphical desktop mode (GUI) and accelerated CLI download mode.
"""

import sys
import os
import argparse
import time

# Ensure project root is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PARENT_DIR = os.path.dirname(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)
if PARENT_DIR not in sys.path:
    sys.path.insert(0, PARENT_DIR)

# Reconfigure stdout for UTF-8 on Windows
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from desktop.presets import (
    USER_AGENT_PRESETS,
    DEFAULT_USER_AGENT,
    parse_headers,
    format_bytes,
    format_duration
)
from desktop.engine import (
    DownloadTask,
    TaskStatus,
    probe_url
)


def run_cli_download(args):
    """CLI accelerated multi-connection download."""
    url = args.url.strip()
    if not (url.startswith("http://") or url.startswith("https://")):
        print(f"[!] Error: Only HTTP and HTTPS URLs are supported. Got: {url}")
        sys.exit(1)

    ua = args.user_agent or DEFAULT_USER_AGENT
    headers = {}
    if args.header:
        for h in args.header:
            headers.update(parse_headers(h))

    dest_dir = os.path.abspath(args.dir or os.getcwd())
    conns = max(1, min(args.connections, 32))

    print("=" * 60)
    print(f"[+] OmniDownloader CLI Accelerator (v3.0)")
    print(f"[+] URL:         {url}")
    print(f"[+] Connections: {conns} parallel chunk streams")
    print(f"[+] Destination: {dest_dir}")
    print("=" * 60)

    print("[*] Probing server metadata and range support...")
    probe = probe_url(url=url, user_agent=ua, custom_headers=headers)

    filename = args.output or probe.filename
    size_str = format_bytes(probe.file_size) if probe.file_size > 0 else "Unknown size"
    range_str = f"YES (Accelerated {conns}x Range Streams)" if probe.accept_ranges else "NO (Single Stream)"

    print(f"[+] Filename:       {filename}")
    print(f"[+] Content-Length: {size_str}")
    print(f"[+] Range Support:  {range_str}")
    print("-" * 60)

    task = DownloadTask(
        url=url,
        dest_dir=dest_dir,
        filename=filename,
        connections=conns,
        user_agent=ua,
        custom_headers=headers
    )

    import threading
    done_event = threading.Event()

    def on_update(t: DownloadTask):
        if t.status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED):
            done_event.set()

    task.start_async(on_update=on_update)

    last_line_len = 0
    try:
        while not done_event.is_set():
            time.sleep(0.3)
            percent = task.progress_percent
            speed = format_bytes(int(task.speed_bps)) + "/s"
            downloaded = format_bytes(task.downloaded_bytes)
            total = format_bytes(task.total_bytes) if task.total_bytes > 0 else "???"
            eta = f"ETA {format_duration(task.eta_seconds)}" if task.eta_seconds > 0 else ""

            # Render terminal progress bar
            bar_len = 25
            filled = int(bar_len * (percent / 100.0))
            bar = "█" * filled + "░" * (bar_len - filled)

            status_line = f"\r[{bar}] {percent:5.1f}% | {downloaded}/{total} | ↓ {speed:12} | {eta}"
            sys.stdout.write(status_line.ljust(last_line_len))
            sys.stdout.flush()
            last_line_len = len(status_line)

    except KeyboardInterrupt:
        print("\n[!] User paused download.")
        task.pause()
        sys.exit(0)

    print()
    if task.status == TaskStatus.COMPLETED:
        print("=" * 60)
        print(f"[+] Download Complete: {task.dest_path}")
        print(f"[+] Total Size:        {format_bytes(os.path.getsize(task.dest_path))}")
        print("=" * 60)
    else:
        print(f"[!] Download Failed: {task.error_message}")
        sys.exit(1)


def run_gui():
    """Launch full modern graphical interface."""
    from desktop.gui import OmniDownloaderApp
    app = OmniDownloaderApp()
    app.mainloop()


def main():
    parser = argparse.ArgumentParser(description="OmniDownloader Desktop - Accelerated Multi-Connection HTTP/HTTPS Downloader")
    parser.add_argument("--url", "-u", type=str, help="Download URL (starts download in CLI mode)")
    parser.add_argument("--connections", "-c", type=int, default=16, help="Number of parallel chunk connections (default: 16)")
    parser.add_argument("--output", "-o", type=str, help="Destination filename")
    parser.add_argument("--dir", "-d", type=str, help="Destination directory (default: current directory)")
    parser.add_argument("--user-agent", "-a", type=str, help="Custom User-Agent string")
    parser.add_argument("--header", "-H", action="append", help="Custom HTTP header, e.g. -H 'Referer: https://...' -H 'Cookie: ...'")

    args = parser.parse_args()

    if args.url:
        run_cli_download(args)
    else:
        run_gui()


if __name__ == "__main__":
    main()
