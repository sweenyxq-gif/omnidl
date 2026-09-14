// ==UserScript==
// @name         HbLinks & Gate Bypasser
// @namespace    https://omni.downloader/resolvers/hblinks
// @version      1.0.0
// @description  Bypasses intermediate shortlink gates (cryptoinsights, gadgetsweb, techyboy4u, hdstream4u) decoding ROT13+Base64 tokens to reveal destination downloads.
// @match        *://*.gadgetsweb.xyz/*
// @match        *://*.cryptoinsights.site/*
// @match        *://*.kiemtienmua911ca/*
// @match        *://*.techyboy4u.*/*
// @match        *://*.hdstream4u.*/*
// @match        *://*.hblinks.*/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      gadgetsweb.xyz
// @connect      cryptoinsights.site
// @connect      kiemtienmua911ca
// @connect      techyboy4u.com
// @connect      hdstream4u.com
// @connect      *
// @omni-resolver true
// @omni-api     1
// @omni-category shortener
// ==/UserScript==

(async function() {
    try {
        omni.log("HbLinks / Gate resolver triggered: " + targetUrl);

        function rot13(str) {
            if (!str) return "";
            return str.replace(/[a-zA-Z]/g, function(c) {
                var base = c <= 'Z' ? 65 : 97;
                return String.fromCharCode(base + (c.charCodeAt(0) - base + 13) % 26);
            });
        }

        var headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": targetUrl
        };

        var resp = await omni.fetch(targetUrl, { headers: headers });
        if (resp.status !== 200) {
            omni.log("Gate fetch failed: " + resp.status);
            return;
        }

        var html = await resp.text();

        // 1. Direct window.location.href or meta refresh redirect
        var locMatch = html.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/i) ||
                       html.match(/<meta[^>]*http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"']+)["']/i);
        if (locMatch && locMatch[1]) {
            var dest = locMatch[1].trim();
            if (dest.startsWith("http")) {
                omni.log("Gate resolved immediate redirect: " + dest);
                omni.resolve({
                    url: dest,
                    label: "Direct Destination (" + new URL(dest).hostname + ")",
                    filename: "download_link",
                    size: -1,
                    mimeType: "text/html",
                    type: "HTTP"
                });
                return;
            }
        }

        // 2. Obfuscated gate payload: s("o", "...") or ck("_wp_http_...", "...")
        var pattern = /s\s*\(\s*['"]o['"]\s*,\s*['"]([^'"]+)['"]\s*\)|ck\s*\(\s*['"]_wp_http_[^'"]+['"]\s*,\s*['"]([^'"]+)['"]\s*\)/i;
        var tokenMatch = html.match(pattern);

        if (tokenMatch) {
            var rawToken = tokenMatch[1] || tokenMatch[2];
            try {
                // Deobfuscation pipeline: atob(rot13(atob(atob(token))))
                var step1 = atob(rawToken);
                var step2 = atob(step1);
                var step3 = rot13(step2);
                var decoded = atob(step3);

                var parsed = JSON.parse(decoded);
                var oVal = parsed.o || parsed.data || "";
                var reToken = atob(oVal);

                var actionMatch = html.match(/<form[^>]*action=["']([^"']+)["']/i) ||
                                  html.match(/action\s*:\s*['"]([^'"]+)['"]/i);
                var actionUrl = actionMatch ? actionMatch[1] : targetUrl;
                if (!actionUrl.startsWith("http")) {
                    var u = new URL(targetUrl);
                    actionUrl = u.protocol + "//" + u.host + (actionUrl.startsWith("/") ? "" : "/") + actionUrl;
                }

                var unlockUrl = actionUrl + (actionUrl.includes("?") ? "&" : "?") + "re=" + encodeURIComponent(reToken);
                omni.log("Gate sending unlock request to: " + unlockUrl);

                var unlockResp = await omni.fetch(unlockUrl, {
                    headers: {
                        "User-Agent": headers["User-Agent"],
                        "Referer": targetUrl,
                        "HX-Request": "true"
                    }
                });

                var hxRedirect = unlockResp.headers ? (unlockResp.headers["hx-redirect"] || unlockResp.headers["HX-Redirect"]) : null;
                var finalDest = hxRedirect;

                if (!finalDest) {
                    var unlockText = await unlockResp.text();
                    var destM = unlockText.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/i) ||
                                unlockText.match(/<a[^>]*href=["']([^"']+)["'][^>]*>(?:Continue|Download|Click Here)<\/a>/i);
                    if (destM) finalDest = destM[1];
                }

                if (finalDest) {
                    omni.log("Gate successfully bypassed! Destination: " + finalDest);
                    omni.resolve({
                        url: finalDest,
                        label: "Bypassed Destination Link",
                        filename: "media_target",
                        size: -1,
                        mimeType: "application/octet-stream",
                        type: "HTTP"
                    });
                    return;
                }
            } catch (decErr) {
                omni.log("Gate decoding error: " + decErr.message);
            }
        }

        // 3. Fallback: Search for any destination download anchors
        var dlAnchor = html.match(/<a\s+[^>]*href=["'](https?:\/\/[^"']*(?:hubcloud|hubstream|pixeldrain|drive|download)[^"']*)["'][^>]*>/i);
        if (dlAnchor && dlAnchor[1]) {
            omni.log("Gate fallback anchor found: " + dlAnchor[1]);
            omni.resolve({
                url: dlAnchor[1],
                label: "Unlocked Link (" + new URL(dlAnchor[1]).hostname + ")",
                filename: "download_link",
                size: -1,
                mimeType: "text/html",
                type: "HTTP"
            });
        }
    } catch (e) {
        omni.log("HbLinks resolver error: " + e.message);
    }
})();
