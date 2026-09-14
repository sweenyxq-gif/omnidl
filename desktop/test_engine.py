"""
Automated Unit Tests for OmniDownloader Desktop Engine & Presets
"""

import os
import shutil
import tempfile
import hashlib
import threading
import unittest
from http.server import HTTPServer, BaseHTTPRequestHandler

from desktop.presets import (
    parse_headers,
    parse_request_dump,
    detect_category,
    format_bytes,
    format_duration,
    sanitize_filename,
    USER_AGENT_PRESETS
)
from desktop.engine import (
    DownloadTask,
    TaskStatus,
    probe_url,
    probe_url,
    extract_filename_from_response
)


class RangeTestHandler(BaseHTTPRequestHandler):
    """Local HTTP handler simulating a server that supports byte ranges (HTTP 206)."""
    test_data = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" * 20000  # ~1.2 MB

    def log_message(self, format, *args):
        pass  # suppress standard log outputs during tests

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(self.test_data)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Disposition", 'attachment; filename="test_sample.dat"')
        self.end_headers()

    def do_GET(self):
        range_header = self.headers.get("Range")
        total_len = len(self.test_data)

        if range_header and range_header.startswith("bytes="):
            rng = range_header[6:].strip()
            parts = rng.split("-")
            start = int(parts[0]) if parts[0] else 0
            end = int(parts[1]) if len(parts) > 1 and parts[1] else (total_len - 1)
            end = min(end, total_len - 1)

            if start > end or start >= total_len:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total_len}")
                self.end_headers()
                return

            chunk = self.test_data[start:end + 1]
            self.send_response(206)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(chunk)))
            self.send_header("Content-Range", f"bytes {start}-{end}/{total_len}")
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            self.wfile.write(chunk)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(total_len))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            self.wfile.write(self.test_data)


class TestPresetsAndParsing(unittest.TestCase):

    def test_parse_headers(self):
        raw = """
        User-Agent: CustomBot/1.0
        Referer: https://example.com/source
        Authorization: Bearer token123
        Cookie: session=abc; uid=42
        # This is a comment
        InvalidLineWithoutColon
        """
        parsed = parse_headers(raw)
        self.assertEqual(parsed["User-Agent"], "CustomBot/1.0")
        self.assertEqual(parsed["Referer"], "https://example.com/source")
        self.assertEqual(parsed["Authorization"], "Bearer token123")
        self.assertEqual(parsed["Cookie"], "session=abc; uid=42")
        self.assertNotIn("InvalidLineWithoutColon", parsed)

    def test_parse_devtools_dump(self):
        raw = """
        Request URL
        https://s26.fileaxa.com/d/chfdl62k2by43rxri24ttcrllslbncefbr5pz7z5caaew34zgpdagjss4wzyp7nqvhzfy4ks/test_file.part4.rar
        Request Method
        GET
        Status Code
        200 OK
        referer
        https://fileaxa.com/
        cookie
        lang=english; cf_clearance=test123456; dl_free=1
        user-agent
        Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36
        """
        url, ua, headers = parse_request_dump(raw)
        self.assertEqual(url, "https://s26.fileaxa.com/d/chfdl62k2by43rxri24ttcrllslbncefbr5pz7z5caaew34zgpdagjss4wzyp7nqvhzfy4ks/test_file.part4.rar")
        self.assertEqual(ua, "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")
        self.assertEqual(headers["referer"], "https://fileaxa.com/")
        self.assertEqual(headers["cookie"], "lang=english; cf_clearance=test123456; dl_free=1")

    def test_detect_category(self):
        cat, icon, color = detect_category("movie.mp4")
        self.assertEqual(cat, "Video")

        cat, icon, color = detect_category("song.flac")
        self.assertEqual(cat, "Audio")

        cat, icon, color = detect_category("archive.7z")
        self.assertEqual(cat, "Archive")

        cat, icon, color = detect_category("setup.exe")
        self.assertEqual(cat, "Executable")

        cat, icon, color = detect_category("document.pdf")
        self.assertEqual(cat, "Document")

    def test_format_bytes(self):
        self.assertEqual(format_bytes(500), "500 B")
        self.assertEqual(format_bytes(1024), "1.0 KB")
        self.assertEqual(format_bytes(1024 * 1024 * 15), "15.00 MB")
        self.assertEqual(format_bytes(1024 * 1024 * 1024 * 2), "2.00 GB")

    def test_format_duration(self):
        self.assertEqual(format_duration(45), "00:45")
        self.assertEqual(format_duration(125), "02:05")
        self.assertEqual(format_duration(3665), "01:01:05")

    def test_sanitize_filename(self):
        bad = 'my:bad<file>name"test|dir?file*.zip'
        clean = sanitize_filename(bad)
        for char in '<>:"/\\|?*':
            self.assertNotIn(char, clean)
        self.assertTrue(clean.endswith(".zip"))


