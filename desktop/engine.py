"""
OmniDownloader Desktop - High-Performance HTTP/HTTPS Download Engine
Features:
- Segmented range downloading with up to 16+ parallel connections per file
- In-place direct-offset file writing (seek + "r+b"), zero reassembly overhead
- Checkpoint persistence (.omni) for reliable Pause & Resume
- Single-stream fallback for non-range servers
- Custom User-Agent and arbitrary HTTP headers injection
- Live rolling throughput (B/s, KB/s, MB/s) and ETA calculation
"""

import os
import time
import json
import uuid
import logging
import threading
import urllib.parse
import random
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import List, Dict, Optional, Callable, Tuple
import requests

import warnings
warnings.filterwarnings("ignore", message=".*chunk_size.*")
warnings.filterwarnings("ignore", module="curl_cffi")

try:
    from curl_cffi import requests as cffi_requests
    HAS_CURL_CFFI = True
except ImportError:
    import requests as cffi_requests
    HAS_CURL_CFFI = False

def create_http_session():
    """Create session with Chrome TLS fingerprint impersonation if curl_cffi is available."""
    if HAS_CURL_CFFI:
        try:
            return cffi_requests.Session(impersonate="chrome")
        except Exception:
            return requests.Session()
    return requests.Session()

from desktop.presets import (
    DEFAULT_USER_AGENT,
    DEFAULT_CLIENT_HEADERS,
    sanitize_filename,
    format_bytes,
    format_duration
)

logger = logging.getLogger("OmniEngine")


class TaskStatus(str, Enum):
    QUEUED = "QUEUED"
    PROBING = "PROBING"
    DOWNLOADING = "DOWNLOADING"
    PAUSED = "PAUSED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


@dataclass
class ProbeResult:
    url: str
    final_url: str
    filename: str
    file_size: int
    accept_ranges: bool
    content_type: str
    headers: Dict[str, str]
    status_code: int


@dataclass
class ChunkState:
    index: int
    start_byte: int
    end_byte: int
    downloaded: int = 0
    status: str = "queued"  # queued, downloading, completed, failed


def extract_filename_from_response(url: str, resp: requests.Response) -> str:
    """Extract filename from Content-Disposition header or URL path."""
    cd = resp.headers.get("Content-Disposition", "")
    if cd:
        # Check RFC 5987 filename*=UTF-8''filename.ext
        match_star = re_star = None
        import re
        m = re.search(r"filename\*\s*=\s*UTF-8''([^;\s]+)", cd, re.IGNORECASE)
        if m:
            return sanitize_filename(urllib.parse.unquote(m.group(1)))
        m2 = re.search(r'filename\s*=\s*"?([^";\n]+)"?', cd, re.IGNORECASE)
        if m2:
            return sanitize_filename(m2.group(1).strip())

    # Fallback to URL path
    parsed = urllib.parse.urlparse(resp.url or url)
    path = urllib.parse.unquote(parsed.path)
    base = os.path.basename(path.rstrip("/"))
    if base and "." in base:
        return sanitize_filename(base)
    return sanitize_filename(base or "download.bin")


def probe_url(
    url: str,
    user_agent: str = DEFAULT_USER_AGENT,
    custom_headers: Optional[Dict[str, str]] = None,
    timeout: float = 12.0
) -> ProbeResult:
    """
    Probe URL with HEAD and/or byte-range GET to discover:
    - Final redirected URL
    - Total content length
    - Range support (Accept-Ranges or 206 Partial Content)
    - Content-Disposition filename
    - Content-Type
    """
    headers = dict(DEFAULT_CLIENT_HEADERS)
    headers["User-Agent"] = user_agent or DEFAULT_USER_AGENT
    if custom_headers:
        headers.update(custom_headers)

    session = create_http_session()
    file_size = -1
    accept_ranges = False
    final_url = url
    content_type = ""
    status_code = 0
    filename = "download.bin"
    resp_headers = {}

    try:
        # Step 1: Attempt HEAD request
        head_resp = session.head(url, headers=headers, allow_redirects=True, timeout=timeout)
        final_url = head_resp.url
        status_code = head_resp.status_code
        resp_headers = dict(head_resp.headers)
        content_type = head_resp.headers.get("Content-Type", "")
        filename = extract_filename_from_response(url, head_resp)

        cl = head_resp.headers.get("Content-Length")
        if cl and cl.isdigit():
            file_size = int(cl)

        if head_resp.headers.get("Accept-Ranges", "").lower() == "bytes":
            accept_ranges = True

    except Exception as e:
        logger.debug("HEAD probe failed for %s: %s", url, e)

    # Step 2: If HEAD didn't confirm ranges or file size, probe with a 0-0 Range GET
    if not accept_ranges or file_size <= 0:
        try:
            range_headers = dict(headers)
            range_headers["Range"] = "bytes=0-0"
            get_resp = session.get(final_url, headers=range_headers, stream=True, allow_redirects=True, timeout=timeout)
            status_code = get_resp.status_code
            resp_headers.update(get_resp.headers)
            final_url = get_resp.url
            if not content_type:
                content_type = get_resp.headers.get("Content-Type", "")
            if filename == "download.bin":
                filename = extract_filename_from_response(final_url, get_resp)

            if get_resp.status_code == 206:
                accept_ranges = True
                cr = get_resp.headers.get("Content-Range", "")
                # Format: bytes 0-0/123456
                if "/" in cr:
                    total_str = cr.split("/")[-1].strip()
                    if total_str.isdigit():
                        file_size = int(total_str)
            elif get_resp.status_code == 200 and file_size <= 0:
                cl = get_resp.headers.get("Content-Length")
                if cl and cl.isdigit():
                    file_size = int(cl)
            get_resp.close()
        except Exception as e:
            logger.warning("Range GET probe failed for %s: %s", url, e)

    return ProbeResult(
        url=url,
        final_url=final_url,
        filename=filename,
        file_size=file_size,
        accept_ranges=accept_ranges,
        content_type=content_type,
        headers=resp_headers,
        status_code=status_code
    )


