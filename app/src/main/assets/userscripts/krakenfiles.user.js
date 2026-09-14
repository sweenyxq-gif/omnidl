// ==UserScript==
// @name         Krakenfiles Direct Resolver
// @namespace    https://omni.downloader/resolvers/krakenfiles
// @version      1.0.0
// @description  Resolves direct download links from Krakenfiles pages.
// @match        https://krakenfiles.com/view/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      krakenfiles.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// @downloadURL  https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/krakenfiles.user.js
// @updateURL    https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/krakenfiles.user.js
// ==/UserScript==

(async function() {
    try {
        omni.log("Krakenfiles resolver matched: " + targetUrl);
        var resp = await omni.fetch(targetUrl);
        var html = await resp.text();

        var dlMatch = /data-url=["']([^"']+)["']/i.exec(html) || /href=["'](https?:\/\/[^"']*krakenfiles\.com\/download\/[^"']+)["']/i.exec(html);
        var filename = "download.bin";

        var nameMatch = /<div class=["']coin-name["'][^>]*><h3>([^<]+)<\/h3>/i.exec(html) || /<title>([^<]+)<\/title>/i.exec(html);
        if (nameMatch && nameMatch[1]) {
            filename = nameMatch[1].replace(/ - Krakenfiles\.com$/i, "").trim();
        }

        if (dlMatch && dlMatch[1]) {
            var dlUrl = dlMatch[1].indexOf("http") === 0 ? dlMatch[1] : "https:" + dlMatch[1];
            omni.resolve({
                url: dlUrl,
                label: "Krakenfiles: " + filename,
                filename: filename,
                type: "HTTP"
            });
            omni.log("Found Krakenfiles stream: " + dlUrl);
        }
    } catch (e) {
        omni.log("Krakenfiles resolver error: " + e.message);
    }
})();
