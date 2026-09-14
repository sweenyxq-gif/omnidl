# OmniDownloader Desktop (Python Edition)

A high-performance desktop download accelerator built in Python, engineered exclusively for **HTTP and HTTPS** transfers with **up to 16 concurrent segmented chunk streams**, custom User-Agents, arbitrary request headers, and an Obsidian-styled modern desktop GUI.

---

## Key Features

- **Multi-Threaded Range Acceleration**:
  - Automatically probes servers for `Accept-Ranges` and `Content-Length`.
  - Splits files into up to **16 parallel chunks** (or up to 32) and downloads them concurrently.
  - **In-Place File Positioning**: Writes blocks directly to specific byte offsets (`seek` + `"r+b"` mode), pre-allocating the file so there is **zero file stitching / merging overhead** when the download finishes.
- **Reliable Pause & Resume**:
  - Checkpoint metadata (`.omni`) stores exact per-chunk progress on disk.
  - Resumes automatically from the last received byte without restarting from scratch.
- **Custom User-Agents & Request Headers**:
  - **Default User-Agent**: `Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36` (Pixel 9 / Chrome 152).
  - Built-in presets for **Pixel 9 (Android 15), Chrome 133, Edge 133, Firefox 135, Safari 18, cURL**, or completely custom strings.
  - **1-Click Browser DevTools Dump Pasting**: Copy headers directly from your browser's Network tab and paste them in OmniDownloader — it automatically extracts the URL, Cookies, Referer, and User-Agent!
  - Includes default Client Hints matching modern mobile Chrome (`sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`, `upgrade-insecure-requests`).
- **Modern Desktop GUI**:
  - Built with `customtkinter` with an Obsidian dark theme.
  - 1-click clipboard paste and link probing.
  - Per-task category badge, live speed readout (`↓ MB/s`), ETA, and connection HUD.
  - Global aggregate throughput indicator.
- **CLI & Scripting Support**:
  - Run high-speed multi-connection downloads directly from PowerShell or Command Prompt.

---

## Installation & Requirements

Ensure you have Python 3.9+ installed:

```bash
pip install -r desktop/requirements.txt
```

---

## Launching the Application

### 1. Graphical Mode (GUI)

```bash
python desktop/main.py
```

### 2. Command Line Interface (CLI)

Accelerate downloads directly from your terminal:

```bash
# Basic 16-connection download
python desktop/main.py --url "https://example.com/largefile.zip" --connections 16

# With custom filename and destination directory
python desktop/main.py --url "https://example.com/video.mp4" -o "my_video.mp4" -d "D:\Downloads"

# With custom User-Agent and Headers (Cookies, Referer, Auth)
python desktop/main.py \
  --url "https://example.com/protected.iso" \
  --connections 16 \
  --user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64)..." \
  -H "Cookie: session_id=xyz123" \
  -H "Referer: https://example.com/downloads"
```

---

## Building a Standalone Windows Executable (.exe)

A one-click batch script is included to generate a standalone Windows executable using PyInstaller:

```cmd
desktop\build_exe.bat
```

The compiled binary will be placed in `dist\OmniDownloader\OmniDownloader.exe`.

---

## Running Unit Tests

To run the automated test suite (verifying 16-connection parallel range downloads, chunk partitioning, and resume integrity against a local test server):

```bash
python -m unittest desktop.test_engine
```
