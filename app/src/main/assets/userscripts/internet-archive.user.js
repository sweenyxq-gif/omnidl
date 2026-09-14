// ==UserScript==
// @name         Internet Archive Files Resolver
// @namespace    https://omni.downloader/resolvers/archive-org
// @version      1.0.0
// @description  Lists public files, formats, and sizes from an Internet Archive item.
// @match        https://archive.org/details/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      archive.org
// @omni-resolver true
// @omni-api     1
// @omni-category archives
// ==/UserScript==

(async function() {
    try {
        omni.log("Internet Archive resolver matched: " + targetUrl);
        var identifier = targetUrl.split("/details/")[1].split(/[?#/]/)[0];
        var response = await omni.fetch("https://archive.org/metadata/" + encodeURIComponent(identifier));
        var metadata = await response.json();
        var files = metadata.files || [];
        var count = 0;

        files.slice(0, 50).forEach(function(file) {
            if (!file.name || file.private === true) return;
            var dlUrl = "https://archive.org/download/" + encodeURIComponent(identifier) + "/" + file.name.split("/").map(encodeURIComponent).join("/");
            omni.resolve({
                url: dlUrl,
                label: (file.format || "Archive file") + " (" + file.name + ")",
                filename: file.name,
                size: Number(file.size || -1),
                type: "HTTP"
            });
            count++;
        });
        omni.log("Resolved " + count + " file(s) from Internet Archive item " + identifier);
    } catch (e) {
        omni.log("Internet Archive resolver error: " + e.message);
    }
})();
