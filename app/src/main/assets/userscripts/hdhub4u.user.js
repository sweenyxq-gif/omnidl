// ==UserScript==
// @name         HDHub4u Post Resolver
// @namespace    https://omni.downloader/resolvers/hdhub4u
// @version      1.0.0
// @description  Parses HDHub4u movie and TV series pages, extracting organized 4K, 1080p, 720p, 480p, and episode download streams.
// @match        *://*.hdhub4u.*/*
// @match        *://*.hdhub4u.cl/*
// @match        *://*.hdhub4u.in/*
// @match        *://*.hdhub4u.movie/*
// @match        *://*.hdhub4u.tv/*
// @match        *://*.hdhub4u.lat/*
// @match        *://*.hdhub4u.fans/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      hdhub4u.cl
// @connect      hdhub4u.in
// @connect      hdhub4u.movie
// @connect      hdhub4u.tv
// @connect      hdhub4u.lat
// @connect      hdhub4u.fans
// @connect      *
// @omni-resolver true
// @omni-api     1
// @omni-category video
// ==/UserScript==

(async function() {
    try {
        omni.log("HDHub4u post resolver triggered: " + targetUrl);

        var headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": targetUrl
        };

        var resp = await omni.fetch(targetUrl, { headers: headers });
        if (resp.status !== 200) {
            omni.log("HDHub4u fetch failed with status: " + resp.status);
            return;
        }

        var html = await resp.text();

        // 1. Post Title
        var postTitle = "hdhub4u_media";
        var titleMatch = html.match(/<h1[^>]*class=["'][^"']*page-title[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i) ||
                         html.match(/<h1[^>]*class=["'][^"']*entry-title[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i) ||
                         html.match(/<title>([\s\S]*?)<\/title>/i);
        if (titleMatch && titleMatch[1]) {
            postTitle = titleMatch[1].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").replace(/Download\s+/i, "").replace(/\|.*/, "").trim();
        }

        omni.log("HDHub4u parsed post title: " + postTitle);

        function parseSize(str) {
            if (!str) return -1;
            var m = str.match(/([\d.]+)\s*(GB|MB|KB)/i);
            if (!m) return -1;
            var val = parseFloat(m[1]);
            var unit = m[2].toUpperCase();
            if (unit === "GB") return Math.round(val * 1024 * 1024 * 1024);
            if (unit === "MB") return Math.round(val * 1024 * 1024);
            if (unit === "KB") return Math.round(val * 1024);
            return Math.round(val);
        }

        // 2. Scrape download link blocks (h3/h4/h5/p sections and links)
        // HDHub4u standard structure: Headings with resolution/size followed by button links
        var anchorRegex = /<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
        var links = [];
        var m;
        while ((m = anchorRegex.exec(html)) !== null) {
            var href = m[1].trim();
            var text = m[2].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
            if (href && href.startsWith("http") && !href.includes("facebook.com") && !href.includes("twitter.com") && !href.includes("t.me")) {
                links.push({ href: href, text: text, index: m.index });
            }
        }

        var downloadLinks = [];
        for (var i = 0; i < links.length; i++) {
            var item = links[i];
            var href = item.href;
            var text = item.text.toLowerCase();

            // Detect download hosts or button markers
            var isDlHost = href.includes("hubcloud") || href.includes("hblinks") || href.includes("gadgetsweb") ||
                           href.includes("cryptoinsights") || href.includes("techyboy") || href.includes("hdstream") ||
                           href.includes("drive") || href.includes("pixeldrain") || href.includes("streamtape");
            var isDlText = text.includes("download") || text.includes("link") || text.includes("watch") ||
                           text.includes("480p") || text.includes("720p") || text.includes("1080p") || text.includes("2160p") ||
                           text.includes("4k") || text.includes("episode") || text.includes("zip");

            if (isDlHost || (isDlText && !href.includes(new URL(targetUrl).hostname))) {
                // Find context preceding the link (within 300 chars) to get resolution / size
                var start = Math.max(0, item.index - 350);
                var context = html.substring(start, item.index);
                var quality = "720p";
                if (/2160p|4k/i.test(context) || /2160p|4k/i.test(text)) quality = "4K 2160p";
                else if (/1080p/i.test(context) || /1080p/i.test(text)) quality = "1080p";
                else if (/720p/i.test(context) || /720p/i.test(text)) quality = "720p";
                else if (/480p/i.test(context) || /480p/i.test(text)) quality = "480p";

                var sizeBytes = -1;
                var sMatch = context.match(/([\d.]+)\s*(GB|MB)/i) || text.match(/([\d.]+)\s*(GB|MB)/i);
                if (sMatch) sizeBytes = parseSize(sMatch[0]);

                // Episode check
                var epMatch = text.match(/(?:Episode|EP|E)\s*(\d+)/i) || context.match(/(?:Episode|EP|E)\s*(\d+)/i);
                var epLabel = epMatch ? " (Ep " + epMatch[1] + ")" : "";

                var label = postTitle + " [" + quality + "]" + epLabel;
                if (item.text && item.text.length > 3 && !text.includes("download") && !text.includes("click")) {
                    label = postTitle + " [" + quality + "] - " + item.text;
                }

                downloadLinks.push({
                    url: href,
                    label: label,
                    filename: postTitle + "_" + quality.replace(/\s+/g, "_") + (epMatch ? "_E" + epMatch[1] : "") + ".mp4",
                    size: sizeBytes
                });
            }
        }

        // Deduplicate URLs
        var seen = new Set();
        var unique = [];
        for (var j = 0; j < downloadLinks.length; j++) {
            var dl = downloadLinks[j];
            if (!seen.has(dl.url)) {
                seen.add(dl.url);
                unique.push(dl);
            }
        }

        omni.log("HDHub4u identified " + unique.length + " download options.");

        for (var k = 0; k < unique.length; k++) {
            var u = unique[k];
            omni.resolve({
                url: u.url,
                label: u.label,
                filename: u.filename,
                size: u.size,
                mimeType: "video/mp4",
                type: "HTTP"
            });
        }
    } catch (err) {
        omni.log("HDHub4u resolver error: " + err.message);
    }
})();
