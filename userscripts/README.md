# OmniDownloader Userscript Resolver Extensions

OmniDownloader includes an isolated, security-sandboxed JavaScript runtime engine capable of executing **Userscripts** (Tampermonkey & Greasemonkey compatible `.user.js` files) to extract, resolve, and inspect direct download links from file hosts, video sites, model repositories, and developer platforms.

---

## 📦 Curated Resolvers Included

| Userscript | Namespace | Target Hosts | Categories | Description |
| :--- | :--- | :--- | :--- | :--- |
| [`pixeldrain.user.js`](pixeldrain.user.js) | `resolvers/pixeldrain` | `pixeldrain.com` | Filehost | Resolves direct high-speed streams with full metadata and filenames. |
| [`mediafire.user.js`](mediafire.user.js) | `resolvers/mediafire` | `mediafire.com` | Filehost | Bypasses landing page interstitial to extract direct file download button URLs. |
| [`huggingface.user.js`](huggingface.user.js) | `resolvers/huggingface` | `huggingface.co` | AI Models | Resolves AI model weights (`.safetensors`, `.gguf`, `.bin`) and datasets. |
| [`civitai.user.js`](civitai.user.js) | `resolvers/civitai` | `civitai.com` | AI Models | Queries model versions and resolves Checkpoints, LoRAs, and VAEs. |
| [`github-releases.user.js`](github-releases.user.js) | `resolvers/github` | `github.com` | Development | Resolves direct binary releases (APKs, executables, archives) from release pages. |
| [`buzzheavier.user.js`](buzzheavier.user.js) | `resolvers/buzzheavier` | `buzzheavier.com` | Filehost | Fast stream resolver for Buzzheavier file shares. |
| [`gitlab-releases.user.js`](gitlab-releases.user.js) | `resolvers/gitlab` | `gitlab.com` | Development | Resolves downloadable release assets published on GitLab. |
| [`internet-archive.user.js`](internet-archive.user.js) | `resolvers/archive-org` | `archive.org` | Archives | Discovers public files, formats, and sizes from Archive.org items. |
| [`sourceforge.user.js`](sourceforge.user.js) | `resolvers/sourceforge` | `sourceforge.net` | Development | Discovers official project download mirrors. |
| [`universal-media-sniffer.user.js`](universal-media-sniffer.user.js) | `resolvers/media-sniffer` | `<all_urls>` | Media Streams | Sniffs HTML5 `<video>`, `<audio>`, `.m3u8` HLS, and `.mpd` DASH manifests. |

---

## 🚀 How to Install

### 1. In the OmniDownloader Android App
1. Open **OmniDownloader**.
2. Tap the **Extensions** tab at the bottom.
3. Choose any method:
   - **Catalog Tab**: Browse the built-in curated resolvers and tap **[Install]**.
   - **Install from URL**: Tap the download icon in the top-right corner and paste a raw `.user.js` URL.
   - **Import Code**: Tap **[+ Add Script]** and paste the `.user.js` source code directly into the editor.
4. Review the requested permissions and network domains, then confirm **Install**.

### 2. In Desktop Browsers (Tampermonkey / Violentmonkey)
All scripts conform to standard Greasemonkey/Tampermonkey metadata headers (`@match`, `@grant`, `@connect`, `@version`), making them compatible with browser extensions like Violentmonkey or Tampermonkey.

---

## 🛠️ Writing Custom Userscript Resolvers

Every OmniDownloader userscript must declare a standard metadata block:

```javascript
// ==UserScript==
// @name         My Custom Resolver
// @namespace    https://omni.downloader/resolvers/mycustom
// @version      1.0.0
// @description  Resolves direct download links for example.com
// @match        https://example.com/download/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      example.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// ==/UserScript==
```

### Omni Runtime API

Inside the script execution environment, the global `omni` object is injected:

#### 1. `omni.fetch(url, options)`
Safe HTTP request through OmniDownloader's OkHttp network sandbox.
```javascript
var response = await omni.fetch("https://api.example.com/item/123", {
    method: "GET", // GET, POST, HEAD
    headers: { "Authorization": "Bearer token" },
    body: null
});
var json = await response.json();
var text = await response.text();
```

#### 2. `omni.resolve(item)`
Emits a resolved downloadable item to OmniDownloader's transfer queue or inspector.
```javascript
omni.resolve({
    url: "https://cdn.example.com/files/archive.zip",
    label: "Archive (1.2 GB)",
    filename: "archive.zip",
    size: 1288490188,         // Total size in bytes (-1 if unknown)
    mimeType: "application/zip",
    quality: "1080p",         // Optional quality descriptor
    type: "HTTP",             // "HTTP", "HLS", "DASH", "MAGNET", "FTP", "SFTP"
    headers: {                // Optional custom request headers (cookies, auth)
        "Referer": "https://example.com/",
        "Cookie": "session=xyz"
    }
});
```

#### 3. `omni.log(message)`
Outputs debug logging visible in the in-app Playground and Logcat.
```javascript
omni.log("Found 3 downloadable assets");
```

#### 4. `omni.storage.get(key, defaultValue)` & `omni.storage.set(key, value)`
Persistent per-script key-value storage.
```javascript
omni.storage.set("preferred_quality", "1080p");
var quality = omni.storage.get("preferred_quality", "720p");
```

---

## 🔒 Security Sandbox

- **Isolated Runtime**: Executed in a secure, isolated WebView environment with DOM access disabled and local file access blocked.
- **Domain Whitelisting**: Network access is restricted strictly to hosts declared in `@connect` and `@match` tags. Private IP ranges (e.g. `localhost`, `127.0.0.1`, `192.168.x.x`, `10.x.x.x`) are rejected.
- **Permission Auditing**: When installing or updating a script, OmniDownloader prompts users to review the required URL rules and network access domains.
