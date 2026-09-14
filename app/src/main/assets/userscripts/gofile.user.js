// ==UserScript==
// @name         Gofile Direct Stream Resolver
// @namespace    https://omni.downloader/resolvers/gofile
// @version      1.0.0
// @description  Resolves direct download links for files stored on Gofile.io.
// @match        https://gofile.io/d/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      gofile.io
// @connect      api.gofile.io
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// @downloadURL  https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/gofile.user.js
// @updateURL    https://raw.githubusercontent.com/sweenyxq-gif/omnidl-extensions/main/userscripts/gofile.user.js
// ==/UserScript==

(async function() {
    try {
        omni.log("Gofile resolver matched: " + targetUrl);
        var contentId = targetUrl.split("/d/")[1].split(/[?#/]/)[0];
        if (!contentId) {
            omni.log("Could not extract Gofile content ID.");
            return;
        }

        var apiUrl = "https://api.gofile.io/contents/" + encodeURIComponent(contentId) + "?wt=4fd6sg89d7s6";
        var resp = await omni.fetch(apiUrl);
        if (resp.status === 200) {
            var data = await resp.json();
            if (data && data.status === "ok" && data.data && data.data.children) {
                var children = data.data.children;
                var keys = Object.keys(children);
                for (var i = 0; i < keys.length; i++) {
                    var item = children[keys[i]];
                    if (item.type === "file" && item.link) {
                        omni.resolve({
                            url: item.link,
                            label: "Gofile: " + item.name,
                            filename: item.name,
                            size: Number(item.size || -1),
                            mimeType: item.mimetype || "application/octet-stream",
                            type: "HTTP"
                        });
                    }
                }
                omni.log("Resolved " + keys.length + " file(s) from Gofile container.");
            }
        }
    } catch (e) {
        omni.log("Gofile resolver error: " + e.message);
    }
})();
