// ==UserScript==
// @name         Hugging Face Model & Dataset Resolver
// @namespace    https://omni.downloader/resolvers/huggingface
// @version      1.0.0
// @description  Resolves direct download links for AI models, weights (.safetensors, .gguf, .bin), and datasets on Hugging Face.
// @match        https://huggingface.co/*
// @grant        GM_xmlhttpRequest
// @grant        GM_log
// @connect      huggingface.co
// @connect      cdn-lfs.huggingface.co
// @connect      cdn-lfs-us-1.huggingface.co
// @omni-resolver true
// @omni-api     1
// @omni-category ai-models
// ==/UserScript==

(async function() {
    try {
        omni.log("Hugging Face resolver matched: " + targetUrl);
        var parsed = targetUrl.replace("https://huggingface.co/", "").split(/[?#]/)[0];
        var parts = parsed.split("/").filter(Boolean);

        if (parts.length < 2) {
            omni.log("Not a valid Hugging Face repository URL.");
            return;
        }

        var repoId = parts[0] + "/" + parts[1];

        // Scenario A: User pasted a direct blob or resolve link (e.g. /blob/main/model.safetensors)
        if (targetUrl.indexOf("/blob/") !== -1) {
            var directUrl = targetUrl.replace("/blob/", "/resolve/") + "?download=true";
            var filename = parts[parts.length - 1];
            omni.resolve({
                url: directUrl,
                label: "Hugging Face: " + filename,
                filename: filename,
                type: "HTTP"
            });
            omni.log("Resolved direct blob file: " + filename);
            return;
        }

        if (targetUrl.indexOf("/resolve/") !== -1) {
            var dlUrl = targetUrl.indexOf("?download=true") !== -1 ? targetUrl : targetUrl + "?download=true";
            var fname = parts[parts.length - 1];
            omni.resolve({
                url: dlUrl,
                label: "Hugging Face: " + fname,
                filename: fname,
                type: "HTTP"
            });
            return;
        }

        // Scenario B: User pasted model root page (e.g. https://huggingface.co/mistralai/Mistral-7B-v0.1)
        // Query Hugging Face public Model API
        var apiUrl = "https://huggingface.co/api/models/" + encodeURIComponent(repoId);
        var apiResp = await omni.fetch(apiUrl);
        if (apiResp.status === 200) {
            var modelData = await apiResp.json();
            var siblings = modelData.siblings || [];
            var foundWeights = 0;

            for (var i = 0; i < siblings.length; i++) {
                var rfilename = siblings[i].rfilename;
                if (!rfilename) continue;

                // Prioritize model weights and essential config
                var isWeight = /\.(safetensors|gguf|bin|onnx|pt|pth|ckpt|model|json|txt|tokenizer)$/i.test(rfilename);
                if (isWeight) {
                    var fileDlUrl = "https://huggingface.co/" + repoId + "/resolve/main/" + rfilename + "?download=true";
                    var leafName = rfilename.substring(rfilename.lastIndexOf('/') + 1);
                    omni.resolve({
                        url: fileDlUrl,
                        label: repoId.split('/')[1] + " / " + leafName,
                        filename: leafName,
                        type: "HTTP"
                    });
                    foundWeights++;
                    if (foundWeights >= 25) break; // Limit to 25 primary files per model
                }
            }
            omni.log("Resolved " + foundWeights + " weight/config files from " + repoId);
        } else {
            omni.log("Could not fetch model metadata API, HTTP status: " + apiResp.status);
        }
    } catch (e) {
        omni.log("Hugging Face resolver error: " + e.message);
    }
})();
