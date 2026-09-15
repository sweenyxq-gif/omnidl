import fs from "node:fs/promises";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const scriptsDir = path.join(root, "app", "src", "main", "assets", "userscripts");
const timeoutMs = 20_000;

const fixtures = [
  ["1fichier.user.js", "https://1fichier.com/?abc", '<td class="normal">sample.zip</td><a href="https://a1.1fichier.com/token" class="okbtn">Download</a>'],
  ["buzzheavier.user.js", "https://buzzheavier.com/test123", "<title>sample.bin - Buzzheavier</title>"],
  ["civitai.user.js", "https://civitai.com/models/4201/sample", JSON.stringify({ name: "Sample Model", type: "Checkpoint", modelVersions: [{ id: 42, name: "v1", files: [{ name: "sample.safetensors", sizeKB: 1, downloadUrl: "https://civitai.com/api/download/models/42" }] }] })],
  ["fileaxa.user.js", "https://s1.fileaxa.com/d/test/sample.zip", ""],
  ["github-releases.user.js", "https://github.com/example/project/releases/tag/v1", JSON.stringify({ assets: [{ name: "app.zip", size: 10, content_type: "application/zip", browser_download_url: "https://github.com/example/project/releases/download/v1/app.zip" }] })],
  ["gitlab-releases.user.js", "https://gitlab.com/example/project/-/releases/v1", JSON.stringify({ assets: { links: [{ name: "app.zip", direct_asset_url: "https://gitlab.com/example/project/-/releases/v1/downloads/app.zip" }] } })],
  ["gofile.user.js", "https://gofile.io/d/test123", JSON.stringify({ status: "ok", data: { children: { one: { type: "file", link: "https://store1.gofile.io/download/test/sample.zip", name: "sample.zip", size: 10 } } } })],
  ["hblinks.user.js", "https://www.gadgetsweb.xyz/test", '<meta http-equiv="refresh" content="0;url=https://example.com/final.zip">'],
  ["hdhub4u.user.js", "https://www.hdhub4u.example/post", '<h1 class="entry-title">Sample Video</h1><p>1080p 10 MB</p><a href="https://pixeldrain.com/u/test123">Download 1080p</a>'],
  ["hubcloud.user.js", "https://hubcloud.club/drive/test", '<h1 class="page-title">sample.mp4</h1><i id="size">10 MB</i><a href="https://sample.r2.dev/video.mp4">R2</a>'],
  ["huggingface.user.js", "https://huggingface.co/openai-community/gpt2/blob/main/README.md", ""],
  ["internet-archive.user.js", "https://archive.org/details/sample-item", JSON.stringify({ files: [{ name: "sample.mp4", format: "MPEG4", size: "10" }] })],
  ["krakenfiles.user.js", "https://krakenfiles.com/view/test/file.html", '<title>sample.zip - Krakenfiles.com</title><button data-url="https://krakenfiles.com/download/test/sample.zip">Download</button>'],
  ["mediafire.user.js", "https://www.mediafire.com/file/test/sample.zip/file", '<div class="filename">sample.zip</div><a id="downloadButton" href="https://download1.mediafire.com/test/sample.zip">Download</a>'],
  ["pixeldrain.user.js", "https://pixeldrain.com/u/test123", JSON.stringify({ success: true, name: "sample.zip", size: 10, mime_type: "application/zip" })],
  ["sourceforge.user.js", "https://sourceforge.net/projects/example/files/releases/", '<a href="/projects/example/files/releases/app.zip/download">Download</a>'],
  ["streamtape.user.js", "https://streamtape.com/v/test/sample", '<meta property="og:title" content="Sample"><div id="robotlink">//streamtape.com/get_video?id=test</div>'],
  ["universal-media-sniffer.user.js", "https://example.com/media/page.html", '<video src="https://example.com/media/sample.mp4"></video>'],
];

