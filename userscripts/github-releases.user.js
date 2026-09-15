// ==UserScript==
// @name         GitHub Release Assets Resolver
// @namespace    https://omni.downloader/resolvers/github
// @version      1.2.0
// @description  Resolves direct download links for software release binaries, APKs, and archives on GitHub.
// @match        https://github.com/*/*/releases*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      github.com
// @connect      api.github.com
// @connect      objects.githubusercontent.com
// @omni-resolver true
// @omni-api     1
// @omni-category development
// ==/UserScript==

(async function() {
    try {
        omni.log("GitHub release resolver matched: " + targetUrl);
        var parsed = new URL(targetUrl);
        var parts = parsed.pathname.split("/").filter(Boolean);
        if (parts.length < 3 || parts[2] !== "releases") {
            omni.log("GitHub URL is not a repository release page.");
            return;
        }
        var owner = parts[0];
        var repo = parts[1];
        var apiUrl = "https://api.github.com/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repo) + "/releases/latest";
        if (parts[3] === "tag" && parts[4]) {
            apiUrl = "https://api.github.com/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repo) + "/releases/tags/" + encodeURIComponent(decodeURIComponent(parts.slice(4).join("/")));
        }
        var response = await omni.fetch(apiUrl, { headers: { "Accept": "application/vnd.github+json" } });
        if (!response.ok) {
            omni.log("GitHub Releases API returned status " + response.status);
            return;
        }
        var release = await response.json();
        var foundCount = 0;
        var seenUrls = {};
        var assets = release.assets || [];
        for (var i = 0; i < assets.length; i++) {
            var asset = assets[i];
            var fullUrl = asset.browser_download_url;
            if (!fullUrl) continue;
            if (seenUrls[fullUrl]) continue;
            seenUrls[fullUrl] = true;
            omni.resolve({
                url: fullUrl,
                label: "GitHub Asset: " + (asset.name || "download"),
                filename: asset.name || "download",
                size: Number(asset.size || -1),
                mimeType: asset.content_type || "application/octet-stream",
                type: "HTTP"
            });
            foundCount++;
        }
        omni.log("Resolved " + foundCount + " downloadable asset(s) from GitHub release.");
    } catch (e) {
        omni.log("GitHub release resolver error: " + e.message);
    }
})();