class DownloadTask:
    """
    Encapsulates an individual multi-threaded or single-stream download task.
    """

    def __init__(
        self,
        url: str,
        dest_dir: str,
        filename: Optional[str] = None,
        connections: int = 8,
        user_agent: str = DEFAULT_USER_AGENT,
        custom_headers: Optional[Dict[str, str]] = None,
        task_id: Optional[str] = None
    ):
        self.id = task_id or str(uuid.uuid4())
        self.url = url.strip()
        self.dest_dir = os.path.abspath(dest_dir)
        self.filename = filename or ""
        self.connections = max(1, min(connections, 32))
        self.user_agent = user_agent or DEFAULT_USER_AGENT
        self.custom_headers = custom_headers or {}

        self.status = TaskStatus.QUEUED
        self.total_bytes = 0
        self.downloaded_bytes = 0
        self.speed_bps = 0.0
        self.eta_seconds = 0.0
        self.error_message: Optional[str] = None
        self.accept_ranges = False
        self.final_url = self.url
        self.content_type = ""

        self.chunks: List[ChunkState] = []
        self._stop_event = threading.Event()
        self._threads: List[threading.Thread] = []
        self._write_lock = threading.Lock()
        self._speed_samples: List[Tuple[float, int]] = []  # (timestamp, total_downloaded)

    @property
    def dest_path(self) -> str:
        return os.path.join(self.dest_dir, self.filename)

    @property
    def meta_path(self) -> str:
        return f"{self.dest_path}.omni"

    @property
    def progress_fraction(self) -> float:
        if self.total_bytes <= 0:
            return 0.0
        return min(1.0, max(0.0, self.downloaded_bytes / self.total_bytes))

    @property
    def progress_percent(self) -> float:
        return self.progress_fraction * 100.0

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "url": self.url,
            "final_url": self.final_url,
            "dest_dir": self.dest_dir,
            "filename": self.filename,
            "connections": self.connections,
            "user_agent": self.user_agent,
            "custom_headers": self.custom_headers,
            "status": self.status.value,
            "total_bytes": self.total_bytes,
            "downloaded_bytes": self.downloaded_bytes,
            "accept_ranges": self.accept_ranges,
            "content_type": self.content_type,
            "chunks": [asdict(c) for c in self.chunks]
        }

    def save_checkpoint(self):
        """Save progress metadata for resuming."""
        try:
            with open(self.meta_path, "w", encoding="utf-8") as f:
                json.dump(self.to_dict(), f, indent=2)
        except Exception as e:
            logger.debug("Failed to write checkpoint %s: %s", self.meta_path, e)

    def load_checkpoint(self) -> bool:
        """Load progress metadata if available."""
        if not os.path.exists(self.meta_path) or not os.path.exists(self.dest_path):
            return False
        try:
            with open(self.meta_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.total_bytes = data.get("total_bytes", 0)
            self.final_url = data.get("final_url", self.url)
            self.accept_ranges = data.get("accept_ranges", False)
            self.content_type = data.get("content_type", "")
            raw_chunks = data.get("chunks", [])
            self.chunks = [
                ChunkState(
                    index=c["index"],
                    start_byte=c["start_byte"],
                    end_byte=c["end_byte"],
                    downloaded=c.get("downloaded", 0),
                    status=c.get("status", "queued")
                ) for c in raw_chunks
            ]
            self.downloaded_bytes = sum(c.downloaded for c in self.chunks)
            return True
        except Exception as e:
            logger.warning("Corrupt checkpoint %s: %s", self.meta_path, e)
            return False

    def remove_checkpoint(self):
        if os.path.exists(self.meta_path):
            try:
                os.remove(self.meta_path)
            except OSError:
                pass

    def start_async(self, on_update: Optional[Callable[["DownloadTask"], None]] = None):
        """Start downloading in background thread."""
        self._stop_event.clear()
        t = threading.Thread(target=self._run, args=(on_update,), daemon=True)
        t.start()

    def pause(self):
        """Signal all worker threads to stop and set status to PAUSED."""
        if self.status in (TaskStatus.DOWNLOADING, TaskStatus.PROBING):
            self.status = TaskStatus.PAUSED
            self._stop_event.set()
            self.speed_bps = 0.0
            self.save_checkpoint()

    def cancel(self, delete_files: bool = True):
        """Cancel the download task and optionally clean up partial files."""
        self.status = TaskStatus.CANCELLED
        self._stop_event.set()
        self.speed_bps = 0.0
        if delete_files:
            self.remove_checkpoint()
            if os.path.exists(self.dest_path):
                try:
                    os.remove(self.dest_path)
                except OSError:
                    pass

    def _run(self, on_update: Optional[Callable[["DownloadTask"], None]]):
        try:
            # Check if we can resume from checkpoint
            can_resume = self.load_checkpoint()

            if not can_resume:
                self.status = TaskStatus.PROBING
                if on_update:
                    on_update(self)

                probe = probe_url(
                    url=self.url,
                    user_agent=self.user_agent,
                    custom_headers=self.custom_headers
                )
                self.final_url = probe.final_url
                self.total_bytes = probe.file_size
                self.accept_ranges = probe.accept_ranges
                self.content_type = probe.content_type
                if not self.filename:
                    self.filename = probe.filename

                os.makedirs(self.dest_dir, exist_ok=True)

                # Initialize chunks
                if self.accept_ranges and self.total_bytes > 0:
                    self._create_chunks()
                    self._preallocate_file()
                else:
                    self.connections = 1
                    self.chunks = [ChunkState(index=0, start_byte=0, end_byte=max(0, self.total_bytes - 1))]

            self.status = TaskStatus.DOWNLOADING
            self._speed_samples = [(time.time(), self.downloaded_bytes)]
            if on_update:
                on_update(self)

            if self.accept_ranges and self.total_bytes > 0:
                self._download_segmented(on_update)
            else:
                self._download_single_stream(on_update)

            if self._stop_event.is_set():
                if self.status != TaskStatus.CANCELLED:
                    self.status = TaskStatus.PAUSED
                self.speed_bps = 0.0
                self.save_checkpoint()
            else:
                self.status = TaskStatus.COMPLETED
                self.downloaded_bytes = self.total_bytes if self.total_bytes > 0 else self.downloaded_bytes
                self.speed_bps = 0.0
                self.eta_seconds = 0.0
                self.remove_checkpoint()

        except Exception as e:
            if not self._stop_event.is_set():
                logger.exception("Download failed for %s", self.filename)
                self.status = TaskStatus.FAILED
                self.error_message = str(e)
                self.speed_bps = 0.0
                self.save_checkpoint()
        finally:
            if on_update:
                on_update(self)

    def _create_chunks(self):
        """Partition total bytes into equal segments for parallel download."""
        chunk_size = self.total_bytes // self.connections
        self.chunks = []
        for i in range(self.connections):
            start = i * chunk_size
            end = (self.total_bytes - 1) if i == self.connections - 1 else ((i + 1) * chunk_size - 1)
            self.chunks.append(ChunkState(index=i, start_byte=start, end_byte=end, downloaded=0))

    def _preallocate_file(self):
        """Pre-allocate destination file to total size on disk."""
        if not os.path.exists(self.dest_path) or os.path.getsize(self.dest_path) < self.total_bytes:
            with open(self.dest_path, "wb") as f:
                if self.total_bytes > 0:
                    f.seek(self.total_bytes - 1)
                    f.write(b"\0")

    def _download_segmented(self, on_update: Optional[Callable[["DownloadTask"], None]]):
        """Multi-threaded worker pool downloading parts directly to byte offsets with staggered startup and auto-rescue."""
        self._threads = []
        for i, chunk in enumerate(self.chunks):
            if chunk.downloaded < (chunk.end_byte - chunk.start_byte + 1):
                t = threading.Thread(target=self._worker_chunk, args=(chunk, on_update), daemon=True)
                self._threads.append(t)
                t.start()
                # Stagger startup by 150ms to prevent server burst rate-limit (503)
                time.sleep(0.15)

        last_checkpoint = time.time()
        while any(t.is_alive() for t in self._threads):
            if self._stop_event.is_set():
                break
            time.sleep(0.3)
            self._update_speed_metrics()
            if time.time() - last_checkpoint > 3.0:
                self.save_checkpoint()
                last_checkpoint = time.time()
            if on_update:
                on_update(self)

        for t in self._threads:
            t.join()

        # Check if any incomplete chunks remain (e.g. due to server connection caps)
        incomplete = [c for c in self.chunks if c.downloaded < (c.end_byte - c.start_byte + 1)]
        if incomplete and not self._stop_event.is_set():
            logger.info("Resuming %d incomplete chunks in safe queue mode...", len(incomplete))
            for chunk in incomplete:
                if self._stop_event.is_set():
                    break
                self._worker_chunk(chunk, on_update)

    def _worker_chunk(self, chunk: ChunkState, on_update: Optional[Callable[["DownloadTask"], None]]):
        """Worker thread for a single range chunk with backoff retry and Chrome impersonation."""
        chunk.status = "downloading"
        max_retries = 8
        retry_count = 0

        while retry_count < max_retries and not self._stop_event.is_set():
            part_start = chunk.start_byte + chunk.downloaded
            part_end = chunk.end_byte

            if part_start > part_end:
                chunk.status = "completed"
                return

            headers = dict(DEFAULT_CLIENT_HEADERS)
            headers["User-Agent"] = self.user_agent
            headers.update(self.custom_headers)
            headers["Range"] = f"bytes={part_start}-{part_end}"

            session = create_http_session()
            resp = None
            try:
                resp = session.get(self.final_url, headers=headers, stream=True, timeout=25.0)
                if resp.status_code in (500, 502, 503, 504, 429):
                    retry_count += 1
                    backoff = min(6.0, 1.0 * (1.6 ** (retry_count - 1))) + random.uniform(0.2, 0.6)
                    logger.warning(
                        "Chunk %d got HTTP %d (Server Busy/Rate Limit). Retrying (%d/%d) in %.1fs...",
                        chunk.index, resp.status_code, retry_count, max_retries, backoff
                    )
                    time.sleep(backoff)
                    continue

                if resp.status_code not in (200, 206):
                    raise RuntimeError(f"Chunk {chunk.index} received HTTP {resp.status_code}")

                # Reset retry count on successful response
                retry_count = 0

                with open(self.dest_path, "r+b") as f:
                    f.seek(part_start)
                    iter_blocks = resp.iter_content() if HAS_CURL_CFFI else resp.iter_content(chunk_size=131072)
                    for block in iter_blocks:
                        if self._stop_event.is_set():
                            break
                        if block:
                            with self._write_lock:
                                f.seek(chunk.start_byte + chunk.downloaded)
                                f.write(block)
                            chunk.downloaded += len(block)
                            self.downloaded_bytes += len(block)

                if chunk.downloaded >= (chunk.end_byte - chunk.start_byte + 1):
                    chunk.status = "completed"
                    return
                elif self._stop_event.is_set():
                    chunk.status = "paused"
                    return

            except Exception as e:
                retry_count += 1
                if self._stop_event.is_set():
                    chunk.status = "paused"
                    return
                backoff = min(8.0, 1.2 * (1.8 ** (retry_count - 1))) + random.uniform(0.2, 0.5)
                logger.warning(
                    "Chunk %d connection error: %s. Retrying (%d/%d) in %.1fs...",
                    chunk.index, e, retry_count, max_retries, backoff
                )
                time.sleep(backoff)
            finally:
                if resp is not None and hasattr(resp, "close"):
                    try:
                        resp.close()
                    except Exception:
                        pass

        if chunk.downloaded >= (chunk.end_byte - chunk.start_byte + 1):
            chunk.status = "completed"
        else:
            chunk.status = "paused" if self._stop_event.is_set() else "failed"

    def _download_single_stream(self, on_update: Optional[Callable[["DownloadTask"], None]]):
        """Single-stream linear download fallback."""
        headers = dict(DEFAULT_CLIENT_HEADERS)
        headers["User-Agent"] = self.user_agent
        headers.update(self.custom_headers)

        mode = "wb"
        resume_offset = 0
        if os.path.exists(self.dest_path) and self.downloaded_bytes > 0:
            resume_offset = self.downloaded_bytes
            headers["Range"] = f"bytes={resume_offset}-"
            mode = "ab"

        session = create_http_session()
        resp = None
        try:
            resp = session.get(self.final_url, headers=headers, stream=True, timeout=25.0)
            if resp.status_code not in (200, 206):
                raise RuntimeError(f"Server responded with HTTP {resp.status_code}")

            if resp.status_code == 200:
                # Server ignored resume range, start over
                mode = "wb"
                self.downloaded_bytes = 0

            with open(self.dest_path, mode) as f:
                last_checkpoint = time.time()
                iter_blocks = resp.iter_content() if HAS_CURL_CFFI else resp.iter_content(chunk_size=65536)
                for block in iter_blocks:
                    if self._stop_event.is_set():
                        break
                    if block:
                        f.write(block)
                        self.downloaded_bytes += len(block)

                    self._update_speed_metrics()
                    if time.time() - last_checkpoint > 3.0:
                        self.save_checkpoint()
                        last_checkpoint = time.time()
                    if on_update:
                        on_update(self)
        finally:
            if resp is not None and hasattr(resp, "close"):
                try:
                    resp.close()
                except Exception:
                    pass

    def _update_speed_metrics(self):
        """Smooth throughput calculation over rolling time window."""
        now = time.time()
        self._speed_samples.append((now, self.downloaded_bytes))

        # Keep samples within last 2.5 seconds
        self._speed_samples = [s for s in self._speed_samples if now - s[0] <= 2.5]
        if len(self._speed_samples) >= 2:
            dt = self._speed_samples[-1][0] - self._speed_samples[0][0]
            db = self._speed_samples[-1][1] - self._speed_samples[0][1]
            if dt > 0.05:
                self.speed_bps = max(0.0, db / dt)
            else:
                self.speed_bps = 0.0
        else:
            self.speed_bps = 0.0

        # Calculate ETA
        if self.speed_bps > 1024 and self.total_bytes > self.downloaded_bytes:
            remaining_bytes = self.total_bytes - self.downloaded_bytes
            self.eta_seconds = remaining_bytes / self.speed_bps
        else:
            self.eta_seconds = 0.0


class DownloadManager:
    """
    Coordinates queued and active downloads with global speed tracking.
    """

    def __init__(self, max_concurrent_downloads: int = 3):
        self.max_concurrent = max_concurrent_downloads
        self.tasks: List[DownloadTask] = []
        self._lock = threading.Lock()
        self.on_task_update_callbacks: List[Callable[[DownloadTask], None]] = []

    def add_task(self, task: DownloadTask, auto_start: bool = True) -> DownloadTask:
        with self._lock:
            self.tasks.append(task)
        if auto_start:
            self.start_task(task)
        return task

    def start_task(self, task: DownloadTask):
        if task.status in (TaskStatus.QUEUED, TaskStatus.PAUSED, TaskStatus.FAILED):
            task.start_async(on_update=self._notify_update)

    def pause_task(self, task_id: str):
        with self._lock:
            task = self.get_task(task_id)
            if task:
                task.pause()
                self._notify_update(task)

    def resume_task(self, task_id: str):
        with self._lock:
            task = self.get_task(task_id)
            if task:
                self.start_task(task)

    def cancel_task(self, task_id: str, delete_file: bool = False):
        with self._lock:
            task = self.get_task(task_id)
            if task:
                task.cancel(delete_files=delete_file)
                self._notify_update(task)

    def remove_task(self, task_id: str, delete_file: bool = False):
        with self._lock:
            task = self.get_task(task_id)
            if task:
                task.cancel(delete_files=delete_file)
                self.tasks.remove(task)

    def pause_all(self):
        with self._lock:
            for task in self.tasks:
                if task.status == TaskStatus.DOWNLOADING:
                    task.pause()

    def resume_all(self):
        with self._lock:
            for task in self.tasks:
                if task.status in (TaskStatus.PAUSED, TaskStatus.QUEUED):
                    self.start_task(task)

    def clear_completed(self):
        with self._lock:
            self.tasks = [t for t in self.tasks if t.status != TaskStatus.COMPLETED]

    def get_task(self, task_id: str) -> Optional[DownloadTask]:
        for t in self.tasks:
            if t.id == task_id:
                return t
        return None

    @property
    def total_speed_bps(self) -> float:
        with self._lock:
            return sum(t.speed_bps for t in self.tasks if t.status == TaskStatus.DOWNLOADING)

    def _notify_update(self, task: DownloadTask):
        for cb in self.on_task_update_callbacks:
            try:
                cb(task)
            except Exception as e:
                logger.debug("Callback error: %s", e)
