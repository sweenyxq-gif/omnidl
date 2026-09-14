// ==UserScript==
// @name         1Fichier Direct Resolver
// @namespace    https://omni.downloader/resolvers/1fichier
// @version      1.0.0
// @description  Resolves direct download links from 1Fichier file pages.
// @match        https://1fichier.com/?*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      1fichier.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// @downloadURL  https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/1fichier.user.js
// @updateURL    https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/1fichier.user.js
// ==/UserScript==

(async function() {
    try {
        omni.log("1Fichier resolver matched: " + targetUrl);
        var resp = await omni.fetch(targetUrl);
        var html = await resp.text();

        // 1. Direct download button
        var dlMatch = /href=["'](https?:\/\/[a-z0-9]+\.1fichier\.com\/[a-z0-9]+)["'][^>]*class=["'][^"']*okbtn/i.exec(html);
        if (!dlMatch) {
            dlMatch = /<a[^>]+href=["'](https?:\/\/[a-z0-9]+\.1fichier\.com\/[^"']+)["']/i.exec(html);
        }

        // Filename in table
        var filename = "download.bin";
        var nameMatch = /<td[^>]*class=["']normal["'][^>]*>([^<]+)<\/td>/i.exec(html);
        if (nameMatch && nameMatch[1]) {
            filename = nameMatch[1].trim();
        }

        if (dlMatch && dlMatch[1]) {
            omni.resolve({
                url: dlMatch[1],
                label: "1Fichier: " + filename,
                filename: filename,
                type: "HTTP"
            });
            omni.log("Found direct 1Fichier URL: " + dlMatch[1]);
        } else {
            omni.log("Waiting for form submission or captcha on 1Fichier landing.");
        }
    } catch (e) {
        omni.log("1Fichier resolver error: " + e.message);
    }
})();
