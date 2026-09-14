// ==UserScript==
// @name         Buzzheavier Direct Resolver
// @namespace    https://omni.downloader/resolvers/buzzheavier
// @version      1.0.1
// @description  Resolves direct high-speed download links from Buzzheavier file shares.
// @match        https://buzzheavier.com/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      buzzheavier.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// @downloadURL  https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/buzzheavier.user.js
// @updateURL    https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/buzzheavier.user.js
// ==/UserScript==

(async function() {
    try {
        omni.log("Buzzheavier resolver matched: " + targetUrl);
        var path = targetUrl.replace("https://buzzheavier.com/", "").split(/[?#]/)[0];
        var parts = path.split("/").filter(Boolean);

        if (parts.length === 0) {
            omni.log("No file ID in Buzzheavier URL.");
            return;
        }

        var fileId = parts[0];
        if (fileId === "download" || fileId === "api") {
            if (parts.length > 1) fileId = parts[1];
        }

        // Direct stream download endpoint
        var dlUrl = "https://buzzheavier.com/" + fileId + "/download";
        var filename = "buzzheavier_" + fileId;

        try {
            var pageResp = await omni.fetch(targetUrl);
            var pageHtml = await pageResp.text();

            var titleMatch = /<title>([^<]+)<\/title>/i.exec(pageHtml);
            if (titleMatch && titleMatch[1]) {
                var rawTitle = titleMatch[1].replace(/ - buzzheavier$/i, "").trim();
                if (rawTitle) filename = rawTitle;
            }
        } catch (fetchErr) {
            omni.log("Buzzheavier title probe failed: " + fetchErr.message);
        }

        omni.resolve({
            url: dlUrl,
            label: "Buzzheavier: " + filename,
            filename: filename,
            type: "HTTP"
        });
        omni.log("Resolved Buzzheavier file: " + filename + " (" + dlUrl + ")");
    } catch (e) {
        omni.log("Buzzheavier resolver error: " + e.message);
    }
})();
