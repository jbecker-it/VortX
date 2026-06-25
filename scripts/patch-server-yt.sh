#!/usr/bin/env bash
# Patches the bundled server.js so the /yt/:id trailer route resolves again.
#
# Why this exists:
#   app/Resources/server.js is Stremio's vendored, fetched, gitignored webpack
#   bundle. Its /yt/:id route calls an inlined ytdl-core 4.9.0 (getInfo over the
#   removed get_video_info endpoint plus a stale HTML scrape), which now 403s
#   against current YouTube, so tvOS trailers never resolve. There is no
#   node_modules to swap a dependency into and the webpack module is closure
#   scoped, so the only reproducible fix is to text-patch the getYt function
#   body in place. server.js is regenerated on every fetch, so this script is
#   wired into the build pipeline (scripts/fetch-server-deps.sh) and is safe to
#   run repeatedly.
#
# The replacement getYt resolves via YouTube's InnerTube player endpoint using
# the ANDROID client context. The InnerTube API key and clientVersion below are
# PUBLIC YouTube constants (they ship in YouTube's own web/mobile clients, they
# are NOT secrets). They drift over time, so this resolver may need an occasional
# version bump. That is still far more durable than the dead ytdl-core 4.9.0 HTML
# scrape, which is broken with no maintenance path here.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_JS="app/Resources/server.js"
MARKER="/*VORTX_YT_PATCHED*/"

if [ ! -f "$SERVER_JS" ]; then
    echo "patch-server-yt: $SERVER_JS not found; run scripts/fetch-server-deps.sh first." >&2
    exit 1
fi

if grep -qF "$MARKER" "$SERVER_JS"; then
    echo "patch-server-yt: $SERVER_JS already patched (marker present); nothing to do."
    exit 0
fi

echo "patch-server-yt: patching getYt in $SERVER_JS ..."

# The Node program below reads server.js as text, replaces the getYt function
# body, and writes atomically (temp file then rename) so a concurrent build
# resource-copy can never observe a torn file.
SERVER_JS="$SERVER_JS" node <<'NODE'
const fs = require("fs");
const path = process.env.SERVER_JS;

const src = fs.readFileSync(path, "utf8");

// Anchor on the exact minified getYt function emitted by the bundle. The body
// runs from "function getYt(id, cb) {" up to and including its closing brace,
// which is the "ytdl.getInfo(...).catch((function(err){...}));}" block.
const startMarker = "function getYt(id, cb) {";
const startIdx = src.indexOf(startMarker);
if (startIdx === -1) {
    console.error("patch-server-yt: could not find getYt signature; bundle layout changed.");
    process.exit(1);
}

// Walk braces from the "{" of the function body to find its matching close,
// so the replacement is robust to minor whitespace/minification drift.
const bodyOpen = src.indexOf("{", startIdx + "function getYt(id, cb)".length);
if (bodyOpen === -1) {
    console.error("patch-server-yt: could not find getYt body open brace.");
    process.exit(1);
}
let depth = 0;
let endIdx = -1;
for (let i = bodyOpen; i < src.length; i++) {
    const ch = src[i];
    if (ch === "{") depth++;
    else if (ch === "}") {
        depth--;
        if (depth === 0) { endIdx = i; break; }
    }
}
if (endIdx === -1) {
    console.error("patch-server-yt: could not find matching close brace for getYt.");
    process.exit(1);
}

const original = src.slice(startIdx, endIdx + 1);
if (original.indexOf("ytdl.getInfo(") === -1) {
    console.error("patch-server-yt: matched getYt does not contain ytdl.getInfo; aborting to be safe.");
    process.exit(1);
}

// Self-contained InnerTube resolver. Uses only node's https core module (no new
// dependency). Calls cb(null, {url}) so the surrounding /yt/:id route, which
// reads format.url, is unchanged.
const replacement = [
    'function getYt(id, cb) {',
    '            /*VORTX_YT_PATCHED*/',
    '            var YT_INNERTUBE_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";',
    '            var YT_CLIENT_VERSION = "19.09.37";',
    '            var https = require("https");',
    '            var payload = JSON.stringify({',
    '                videoId: id,',
    '                context: {',
    '                    client: {',
    '                        clientName: "ANDROID",',
    '                        clientVersion: YT_CLIENT_VERSION,',
    '                        androidSdkVersion: 30,',
    '                        hl: "en",',
    '                        gl: "US"',
    '                    }',
    '                }',
    '            });',
    '            var options = {',
    '                method: "POST",',
    '                hostname: "youtubei.googleapis.com",',
    '                path: "/youtubei/v1/player?key=" + YT_INNERTUBE_KEY,',
    '                headers: {',
    '                    "Content-Type": "application/json",',
    '                    "Content-Length": Buffer.byteLength(payload),',
    '                    "User-Agent": "com.google.android.youtube/" + YT_CLIENT_VERSION + " (Linux; U; Android 11) gzip"',
    '                }',
    '            };',
    '            var req = https.request(options, (function(res) {',
    '                var chunks = [];',
    '                res.on("data", (function(c) { chunks.push(c); }));',
    '                res.on("end", (function() {',
    '                    var info;',
    '                    try { info = JSON.parse(Buffer.concat(chunks).toString("utf8")); }',
    '                    catch (e) { return cb(new Error("bad InnerTube response")); }',
    '                    var sd = info && info.streamingData;',
    '                    if (!sd) return cb(new Error("no streamingData"));',
    '                    var best = null;',
    '                    var formats = sd.formats || [];',
    '                    for (var i = 0; i < formats.length; i++) {',
    '                        var f = formats[i];',
    '                        if (f && f.url && (best === null || (f.bitrate || 0) > (best.bitrate || 0))) best = f;',
    '                    }',
    '                    if (best && best.url) return cb(null, { url: best.url });',
    '                    if (sd.hlsManifestUrl) return cb(null, { url: sd.hlsManifestUrl });',
    '                    return cb(new Error("no playable format"));',
    '                }));',
    '            }));',
    '            req.on("error", cb);',
    '            req.write(payload);',
    '            req.end();',
    '        }'
].join("\n");

const patched = src.slice(0, startIdx) + replacement + src.slice(endIdx + 1);

// Atomic write: temp file in the same directory, then rename over the original.
const tmp = path + ".vortx-tmp." + process.pid;
fs.writeFileSync(tmp, patched);
fs.renameSync(tmp, path);
console.log("patch-server-yt: getYt replaced with InnerTube resolver.");
NODE

echo "patch-server-yt: done."
