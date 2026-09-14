// ==UserScript==
// @name         MediaFire Direct Resolver
// @namespace    https://omni.downloader/resolvers/mediafire
// @version      1.0.0
// @description  Extracts direct download URLs and file metadata from MediaFire file pages.
// @match        https://www.mediafire.com/file/*
// @match        https://mediafire.com/file/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      mediafire.com
// @connect      download*.mediafire.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// ==/UserScript==

(async function() {
    try {
        omni.log("MediaFire resolver matched: " + targetUrl);
        var response = await omni.fetch(targetUrl);
        var html = await response.text();

        // 1. Look for download button anchor tag
        var directUrl = null;
        var btnMatch = /<a[^>]+id=["']downloadButton["'][^>]+href=["']([^"']+)["']/i.exec(html);
        if (btnMatch && btnMatch[1]) {
            directUrl = btnMatch[1];
        }

        // Fallback: aria-label="Download file" or class="popsok"
        if (!directUrl) {
            var altMatch = /href=["'](https?:\/\/[^"']+\.mediafire\.com\/[^"']+)["'][^>]*id=["']downloadButton["']/i.exec(html);
            if (altMatch && altMatch[1]) {
                directUrl = altMatch[1];
            }
        }

        if (!directUrl) {
            // Check for direct download URL patterns inside script tags
            var scriptMatch = /["'](https?:\/\/[a-z0-9]+\.mediafire\.com\/[^"']+)["']/i.exec(html);
            if (scriptMatch && scriptMatch[1] && scriptMatch[1].indexOf("/file/") === -1) {
                directUrl = scriptMatch[1];
            }
        }

        // Extract filename from HTML
        var filename = "download";
        var nameMatch = /<div class=["']filename["'][^>]*>([^<]+)<\/div>/i.exec(html);
        if (nameMatch && nameMatch[1]) {
            filename = nameMatch[1].trim();
        } else {
            var titleMatch = /<meta property=["']og:title["'] content=["']([^"']+)["']/i.exec(html);
            if (titleMatch && titleMatch[1]) {
                filename = titleMatch[1].trim();
            } else {
                var urlParts = targetUrl.split("/file/")[1].split("/")[1];
                if (urlParts) filename = decodeURIComponent(urlParts.split("?")[0]);
            }
        }

        // Extract size if available
        var size = -1;
        var sizeMatch = /\(([0-9.]+\s*(?:KB|MB|GB|Bytes))\)/i.exec(html);
        var sizeLabel = sizeMatch ? sizeMatch[1] : "";

        if (directUrl) {
            omni.log("Found direct MediaFire download URL: " + directUrl);
            omni.resolve({
                url: directUrl,
                label: "MediaFire: " + filename + (sizeLabel ? " (" + sizeLabel + ")" : ""),
                filename: filename,
                size: size,
                type: "HTTP"
            });
        } else {
            omni.log("Could not locate direct download URL on MediaFire landing page.");
        }
    } catch (e) {
        omni.log("MediaFire resolver error: " + e.message);
    }
})();
