// ==UserScript==
// @name         FileAxa Direct Resolver
// @namespace    https://omni.downloader/resolvers/fileaxa
// @version      1.0.0
// @description  Resolves direct high-speed links from FileAxa download pages.
// @match        https://*.fileaxa.com/*
// @match        https://fileaxa.com/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      fileaxa.com
// @connect      *.fileaxa.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// @downloadURL  https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/fileaxa.user.js
// @updateURL    https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/fileaxa.user.js
// ==/UserScript==

(async function() {
    try {
        omni.log("FileAxa resolver matched: " + targetUrl);

        // Case A: Direct s*.fileaxa.com/d/ URL
        if (targetUrl.indexOf("/d/") !== -1) {
            var parts = targetUrl.split("/d/")[1].split("/");
            var filename = parts.length > 1 ? decodeURIComponent(parts[1].split("?")[0]) : "download.bin";
            omni.resolve({
                url: targetUrl,
                label: "FileAxa Direct: " + filename,
                filename: filename,
                type: "HTTP",
                headers: {
                    "Referer": "https://fileaxa.com/"
                }
            });
            return;
        }

        // Case B: Landing page https://fileaxa.com/<id>
        var pageResp = await omni.fetch(targetUrl);
        var html = await pageResp.text();

        var dlMatch = /href=["'](https?:\/\/s[0-9]+\.fileaxa\.com\/d\/[^"']+)["']/i.exec(html);
        if (dlMatch && dlMatch[1]) {
            var directUrl = dlMatch[1];
            var fparts = directUrl.split("/d/")[1].split("/");
            var fname = fparts.length > 1 ? decodeURIComponent(fparts[1].split("?")[0]) : "download.bin";

            omni.resolve({
                url: directUrl,
                label: "FileAxa: " + fname,
                filename: fname,
                type: "HTTP",
                headers: {
                    "Referer": "https://fileaxa.com/"
                }
            });
            omni.log("Found direct FileAxa stream: " + fname);
        } else {
            omni.log("No direct download button found on FileAxa landing page.");
        }
    } catch (e) {
        omni.log("FileAxa resolver error: " + e.message);
    }
})();
