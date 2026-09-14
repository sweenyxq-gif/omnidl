// ==UserScript==
// @name         HubCloud Direct Resolver
// @namespace    https://omni.downloader/resolvers/hubcloud
// @version      1.0.0
// @description  Resolves HubCloud and HubStream file locker gates (FSL, 10Gbps, Cloudflare R2, S3, PixelDrain) into direct downloadable streams.
// @match        *://*.hubcloud.club/*
// @match        *://*.hubcloud.ink/*
// @match        *://*.hubcloud.cx/*
// @match        *://*.hubcloud.one/*
// @match        *://*.hubcloud.lat/*
// @match        *://*.hubstream.dad/*
// @match        *://*.hubstream.me/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      hubcloud.club
// @connect      hubcloud.ink
// @connect      hubcloud.cx
// @connect      hubcloud.one
// @connect      hubcloud.lat
// @connect      hubstream.dad
// @connect      hubstream.me
// @connect      pixeldrain.com
// @connect      *
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// ==/UserScript==

(async function() {
    try {
        omni.log("HubCloud resolver triggered: " + targetUrl);

        function parseSize(sizeStr) {
            if (!sizeStr) return -1;
            var m = sizeStr.match(/([\d.]+)\s*(GB|MB|KB|Bytes)/i);
            if (!m) return -1;
            var val = parseFloat(m[1]);
            var unit = m[2].toUpperCase();
            if (unit === "GB") return Math.round(val * 1024 * 1024 * 1024);
            if (unit === "MB") return Math.round(val * 1024 * 1024);
            if (unit === "KB") return Math.round(val * 1024);
            return Math.round(val);
        }

        var pageUrl = targetUrl;
        var headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": pageUrl
        };

        var resp = await omni.fetch(pageUrl, { headers: headers });
        if (resp.status !== 200) {
            omni.log("HubCloud initial fetch failed with status " + resp.status);
            return;
        }

        var html = await resp.text();

        // Step 1: Check if landing page redirects via var url = '...'
        var redirectMatch = html.match(/var\s+url\s*=\s*['"]([^'"]+)['"]/i);
        if (redirectMatch && redirectMatch[1]) {
            var nextUrl = redirectMatch[1];
            if (!nextUrl.startsWith("http")) {
                var u = new URL(pageUrl);
                nextUrl = u.protocol + "//" + u.host + (nextUrl.startsWith("/") ? "" : "/") + nextUrl;
            }
            omni.log("HubCloud following redirect to: " + nextUrl);
            resp = await omni.fetch(nextUrl, { headers: headers });
            if (resp.status === 200) {
                html = await resp.text();
                pageUrl = nextUrl;
            }
        }

        // Step 2: Extract Title & File Size
        var title = "hubcloud_media";
        var titleMatch = html.match(/<h1[^>]*class=["'][^"']*page-title[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i) ||
                         html.match(/<title>([\s\S]*?)<\/title>/i);
        if (titleMatch && titleMatch[1]) {
            title = titleMatch[1].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
        }

        var sizeBytes = -1;
        var sizeMatch = html.match(/<i[^>]*id=["']size["'][^>]*>([\s\S]*?)<\/i>/i) ||
                        html.match(/([\d.]+)\s*(GB|MB|KB)/i);
        if (sizeMatch && sizeMatch[1]) {
            sizeBytes = parseSize(sizeMatch[0] || sizeMatch[1]);
        }

        omni.log("HubCloud title: " + title + " | Size: " + sizeBytes);

        // Step 3: Extract Server Links
        var linkRegex = /<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
        var foundMatches = [];
        var m;
        while ((m = linkRegex.exec(html)) !== null) {
            var href = m[1].trim();
            var text = m[2].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
            if (href && !href.startsWith("#") && !href.startsWith("javascript:")) {
                foundMatches.push({ href: href, text: text });
            }
        }

        var resolvedCount = 0;

        for (var i = 0; i < foundMatches.length; i++) {
            var item = foundMatches[i];
            var href = item.href;
            var text = item.text.toLowerCase();

            // Cloudflare R2 direct link
            if (href.includes("r2.dev")) {
                omni.resolve({
                    url: href,
                    label: "HubCloud (Direct R2 10Gbps)",
                    filename: title,
                    size: sizeBytes,
                    mimeType: "video/mp4",
                    type: "HTTP"
                });
                resolvedCount++;
                continue;
            }

            // PixelDrain link
            if (href.includes("pixeldrain.com/u/")) {
                var pdId = href.split("/u/")[1].split(/[?#/]/)[0];
                var pdDirect = "https://pixeldrain.com/api/file/" + pdId + "?download";
                omni.resolve({
                    url: pdDirect,
                    label: "HubCloud (PixelDrain Fast)",
                    filename: title,
                    size: sizeBytes,
                    mimeType: "video/mp4",
                    type: "HTTP"
                });
                resolvedCount++;
                continue;
            }

            // FSL Server or 10Gbps download button
            var isFsl = text.includes("fsl") || href.includes("fslv2");
            var is10Gbps = text.includes("10gbps") || text.includes("download");
            var isS3 = text.includes("s3");
            var isZipDisk = text.includes("zipdisk");

            if (isFsl || is10Gbps || isS3 || isZipDisk) {
                var serverLabel = isFsl ? "FSL Server" :
                                 is10Gbps ? "HubCloud 10Gbps" :
                                 isS3 ? "S3 Server" : "ZipDisk Server";

                var targetHref = href;
                if (!targetHref.startsWith("http")) {
                    var u = new URL(pageUrl);
                    targetHref = u.protocol + "//" + u.host + (targetHref.startsWith("/") ? "" : "/") + targetHref;
                }

                if (targetHref.includes("/download")) {
                    try {
                        var dlResp = await omni.fetch(targetHref, {
                            headers: {
                                "User-Agent": headers["User-Agent"],
                                "Referer": pageUrl,
                                "x-requested-with": "XMLHttpRequest"
                            }
                        });
                        var dlHtml = await dlResp.text();
                        var directDl = dlHtml.match(/<a\s+[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*btn-success[^"']*["']/i) ||
                                       dlHtml.match(/<a\s+[^>]*href=["']([^"']+)["'][^>]*>Download Now<\/a>/i) ||
                                       dlHtml.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/i);
                        if (directDl && directDl[1]) {
                            targetHref = directDl[1];
                        }
                    } catch (e) {
                        omni.log("Server gateway fetch error for " + serverLabel + ": " + e.message);
                    }
                }

                omni.resolve({
                    url: targetHref,
                    label: "HubCloud (" + serverLabel + ")",
                    filename: title,
                    size: sizeBytes,
                    mimeType: "video/mp4",
                    type: "HTTP"
                });
                resolvedCount++;
            }
        }

        if (resolvedCount === 0) {
            omni.log("HubCloud: no direct download links extracted from " + pageUrl);
        } else {
            omni.log("HubCloud: successfully resolved " + resolvedCount + " stream/download options.");
        }
    } catch (err) {
        omni.log("HubCloud resolver error: " + err.message);
    }
})();
