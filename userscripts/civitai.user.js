// ==UserScript==
// @name         Civitai AI Model Direct Resolver
// @namespace    https://omni.downloader/resolvers/civitai
// @version      1.0.0
// @description  Resolves direct download URLs for Checkpoints, LoRAs, VAEs, and Safetensors from Civitai model pages.
// @match        https://civitai.com/models/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      civitai.com
// @omni-resolver true
// @omni-api     1
// @omni-category ai-models
// ==/UserScript==

(async function() {
    try {
        omni.log("Civitai resolver matched: " + targetUrl);
        var match = targetUrl.match(/\/models\/([0-9]+)/);
        if (!match || !match[1]) {
            omni.log("Could not find Civitai model ID in URL: " + targetUrl);
            return;
        }

        var modelId = match[1];
        var apiUrl = "https://civitai.com/api/v1/models/" + modelId;
        omni.log("Fetching Civitai metadata from " + apiUrl);

        var resp = await omni.fetch(apiUrl);
        if (resp.status !== 200) {
            omni.log("Civitai API returned status " + resp.status);
            return;
        }

        var data = await resp.json();
        var modelName = data.name || ("Civitai Model " + modelId);
        var modelType = data.type || "Model";
        var versions = data.modelVersions || [];

        omni.log("Found " + versions.length + " version(s) for " + modelName);

        // Iterate through latest versions and extract files
        for (var i = 0; i < Math.min(versions.length, 5); i++) {
            var v = versions[i];
            var vName = v.name || "Default";
            var baseModel = v.baseModel || "";
            var files = v.files || [];

            for (var j = 0; j < files.length; j++) {
                var f = files[j];
                var dlUrl = f.downloadUrl || ("https://civitai.com/api/download/models/" + v.id);
                var fname = f.name || (modelName.replace(/[^a-zA-Z0-9_\-]/g, "_") + "_" + vName + ".safetensors");
                var sizeBytes = f.sizeKB ? Math.round(f.sizeKB * 1024) : -1;

                omni.resolve({
                    url: dlUrl,
                    label: modelName + " (" + vName + (baseModel ? " • " + baseModel : "") + ")",
                    filename: fname,
                    size: sizeBytes,
                    type: "HTTP"
                });
            }

            // If no files listed in array, provide version download link directly
            if (files.length === 0 && v.downloadUrl) {
                omni.resolve({
                    url: v.downloadUrl,
                    label: modelName + " (" + vName + ")",
                    filename: modelName.replace(/[^a-zA-Z0-9_\-]/g, "_") + "_" + vName + ".safetensors",
                    type: "HTTP"
                });
            }
        }
    } catch (e) {
        omni.log("Civitai resolver error: " + e.message);
    }
})();