class TestDownloadEngine(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Start local test server
        cls.server = HTTPServer(("127.0.0.1", 0), RangeTestHandler)
        cls.port = cls.server.server_port
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.port}/test_sample.dat"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="omni_test_")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_chunk_partitioning_coverage(self):
        """Verify chunk boundaries for 16 parallel connections perfectly span 0..total_bytes - 1 without gaps."""
        total_size = 10_485_760  # 10 MB
        for conn_count in [1, 2, 4, 8, 16, 32]:
            task = DownloadTask(
                url=self.base_url,
                dest_dir=self.temp_dir,
                filename="partition_test.bin",
                connections=conn_count
            )
            task.total_bytes = total_size
            task._create_chunks()

            self.assertEqual(len(task.chunks), conn_count)
            self.assertEqual(task.chunks[0].start_byte, 0)
            self.assertEqual(task.chunks[-1].end_byte, total_size - 1)

            # Ensure contiguous coverage
            for i in range(len(task.chunks) - 1):
                self.assertEqual(task.chunks[i].end_byte + 1, task.chunks[i + 1].start_byte)

            # Sum of chunk lengths must equal total_bytes
            total_covered = sum(c.end_byte - c.start_byte + 1 for c in task.chunks)
            self.assertEqual(total_covered, total_size)

    def test_probe_url(self):
        """Test URL probe against local Range server."""
        probe = probe_url(self.base_url)
        self.assertEqual(probe.status_code, 200)
        self.assertTrue(probe.accept_ranges)
        self.assertEqual(probe.file_size, len(RangeTestHandler.test_data))
        self.assertEqual(probe.filename, "test_sample.dat")

    def test_multi_connection_download_16_threads(self):
        """Run real 16-connection parallel range download and verify SHA-256 integrity."""
        task = DownloadTask(
            url=self.base_url,
            dest_dir=self.temp_dir,
            filename="downloaded_16.dat",
            connections=16
        )

        completed_event = threading.Event()

        def on_update(t: DownloadTask):
            if t.status in (TaskStatus.COMPLETED, TaskStatus.FAILED):
                completed_event.set()

        task.start_async(on_update=on_update)
        finished = completed_event.wait(timeout=10.0)
        self.assertTrue(finished, "Download timed out")
        self.assertEqual(task.status, TaskStatus.COMPLETED)

        # Verify file exists on disk
        dest_file = os.path.join(self.temp_dir, "downloaded_16.dat")
        self.assertTrue(os.path.exists(dest_file))
        self.assertEqual(os.path.getsize(dest_file), len(RangeTestHandler.test_data))

        # Verify checksum matches original
        expected_sha = hashlib.sha256(RangeTestHandler.test_data).hexdigest()
        with open(dest_file, "rb") as f:
            actual_sha = hashlib.sha256(f.read()).hexdigest()
        self.assertEqual(actual_sha, expected_sha)

    def test_checkpoint_pause_resume(self):
        """Verify checkpoint file serialization and task resume."""
        task = DownloadTask(
            url=self.base_url,
            dest_dir=self.temp_dir,
            filename="resume_test.dat",
            connections=8
        )
        task.total_bytes = 1000
        task._create_chunks()
        task.chunks[0].downloaded = 50
        task.save_checkpoint()

        meta_path = task.meta_path
        self.assertTrue(os.path.exists(meta_path))

        # Create a fresh task instance and load checkpoint
        resumed_task = DownloadTask(
            url=self.base_url,
            dest_dir=self.temp_dir,
            filename="resume_test.dat",
            connections=8
        )
        # Create empty dummy file so checkpoint load validates
        with open(resumed_task.dest_path, "wb") as f:
            f.write(b"\0" * 1000)

        loaded = resumed_task.load_checkpoint()
        self.assertTrue(loaded)
        self.assertEqual(resumed_task.total_bytes, 1000)
        self.assertEqual(resumed_task.chunks[0].downloaded, 50)


if __name__ == "__main__":
    unittest.main()
