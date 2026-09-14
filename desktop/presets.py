"""
OmniDownloader Desktop - Presets & Utilities
Common User-Agent strings, header parser, category detection, and byte/time formatters.
"""

import os
import re
from typing import Dict, Tuple, Optional

USER_AGENT_PRESETS: Dict[str, str] = {
    "Pixel 9 (Android 15 / Chrome 152) [Default]": (
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"
    ),
    "Chrome (Windows 11)": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    ),
    "Edge (Windows 11)": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0"
    ),
    "Firefox (Windows)": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) "
        "Gecko/20100101 Firefox/135.0"
    ),
    "Safari (macOS)": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
        "(KHTML, like Gecko) Version/18.3 Safari/605.1.15"
    ),
    "Chrome (Android)": (
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    ),
    "cURL": "curl/8.11.1",
    "OmniDownloader (Default)": (
        "OmniDownloader/3.0.0 (Windows; Multi-threaded Range Engine)"
    ),
}

DEFAULT_USER_AGENT = USER_AGENT_PRESETS["Pixel 9 (Android 15 / Chrome 152) [Default]"]

# Default client headers matching Chrome 152 on Android 15
DEFAULT_CLIENT_HEADERS: Dict[str, str] = {
    "sec-ch-ua": '"Chromium";v="152", "Not?A_Brand";v="24", "Google Chrome";v="152"',
    "sec-ch-ua-mobile": "?1",
    "sec-ch-ua-platform": '"Android"',
    "upgrade-insecure-requests": "1",
    "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "accept-language": "en-US,en;q=0.9",
}

