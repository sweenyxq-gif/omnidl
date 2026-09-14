package com.omnidownloader.data.userscript

import com.omnidownloader.domain.userscript.UserscriptMetadata

object ExtensionCatalog {
    val scripts: List<UserscriptMetadata> by lazy {
        listOf(
            gitLabReleases,
            internetArchive,
            sourceForge,
            pixeldrain,
            mediaFire,
            huggingFace,
            civitai,
            githubReleases,
            buzzheavier
        ).mapNotNull(UserscriptMetadataParser::parse)
    }

    private val gitLabReleases = """
        // ==UserScript==
        // @name GitLab Release Assets
        // @namespace https://omni.downloader/resolvers/gitlab
        // @version 1.0.0
        // @description Finds downloadable assets published on public GitLab release pages.
        // @match https://gitlab.com/*/-/releases/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect gitlab.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category development
        // ==/UserScript==
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var pattern = /href=["']([^"']+\/-\/releases\/[^"']+\/downloads\/[^"']+)["']/g;
        var match;
        while ((match = pattern.exec(html)) !== null) {
            var url = match[1].indexOf("http") === 0 ? match[1] : "https://gitlab.com" + match[1];
            var filename = url.substring(url.lastIndexOf("/") + 1).split("?")[0];
            omni.resolve({url: url, label: "GitLab release asset", filename: filename});
        }
    """.trimIndent()

    private val internetArchive = """
        // ==UserScript==
        // @name Internet Archive Files
        // @namespace https://omni.downloader/resolvers/archive-org
        // @version 1.0.0
        // @description Lists public files and sizes from an Internet Archive item.
        // @match https://archive.org/details/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect archive.org
        // @omni-resolver true
        // @omni-api 1
        // @omni-category archives
        // ==/UserScript==
        var identifier = targetUrl.split("/details/")[1].split(/[?#/]/)[0];
        var response = await omni.fetch("https://archive.org/metadata/" + encodeURIComponent(identifier));
        var metadata = await response.json();
        (metadata.files || []).slice(0, 50).forEach(function(file) {
            if (!file.name || file.private === true) return;
            omni.resolve({
                url: "https://archive.org/download/" + encodeURIComponent(identifier) + "/" + file.name.split("/").map(encodeURIComponent).join("/"),
                label: file.format || "Archive file",
                filename: file.name,
                size: Number(file.size || -1)
            });
        });
    """.trimIndent()

    private val sourceForge = """
        // ==UserScript==
        // @name SourceForge Project Files
        // @namespace https://omni.downloader/resolvers/sourceforge
        // @version 1.0.0
        // @description Finds official download entries on public SourceForge file pages.
        // @match https://sourceforge.net/projects/*/files/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect sourceforge.net
        // @omni-resolver true
        // @omni-api 1
        // @omni-category development
        // ==/UserScript==
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var pattern = /href=["'](\/projects\/[^"']+\/files\/[^"']+\/download)["']/g;
        var match;
        while ((match = pattern.exec(html)) !== null) {
            var url = "https://sourceforge.net" + match[1];
            var parts = match[1].split("/");
            var filename = parts.length > 2 ? parts[parts.length - 2] : "download";
            omni.resolve({url: url, label: "SourceForge file", filename: filename});
        }
    """.trimIndent()

    private val pixeldrain = """
        // ==UserScript==
        // @name Pixeldrain Direct Resolver
        // @namespace https://omni.downloader/resolvers/pixeldrain
        // @version 1.0.0
        // @description Resolves Pixeldrain file and list links into direct high-speed downloadable streams with metadata.
        // @match https://pixeldrain.com/u/*
        // @match https://pixeldrain.com/api/file/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect pixeldrain.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category filehost
        // ==/UserScript==
        var fileId = "";
        if (targetUrl.indexOf("/u/") !== -1) {
            fileId = targetUrl.split("/u/")[1].split(/[?#/]/)[0];
        } else if (targetUrl.indexOf("/api/file/") !== -1) {
            fileId = targetUrl.split("/api/file/")[1].split(/[?#/]/)[0];
        }
        if (fileId) {
            var directUrl = "https://pixeldrain.com/api/file/" + encodeURIComponent(fileId);
            var filename = "pixeldrain_" + fileId;
            var size = -1;
            try {
                var infoResp = await omni.fetch("https://pixeldrain.com/api/file/" + encodeURIComponent(fileId) + "/info");
                if (infoResp.status === 200) {
                    var info = await infoResp.json();
                    if (info && info.success) {
                        filename = info.name || filename;
                        size = Number(info.size || -1);
                    }
                }
            } catch (e) {}
            omni.resolve({
                url: directUrl,
                label: "Pixeldrain (" + filename + ")",
                filename: filename,
                size: size,
                type: "HTTP"
            });
        }
    """.trimIndent()

    private val mediaFire = """
        // ==UserScript==
        // @name MediaFire Direct Resolver
        // @namespace https://omni.downloader/resolvers/mediafire
        // @version 1.0.0
        // @description Extracts direct download URLs and file metadata from MediaFire file pages.
        // @match https://www.mediafire.com/file/*
        // @match https://mediafire.com/file/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect mediafire.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category filehost
        // ==/UserScript==
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var directUrl = null;
        var btnMatch = /<a[^>]+id=["']downloadButton["'][^>]+href=["']([^"']+)["']/i.exec(html);
        if (btnMatch && btnMatch[1]) directUrl = btnMatch[1];
        if (!directUrl) {
            var altMatch = /href=["'](https?:\/\/[^"']+\.mediafire\.com\/[^"']+)["'][^>]*id=["']downloadButton["']/i.exec(html);
            if (altMatch && altMatch[1]) directUrl = altMatch[1];
        }
        var filename = "download";
        var nameMatch = /<div class=["']filename["'][^>]*>([^<]+)<\/div>/i.exec(html);
        if (nameMatch && nameMatch[1]) filename = nameMatch[1].trim();
        if (directUrl) {
            omni.resolve({
                url: directUrl,
                label: "MediaFire: " + filename,
                filename: filename,
                type: "HTTP"
            });
        }
    """.trimIndent()

