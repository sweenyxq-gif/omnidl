// ==UserScript==
// @name         StreamTape Direct Resolver
// @namespace    https://omni.downloader/resolvers/streamtape
// @version      1.0.0
// @description  Resolves StreamTape video streams directly into playable and downloadable MP4 media streams.
// @match        *://*.streamtape.com/v/*
// @match        *://*.streamtape.com/e/*
// @match        *://*.streamta.pe/v/*
// @match        *://*.streamta.pe/e/*
// @match        *://*.streamtape.to/v/*
// @match        *://*.streamtape.to/e/*
// @match        *://*.streamtape.net/v/*
// @match        *://*.streamtape.net/e/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      streamtape.com
// @connect      streamta.pe
// @connect      streamtape.to
// @connect      streamtape.net
// @connect      *
// @omni-resolver true
// @omni-api     1
// @omni-category video
// ==/UserScript==

(async function() {
    try {
        omni.log("StreamTape resolver triggered: " + targetUrl);

        var headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": targetUrl
        };

        var resp = await omni.fetch(targetUrl, { headers: headers });
        if (resp.status !== 200) {
            omni.log("StreamTape fetch failed with status: " + resp.status);
            return;
        }

        var html = await resp.text();

        // Extract Title
        var title = "streamtape_video";
        var titleMatch = html.match(/<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']/i) ||
                         html.match(/<title>([\s\S]*?)<\/title>/i);
        if (titleMatch && titleMatch[1]) {
            title = titleMatch[1].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").replace(/Watch\s+/i, "").replace(/Streamtape/gi, "").trim();
            if (!title.toLowerCase().endsWith(".mp4") && !title.toLowerCase().endsWith(".mkv")) {
                title += ".mp4";
            }
        }

        // Extract Stream URL from script
        // StreamTape uses: document.getElementById('robotlink' or 'videolink').innerHTML = ...
        var directStreamUrl = null;

        // Pattern 1: document.getElementById('robotlink' or 'videolink').innerHTML = ...
        var scriptMatch = html.match(/document\.getElementById\(['"](?:robotlink|videolink|ideo|link)['"]\)\.innerHTML\s*=\s*([^;]+);/i);
        if (scriptMatch && scriptMatch[1]) {
            try {
                var rawExpr = scriptMatch[1].trim();
                var parts = [];
                var partRegex = /['"]([^'"]+)['"](?:\.substring\((\d+)\))?/g;
                var pm;
                while ((pm = partRegex.exec(rawExpr)) !== null) {
                    var str = pm[1];
                    if (pm[2] !== undefined) {
                        str = str.substring(parseInt(pm[2]));
                    }
                    parts.push(str);
                }
                var joined = parts.join("");
                if (joined) {
                    directStreamUrl = joined.startsWith("//") ? "https:" + joined : joined;
                }
            } catch (evalErr) {
                omni.log("StreamTape eval error: " + evalErr.message);
            }
        }

        // Pattern 2: direct robotlink element contents or get_video link
        if (!directStreamUrl) {
            var directMatch = html.match(/id=["'](?:robotlink|videolink)["'][^>]*>(\/\/[^<]+)<\//i) ||
                              html.match(/['"](\/\/streamtape\.[a-z]+\/get_video\?[^'"]+)['"]/i);
            if (directMatch && directMatch[1]) {
                var rawLink = directMatch[1].trim();
                directStreamUrl = rawLink.startsWith("//") ? "https:" + rawLink : rawLink;
            }
        }

        if (directStreamUrl) {
            omni.resolve({
                url: directStreamUrl,
                label: "StreamTape Direct (MP4 1080p/720p)",
                filename: title,
                size: -1,
                mimeType: "video/mp4",
                type: "HTTP"
            });
            omni.log("StreamTape resolved stream URL: " + directStreamUrl);
        } else {
            omni.log("StreamTape: failed to extract video link from page.");
        }
    } catch (e) {
        omni.log("StreamTape resolver error: " + e.message);
    }
})();
