// ==UserScript==
// @name         Pixeldrain Direct Resolver
// @namespace    https://omni.downloader/resolvers/pixeldrain
// @version      1.0.0
// @description  Resolves Pixeldrain file and list links into direct high-speed downloadable streams with metadata.
// @match        https://pixeldrain.com/u/*
// @match        https://pixeldrain.com/api/file/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      pixeldrain.com
// @omni-resolver true
// @omni-api     1
// @omni-category filehost
// ==/UserScript==

(async function() {
    try {
        omni.log("Pixeldrain resolver matched: " + targetUrl);
        var fileId = "";
        if (targetUrl.indexOf("/u/") !== -1) {
            fileId = targetUrl.split("/u/")[1].split(/[?#/]/)[0];
        } else if (targetUrl.indexOf("/api/file/") !== -1) {
            fileId = targetUrl.split("/api/file/")[1].split(/[?#/]/)[0];
        }

        if (!fileId) {
            omni.log("Could not extract file ID from URL: " + targetUrl);
            return;
        }

        var directUrl = "https://pixeldrain.com/api/file/" + encodeURIComponent(fileId);
        var filename = "pixeldrain_" + fileId;
        var size = -1;
        var mimeType = "application/octet-stream";

        try {
            var infoResp = await omni.fetch("https://pixeldrain.com/api/file/" + encodeURIComponent(fileId) + "/info");
            if (infoResp.status === 200) {
                var info = await infoResp.json();
                if (info && info.success) {
                    filename = info.name || filename;
                    size = Number(info.size || -1);
                    mimeType = info.mime_type || mimeType;
                }
            }
        } catch (infoErr) {
            omni.log("Info metadata fetch skipped: " + infoErr.message);
        }

        omni.resolve({
            url: directUrl,
            label: "Pixeldrain (" + filename + ")",
            filename: filename,
            size: size,
            mimeType: mimeType,
            type: "HTTP"
        });
        omni.log("Resolved Pixeldrain file: " + filename);
    } catch (e) {
        omni.log("Pixeldrain resolver error: " + e.message);
    }
})();
