// ==UserScript==
// @name         GitHub Release Assets Resolver
// @namespace    https://omni.downloader/resolvers/github
// @version      1.1.0
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
        var response = await omni.fetch(targetUrl);
        var html = await response.text();

        // Match direct release asset download links
        var assetRegex = /href=["']([^"']+\/releases\/download\/[^"']+)["']/g;
        var match;
        var foundCount = 0;
        var seenUrls = {};

        while ((match = assetRegex.exec(html)) !== null) {
            var rawPath = match[1];
            var fullUrl = rawPath.indexOf("http") === 0 ? rawPath : "https://github.com" + rawPath;
            if (seenUrls[fullUrl]) continue;
            seenUrls[fullUrl] = true;

            var cleanUrl = fullUrl.split("?")[0];
            var filename = cleanUrl.substring(cleanUrl.lastIndexOf("/") + 1);

            omni.resolve({
                url: fullUrl,
                label: "GitHub Asset: " + filename,
                filename: filename,
                type: "HTTP"
            });
            foundCount++;
        }

        // Also check for source archive tarball / zipball
        var sourceRegex = /href=["']([^"']+\/archive\/refs\/tags\/[^"']+\.(?:zip|tar\.gz))["']/g;
        while ((match = sourceRegex.exec(html)) !== null) {
            var rawPath = match[1];
            var fullUrl = rawPath.indexOf("http") === 0 ? rawPath : "https://github.com" + rawPath;
            if (seenUrls[fullUrl]) continue;
            seenUrls[fullUrl] = true;

            var filename = fullUrl.substring(fullUrl.lastIndexOf("/") + 1);
            omni.resolve({
                url: fullUrl,
                label: "Source Code (" + filename + ")",
                filename: filename,
                type: "HTTP"
            });
            foundCount++;
        }

        omni.log("Resolved " + foundCount + " downloadable asset(s) from GitHub release.");
    } catch (e) {
        omni.log("GitHub release resolver error: " + e.message);
    }
})();
