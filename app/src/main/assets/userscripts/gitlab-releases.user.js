// ==UserScript==
// @name         GitLab Release Assets Resolver
// @namespace    https://omni.downloader/resolvers/gitlab
// @version      1.1.0
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
        var parsed = new URL(targetUrl);
        var marker = parsed.pathname.indexOf("/-/releases/");
        if (marker < 1) {
            omni.log("GitLab URL is not a project release page.");
            return;
        }
        var projectPath = parsed.pathname.substring(1, marker);
        var tag = decodeURIComponent(parsed.pathname.substring(marker + "/-/releases/".length).split("/")[0]);
        var apiUrl = "https://gitlab.com/api/v4/projects/" + encodeURIComponent(projectPath) + "/releases/" + encodeURIComponent(tag);
        var response = await omni.fetch(apiUrl);
        if (!response.ok) {
            omni.log("GitLab Releases API returned status " + response.status);
            return;
        }
        var release = await response.json();
        var count = 0;
        var links = release.assets && release.assets.links ? release.assets.links : [];
        for (var i = 0; i < links.length; i++) {
            var item = links[i];
            var url = item.direct_asset_url || item.url;
            if (!url) continue;
            var filename = item.name || url.substring(url.lastIndexOf("/") + 1).split("?")[0];
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
