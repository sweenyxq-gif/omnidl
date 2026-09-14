// ==UserScript==
// @name         Universal Media & Stream Sniffer
// @namespace    https://omni.downloader/resolvers/media-sniffer
// @version      1.0.0
// @description  Sniffs HTML5 video, audio, and embedded HLS (.m3u8) / DASH (.mpd) / MP4 streams from any webpage.
// @match        *://*/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      *
// @omni-resolver true
// @omni-api     1
// @omni-category media
// ==/UserScript==

(async function() {
    try {
        omni.log("Media Sniffer scanning: " + targetUrl);
        var response = await omni.fetch(targetUrl);
        var html = await response.text();

        var foundCount = 0;
        var seenUrls = {};

        function addMedia(url, label, type) {
            if (!url || seenUrls[url]) return;
            var cleanUrl = url.trim();
            if (cleanUrl.indexOf("//") === 0) {
                cleanUrl = "https:" + cleanUrl;
            } else if (cleanUrl.indexOf("http") !== 0) {
                var baseUri = targetUrl.substring(0, targetUrl.lastIndexOf("/") + 1);
                cleanUrl = baseUri + cleanUrl;
            }

            seenUrls[cleanUrl] = true;
            var filename = cleanUrl.split("?")[0].split("/").pop() || "media_stream";

            omni.resolve({
                url: cleanUrl,
                label: label + ": " + filename,
                filename: filename,
                type: type || "HTTP"
            });
            foundCount++;
        }

        // 1. Sniff HLS m3u8 playlists
        var m3u8Regex = /["'](https?:[^\s"']+\.m3u8[^\s"']*)["']/gi;
        var m3u8Match;
        while ((m3u8Match = m3u8Regex.exec(html)) !== null) {
            addMedia(m3u8Match[1], "HLS Stream (M3U8)", "HLS");
        }

        // 2. Sniff DASH mpd manifests
        var mpdRegex = /["'](https?:[^\s"']+\.mpd[^\s"']*)["']/gi;
        var mpdMatch;
        while ((mpdMatch = mpdRegex.exec(html)) !== null) {
            addMedia(mpdMatch[1], "DASH Stream (MPD)", "DASH");
        }

        // 3. Sniff video sources <video ... src="..."> or <source ... src="...">
        var videoSrcRegex = /<(?:video|source)[^>]+src=["']([^"']+\.(?:mp4|webm|mkv|mov)[^"']*)["']/gi;
        var vMatch;
        while ((vMatch = videoSrcRegex.exec(html)) !== null) {
            addMedia(vMatch[1], "Video Stream", "HTTP");
        }

        // 4. Sniff audio sources <audio ... src="..."> or .mp3, .m4a, .aac, .flac
        var audioSrcRegex = /<(?:audio|source)[^>]+src=["']([^"']+\.(?:mp3|m4a|aac|flac|ogg|wav)[^"']*)["']/gi;
        var aMatch;
        while ((aMatch = audioSrcRegex.exec(html)) !== null) {
            addMedia(aMatch[1], "Audio Stream", "HTTP");
        }

        omni.log("Media Sniffer identified " + foundCount + " stream(s) on page.");
    } catch (e) {
        omni.log("Media Sniffer error: " + e.message);
    }
})();
