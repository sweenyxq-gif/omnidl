// ==UserScript==
// @name         GitLab Release Assets Resolver
// @namespace    https://omni.downloader/resolvers/gitlab
// @version      1.0.0
// @description  Finds and resolves downloadable release assets published on GitLab repository release pages.
// @match        https://gitlab.com/*/-/releases/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      gitlab.com
// @omni-resolver true
// @omni-api     1
// @omni-category development
// ==/UserScript==

(async function() {
    try {
        omni.log("GitLab release resolver matched: " + targetUrl);
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var pattern = /href=["']([^"']+\/-\/releases\/[^"']+\/downloads\/[^"']+)["']/g;
        var match;
        var count = 0;
        while ((match = pattern.exec(html)) !== null) {
            var url = match[1].indexOf("http") === 0 ? match[1] : "https://gitlab.com" + match[1];
            var filename = url.substring(url.lastIndexOf("/") + 1).split("?")[0];
            omni.resolve({
                url: url,
                label: "GitLab Asset: " + filename,
                filename: filename,
                type: "HTTP"
            });
            count++;
        }
        omni.log("Resolved " + count + " GitLab release asset(s).");
    } catch (e) {
        omni.log("GitLab resolver error: " + e.message);
    }
})();