    private val huggingFace = """
        // ==UserScript==
        // @name Hugging Face Model & Dataset Resolver
        // @namespace https://omni.downloader/resolvers/huggingface
        // @version 1.0.0
        // @description Resolves direct download links for AI models, weights (.safetensors, .gguf), and datasets on Hugging Face.
        // @match https://huggingface.co/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect huggingface.co
        // @omni-resolver true
        // @omni-api 1
        // @omni-category ai-models
        // ==/UserScript==
        if (targetUrl.indexOf("/blob/") !== -1) {
            var directUrl = targetUrl.replace("/blob/", "/resolve/") + "?download=true";
            var parts = targetUrl.split("/blob/")[1].split("/");
            var filename = parts[parts.length - 1].split(/[?#]/)[0];
            omni.resolve({
                url: directUrl,
                label: "Hugging Face: " + filename,
                filename: filename,
                type: "HTTP"
            });
        } else if (targetUrl.indexOf("/resolve/") !== -1) {
            var dlUrl = targetUrl.indexOf("?download=true") !== -1 ? targetUrl : targetUrl + "?download=true";
            var parts = targetUrl.split("/resolve/")[1].split("/");
            var fname = parts[parts.length - 1].split(/[?#]/)[0];
            omni.resolve({
                url: dlUrl,
                label: "Hugging Face: " + fname,
                filename: fname,
                type: "HTTP"
            });
        }
    """.trimIndent()

    private val civitai = """
        // ==UserScript==
        // @name Civitai AI Model Direct Resolver
        // @namespace https://omni.downloader/resolvers/civitai
        // @version 1.0.0
        // @description Resolves direct download URLs for Checkpoints, LoRAs, and Safetensors from Civitai model pages.
        // @match https://civitai.com/models/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect civitai.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category ai-models
        // ==/UserScript==
        var match = targetUrl.match(/\/models\/([0-9]+)/);
        if (match && match[1]) {
            var modelId = match[1];
            var apiUrl = "https://civitai.com/api/v1/models/" + modelId;
            var resp = await omni.fetch(apiUrl);
            if (resp.status === 200) {
                var data = await resp.json();
                var modelName = data.name || ("Civitai Model " + modelId);
                var versions = data.modelVersions || [];
                for (var i = 0; i < Math.min(versions.length, 4); i++) {
                    var v = versions[i];
                    var files = v.files || [];
                    for (var j = 0; j < files.length; j++) {
                        var f = files[j];
                        var dlUrl = f.downloadUrl || ("https://civitai.com/api/download/models/" + v.id);
                        var fname = f.name || (modelName.replace(/[^a-zA-Z0-9_\-]/g, "_") + ".safetensors");
                        var sizeBytes = f.sizeKB ? Math.round(f.sizeKB * 1024) : -1;
                        omni.resolve({
                            url: dlUrl,
                            label: modelName + " (" + (v.name || "Default") + ")",
                            filename: fname,
                            size: sizeBytes,
                            type: "HTTP"
                        });
                    }
                }
            }
        }
    """.trimIndent()

    private val githubReleases = """
        // ==UserScript==
        // @name GitHub Release Assets
        // @namespace https://omni.downloader/resolvers/github
        // @version 1.1.0
        // @description Resolves direct download links for software release binaries, APKs, and archives on GitHub.
        // @match https://github.com/*/*/releases*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect github.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category development
        // ==/UserScript==
        var response = await omni.fetch(targetUrl);
        var html = await response.text();
        var assetRegex = /href=["']([^"']+\/releases\/download\/[^"']+)["']/g;
        var match;
        var seenUrls = {};
        while ((match = assetRegex.exec(html)) !== null) {
            var fullUrl = match[1].indexOf("http") === 0 ? match[1] : "https://github.com" + match[1];
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
        }
    """.trimIndent()

    private val buzzheavier = """
        // ==UserScript==
        // @name Buzzheavier Direct Resolver
        // @namespace https://omni.downloader/resolvers/buzzheavier
        // @version 1.0.0
        // @description Resolves direct high-speed download links from Buzzheavier file shares.
        // @match https://buzzheavier.com/*
        // @grant GM_xmlhttpRequest
        // @grant GM_log
        // @connect buzzheavier.com
        // @omni-resolver true
        // @omni-api 1
        // @omni-category filehost
        // ==/UserScript==
        var path = targetUrl.replace("https://buzzheavier.com/", "").split(/[?#]/)[0];
        var parts = path.split("/").filter(Boolean);
        if (parts.length > 0) {
            var fileId = parts[0];
            if (fileId !== "download" && fileId !== "api") {
                var dlUrl = "https://buzzheavier.com/" + fileId + "/download";
                var filename = "buzzheavier_" + fileId;
                omni.resolve({
                    url: dlUrl,
                    label: "Buzzheavier: " + filename,
                    filename: filename,
                    type: "HTTP"
                });
            }
        }
    """.trimIndent()
}