CATEGORIES = {
    "VIDEO": {
        "extensions": {"mp4", "mkv", "avi", "webm", "mov", "flv", "m4v", "wmv", "ts", "m3u8"},
        "icon": "🎬",
        "color": "#818CF8",  # Indigo
        "name": "Video"
    },
    "AUDIO": {
        "extensions": {"mp3", "flac", "aac", "wav", "ogg", "m4a", "opus", "wma"},
        "icon": "🎵",
        "color": "#FBBF24",  # Amber
        "name": "Audio"
    },
    "ARCHIVE": {
        "extensions": {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "dmg"},
        "icon": "📦",
        "color": "#F59E0B",  # Orange
        "name": "Archive"
    },
    "PROGRAM": {
        "extensions": {"exe", "msi", "apk", "appimage", "deb", "rpm", "pkg", "bat", "sh"},
        "icon": "⚙️",
        "color": "#10B981",  # Emerald
        "name": "Executable"
    },
    "DOCUMENT": {
        "extensions": {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub"},
        "icon": "📄",
        "color": "#E11D48",  # Rose
        "name": "Document"
    },
    "OTHER": {
        "extensions": set(),
        "icon": "📁",
        "color": "#38BDF8",  # Sky Cyan
        "name": "File"
    }
}


def parse_headers(text: str) -> Dict[str, str]:
    """
    Parse a multiline string of HTTP headers into a dictionary.
    Supports:
      - Standard 'Header-Name: Header-Value'
      - Alternating DevTools format ('header-name' line followed by 'header-value' line)
      - Strips HTTP/2 pseudo-headers (:authority, :path, etc.)
    """
    _, ua, headers = parse_request_dump(text)
    if ua and "User-Agent" not in headers and "user-agent" not in headers:
        headers["User-Agent"] = ua
    return headers


def parse_request_dump(text: str) -> Tuple[Optional[str], Optional[str], Dict[str, str]]:
    """
    Intelligently parse raw browser DevTools network dumps, curl headers, or multiline headers.
    Returns: (extracted_url, extracted_user_agent, headers_dict)
    """
    if not text:
        return None, None, {}

    extracted_url: Optional[str] = None
    extracted_ua: Optional[str] = None
    headers: Dict[str, str] = {}

    lines = [line.strip() for line in text.splitlines()]
    lines = [l for l in lines if l and not l.startswith("#")]

    # Common response / metadata headers to filter out if a full DevTools dump is pasted
    METADATA_IGNORE = {
        "request method", "status code", "remote address", "referrer policy",
        "alt-svc", "cf-cache-status", "cf-ray", "content-disposition", "content-length",
        "content-type", "date", "etag", "last-modified", "nel", "priority", "report-to",
        "server", "server-timing", "speculation-rules", ":method", ":path", ":scheme",
        ":authority", ":status"
    }

    i = 0
    while i < len(lines):
        line = lines[i]

        # Check for URL
        if re.match(r"^Request URL:?\s*(https?://\S+)", line, re.IGNORECASE):
            m = re.match(r"^Request URL:?\s*(https?://\S+)", line, re.IGNORECASE)
            extracted_url = m.group(1).strip()
            i += 1
            continue
        elif line.lower() == "request url" and i + 1 < len(lines) and lines[i + 1].startswith("http"):
            extracted_url = lines[i + 1].strip()
            i += 2
            continue
        elif not extracted_url and (line.startswith("http://") or line.startswith("https://")):
            extracted_url = line.strip()
            i += 1
            continue

        # Check for standard Key: Value
        if ":" in line and not line.startswith(":"):
            key, val = line.split(":", 1)
            k = key.strip()
            v = val.strip()
            if k.lower() not in METADATA_IGNORE and k:
                if k.lower() == "user-agent":
                    extracted_ua = v
                else:
                    headers[k] = v
            i += 1
            continue

        # Check for HTTP/2 pseudo-header with colon (e.g. :authority: s26.fileaxa.com or :authority\n...)
        if line.startswith(":"):
            if ":" in line[1:]:
                # e.g. :authority: val
                parts = line[1:].split(":", 1)
                k = ":" + parts[0].strip()
                # ignore pseudo headers
            i += 1
            continue

        # Check for alternating DevTools format (Line 1: header-name, Line 2: header-value)
        k_candidate = line.lower().strip(":")
        if k_candidate not in METADATA_IGNORE and re.match(r"^[a-zA-Z0-9_\-]+$", k_candidate) and i + 1 < len(lines):
            val_candidate = lines[i + 1].strip()
            if not val_candidate.startswith("http://") and not val_candidate.startswith("https://") or k_candidate in ("referer", "origin"):
                if k_candidate == "user-agent":
                    extracted_ua = val_candidate
                elif k_candidate not in ("request url", "request method"):
                    headers[k_candidate] = val_candidate
                i += 2
                continue

        i += 1

    return extracted_url, extracted_ua, headers


def format_headers_str(headers: Dict[str, str]) -> str:
    """Format dictionary headers back to a multiline string."""
    return "\n".join(f"{k}: {v}" for k, v in headers.items())


def detect_category(filename: str, content_type: str = "") -> Tuple[str, str, str]:
    """
    Detect category based on filename extension and MIME type.
    Returns: (category_name, icon, color)
    """
    ext = os.path.splitext(filename)[1].lstrip(".").lower()
    content_type = (content_type or "").lower()

    if content_type.startswith("video/"):
        c = CATEGORIES["VIDEO"]
        return c["name"], c["icon"], c["color"]
    if content_type.startswith("audio/"):
        c = CATEGORIES["AUDIO"]
        return c["name"], c["icon"], c["color"]

    for cat_key, info in CATEGORIES.items():
        if ext in info["extensions"]:
            return info["name"], info["icon"], info["color"]

    c = CATEGORIES["OTHER"]
    return c["name"], c["icon"], c["color"]


def format_bytes(num_bytes: int) -> str:
    """Format bytes to human-readable string (KB, MB, GB, TB)."""
    if num_bytes < 0:
        return "Unknown"
    units = ["B", "KB", "MB", "GB", "TB", "PB"]
    value = float(num_bytes)
    for unit in units:
        if value < 1024.0 or unit == units[-1]:
            if unit in ("B", "KB"):
                return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
            return f"{value:.2f} {unit}"
        value /= 1024.0
    return f"{num_bytes} B"


def format_duration(seconds: float) -> str:
    """Format seconds into HH:MM:SS or MM:SS."""
    if seconds <= 0:
        return "--:--"
    sec = int(seconds)
    hours = sec // 3600
    minutes = (sec % 3600) // 60
    secs = sec % 60
    if hours > 0:
        return f"{hours:02d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def sanitize_filename(filename: str) -> str:
    """Sanitize a filename by removing or replacing illegal characters for Windows/Linux/macOS."""
    if not filename:
        return "download.bin"
    # Remove null bytes and control chars
    clean = re.sub(r'[\x00-\x1f\x7f]', '', filename)
    # Remove illegal filename characters: < > : " / \ | ? *
    clean = re.sub(r'[<>:"/\\|?*]', '_', clean).strip()
    return clean or "download.bin"