const liveCases = [
  ["civitai.user.js", "https://civitai.com/models/4201/realistic-vision-v60-b1"],
  ["github-releases.user.js", "https://github.com/yt-dlp/yt-dlp/releases/latest"],
  ["gitlab-releases.user.js", "https://gitlab.com/gitlab-org/cli/-/releases/v1.91.0"],
  ["huggingface.user.js", "https://huggingface.co/openai-community/gpt2/blob/main/README.md"],
  ["internet-archive.user.js", "https://archive.org/details/BigBuckBunny_328"],
  ["sourceforge.user.js", "https://sourceforge.net/projects/sevenzip/files/7-Zip/"],
  ["universal-media-sniffer.user.js", "https://www.w3schools.com/html/html5_video.asp"],
];

function response(status, body, url) {
  return {
    status,
    statusText: status === 200 ? "OK" : "Error",
    ok: status >= 200 && status < 300,
    url,
    headers: {},
    text: async () => body,
    json: async () => JSON.parse(body),
  };
}

async function runScript(filename, targetUrl, fetcher) {
  const code = await fs.readFile(path.join(scriptsDir, filename), "utf8");
  const resolved = [];
  const logs = [];
  const context = vm.createContext({
    targetUrl,
    URL,
    Set,
    console,
    atob: value => Buffer.from(value, "base64").toString("binary"),
    btoa: value => Buffer.from(value, "binary").toString("base64"),
    omni: {
      log: value => logs.push(String(value)),
      resolve: value => resolved.push(typeof value === "string" ? { url: value } : value),
      fetch: fetcher,
    },
  });
  const result = vm.runInContext(code, context, { filename });
  let timeout;
  try {
    await Promise.race([
      Promise.resolve(result),
      new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error("execution timeout")), timeoutMs); }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
  return { filename, targetUrl, resolved, logs };
}

async function contractTests() {
  const results = [];
  for (const [filename, targetUrl, fixture] of fixtures) {
    const result = await runScript(filename, targetUrl, async url => response(200, fixture, url));
    results.push({ ...result, passed: result.resolved.length > 0 });
  }
  return results;
}

async function liveTests() {
  const results = [];
  for (const [filename, targetUrl] of liveCases) {
    try {
      const result = await runScript(filename, targetUrl, async (url, options = {}) => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
          const live = await fetch(url, {
            method: options.method || "GET",
            headers: { "user-agent": "OmniDL-PC-Extension-Test/1.0", ...(options.headers || {}) },
            body: options.body || undefined,
            redirect: "follow",
            signal: controller.signal,
          });
          const text = (await live.text()).slice(0, 10 * 1024 * 1024);
          return response(live.status, text, live.url);
        } finally {
          clearTimeout(timer);
        }
      });
      results.push({ ...result, passed: result.resolved.length > 0 });
    } catch (error) {
      results.push({ filename, targetUrl, resolved: [], logs: [], passed: false, error: error.message });
    }
  }
  return results;
}

function print(title, results) {
  console.log(`\n${title}`);
  for (const item of results) {
    const detail = item.error || item.logs.at(-1) || `${item.resolved.length} result(s)`;
    console.log(`${item.passed ? "PASS" : "FAIL"}  ${item.filename.padEnd(35)} ${item.resolved.length} result(s)  ${detail}`);
  }
}

const contract = await contractTests();
print("Deterministic provider-contract tests", contract);

let live = [];
if (process.argv.includes("--live")) {
  live = await liveTests();
  print("Live public-link smoke tests", live);
}

const output = { generatedAt: new Date().toISOString(), contract, live };
const reportPath = path.join(root, "app", "build", "reports", "extension-pc-test.json");
await fs.mkdir(path.dirname(reportPath), { recursive: true });
await fs.writeFile(reportPath, JSON.stringify(output, null, 2));
console.log(`\nReport: ${reportPath}`);

if (contract.some(item => !item.passed)) process.exitCode = 1;
