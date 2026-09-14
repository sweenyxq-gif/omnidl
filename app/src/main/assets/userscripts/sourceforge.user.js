// ==UserScript==
// @name         SourceForge Project Files Resolver
// @namespace    https://omni.downloader/resolvers/sourceforge
// @version      1.0.0
// @description  Finds official download entries on public SourceForge project file pages.
// @match        https://sourceforge.net/projects/*/files/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      sourceforge.net
// @omni-resolver true
// @omni-api     1
// @omni-category development
// ==/UserScript==

(async function() {
    try {
        omni.log("SourceForge resolver matched: " + targetUrl);
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var pattern = /href=["'](\/projects\/[^"']+\/files\/[^"']+\/download)["']/g;
        var match;
        var count = 0;
        var seen = {};

        while ((match = pattern.exec(html)) !== null) {
            var url = "https://sourceforge.net" + match[1];
            if (seen[url]) continue;
            seen[url] = true;

            var parts = match[1].split("/");
            var filename = parts.length > 2 ? parts[parts.length - 2] : "download";
            omni.resolve({
                url: url,
                label: "SourceForge: " + filename,
                filename: filename,
                type: "HTTP"
            });
            count++;
        }
        omni.log("Resolved " + count + " SourceForge download link(s).");
    } catch (e) {
        omni.log("SourceForge resolver error: " + e.message);
    }
})();
