/**
 * Canta — Phase 0 reconnaissance, third pass.
 *
 * The first two passes settled the catalogue/detail/episode markup. This pass
 * settles the three things the extractor's *contract* depends on, all of which
 * pass 2 could not see because the pages are JavaScript-driven:
 *
 *   1. `?host=<label>` mirror switching on a voir-anime episode page — does the
 *      `<select class="host-select">` actually swap `div#chapter-video-frame`?
 *   2. the voe family, which now moves through a client-side redirect
 *      (voe.sx → jamesbornmain.com) before any payload exists;
 *   3. VidMoly-family masters, including a *measured* byte size per variant —
 *      the number the fast-lane quality guard compares against 200 MiB, plus the
 *      Referer/Origin matrix (nebula-p audit §8.43) on both the manifest and a
 *      media segment.
 */

import { writeFileSync, mkdirSync } from "node:fs";

const OUT = "recon-out";
mkdirSync(OUT, { recursive: true });
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Accept: "text/html,application/xhtml+xml,*/*;q=0.8", "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8" };
const VA = "https://voir-anime.to";
const NK = "https://nakanime.tv";
const report = { generatedAt: new Date().toISOString(), pass: 3 };

function log(...a) { console.log(...a); }
function dump(name, s) { try { writeFileSync(`${OUT}/${name}`, s); } catch { /* ignore */ } }
function clip(s, n = 1200) { return (s || "").replace(/\s+/g, " ").slice(0, n); }

async function req(url, { headers = H, method = "GET", body, timeout = 25000 } = {}) {
  const c = new AbortController();
  const t = setTimeout(() => c.abort(), timeout);
  try {
    const r = await fetch(url, { method, headers, body, redirect: "follow", signal: c.signal });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, headers: Object.fromEntries(r.headers), text: buf.toString("utf8"), raw: buf, url: r.url };
  } catch (e) {
    return { status: 0, error: e.message, headers: {}, text: "", raw: Buffer.alloc(0), url };
  } finally { clearTimeout(t); }
}

// ---------------------------------------------------------------------------
// [3.1] mirror switching: ?host=<label>
// ---------------------------------------------------------------------------
function parseChapterSources(html) {
  const m = html.match(/var\s+thisChapterSources\s*=\s*(\{[\s\S]*?\});\s*\n/);
  if (!m) return null;
  const entries = [];
  const re = /"([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"/g;
  let mm;
  while ((mm = re.exec(m[1])) !== null) {
    const label = mm[1];
    const payload = mm[2].replace(/\\"/g, '"').replace(/\\\//g, "/");
    const src = (payload.match(/src="([^"]+)"/) || [])[1] || null;
    entries.push({ label, src, host: src ? new URL(src).host : null });
  }
  return entries;
}

async function mirrorSwitch() {
  log("\n===== [3.1] ?host= mirror switching =====");
  const episodeUrl = `${VA}/anime/one-piece-vf/one-piece-1110-vf/`;
  const page = await req(episodeUrl, { headers: { ...H, Referer: `${VA}/` } });
  const entries = parseChapterSources(page.text) || [];
  const hostOptions = [...page.text.matchAll(/<option[^>]*data-redirect="([^"]+)"[^>]*value="([^"]*)"/g)].map((m) => ({ redirect: m[1], value: m[2] }));
  const out = { episodeUrl, status: page.status, entries, hostOptions: hostOptions.slice(0, 8), perHost: [] };
  log("thisChapterSources:", JSON.stringify(entries));
  log("hostOptions:", JSON.stringify(out.hostOptions));

  for (const opt of hostOptions.slice(0, 6)) {
    const url = opt.redirect.startsWith("http") ? opt.redirect : new URL(opt.redirect, episodeUrl).toString();
    const r = await req(url, { headers: { ...H, Referer: episodeUrl } });
    const frame = (r.text.match(/<div class="chapter-video-frame"[\s\S]{0,400}?<\/div>/i) || [])[0] || "";
    const src = (frame.match(/src="([^"]+)"/) || [])[1] || null;
    const row = { label: opt.value, url, status: r.status, iframeSrc: src, sameAsFirst: src === (entries[0] && entries[0].src) };
    out.perHost.push(row);
    log("host", opt.value, "->", r.status, clip(src, 120));
  }
  report.mirrorSwitch = out;
  return out;
}

// ---------------------------------------------------------------------------
// [3.2] voe family through its client-side redirect
// ---------------------------------------------------------------------------
function rot13(s) { return s.replace(/[a-zA-Z]/g, (c) => String.fromCharCode((c <= "Z" ? 90 : 122) >= c.charCodeAt(0) + 13 ? c.charCodeAt(0) + 13 : c.charCodeAt(0) - 13)); }
function b64(s) { let t = (s || "").replace(/-/g, "+").replace(/_/g, "/"); const r = t.length % 4; if (r) t += "=".repeat(4 - r); return Buffer.from(t, "base64").toString("utf8"); }
function decodeVoe(payload) {
  let s = rot13(payload);
  for (const op of ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]) s = s.split(op).join("");
  s = b64(s);
  s = Array.from(s, (ch) => String.fromCharCode(ch.charCodeAt(0) - 3)).join("");
  s = s.split("").reverse().join("");
  s = b64(s);
  try { return JSON.parse(s); } catch { return { __parseError: true, tail: s.slice(0, 300) }; }
}

async function hlsVariants(streamUrl, headers, label) {
  const master = await req(streamUrl, { headers });
  const out = { streamUrl, manifestStatus: master.status, bytes: master.text.length, variants: [], mediaPlaylist: false };
  if (master.status !== 200) return out;
  dump(`${label}-master.m3u8`, master.text);
  const lines = master.text.split(/\r?\n/);
  out.mediaPlaylist = !/#EXT-X-STREAM-INF/.test(master.text);
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
    const next = (lines[i + 1] || "").trim();
    if (!next || next.startsWith("#")) continue;
    const bw = Number((lines[i].match(/BANDWIDTH=(\d+)/) || [])[1] || 0);
    const res = (lines[i].match(/RESOLUTION=(\d+x\d+)/) || [])[1] || "?";
    const vurl = new URL(next, streamUrl).toString();
    const pl = await req(vurl, { headers });
    const segs = pl.text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
    const dur = [...pl.text.matchAll(/#EXTINF:([\d.]+)/g)].map((m) => Number(m[1])).reduce((a, b) => a + b, 0);
    out.variants.push({
      resolution: res, bandwidth: bw, segments: segs.length, durationSeconds: +dur.toFixed(1),
      playlistUrl: vurl, firstSegment: segs[0] ? new URL(segs[0], vurl).toString() : null,
      segmentUrls: segs.length <= 200 ? segs.map((s) => new URL(s, vurl).toString()) : [],
      bandwidthImpliedBytes: Math.round((bw * dur) / 8),
    });
  }
  if (out.mediaPlaylist) {
    const segs = lines.map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
    const dur = [...master.text.matchAll(/#EXTINF:([\d.]+)/g)].map((m) => Number(m[1])).reduce((a, b) => a + b, 0);
    out.variants.push({
      resolution: "single-rendition", bandwidth: 0, segments: segs.length, durationSeconds: +dur.toFixed(1),
      playlistUrl: streamUrl, firstSegment: segs[0] ? new URL(segs[0], streamUrl).toString() : null,
      segmentUrls: segs.length <= 200 ? segs.map((s) => new URL(s, streamUrl).toString()) : [],
      bandwidthImpliedBytes: 0,
    });
  }
  return out;
}

/** Sum of real Content-Length values, bounded concurrency. Returns measured bytes. */
async function measureSegments(urls, headers, { concurrency = 8, timeout = 15000 } = {}) {
  const queue = [...urls];
  let bytes = 0, counted = 0, failed = 0;
  await Promise.all(new Array(concurrency).fill(0).map(async () => {
    while (queue.length) {
      const u = queue.pop();
      const h = await req(u, { headers, method: "HEAD", timeout });
      const len = Number(h.headers["content-length"] || 0);
      if (len > 0) { bytes += len; counted++; } else failed++;
    }
  }));
  return { measuredBytes: bytes, counted, failed, total: urls.length };
}

async function voeFamily() {
  log("\n===== [3.2] voe family (redirect + payload decode) =====");
  const probes = [
    ["voe-sx", "https://voe.sx/e/8ncp6aa32n0u", `${VA}/`],
    ["jamesborn-vf", "https://jamesbornmain.com/e/n0ik5gb8a7mg", `${NK}/`],
  ];
  const out = [];
  for (const [label, url, referer] of probes) {
    const hop0 = await req(url, { headers: { ...H, Referer: referer } });
    const redirect = (hop0.text.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/i) || [])[1] || null;
    dump(`va3-${label}-hop0.html`, hop0.text);
    const row = { label, url, status: hop0.status, bytes: hop0.text.length, jsRedirect: redirect, finalUrl: hop0.url };
    const target = redirect && redirect.startsWith("http") ? redirect : (redirect ? new URL(redirect, url).toString() : null);
    if (target) {
      const hop1 = await req(target, { headers: { ...H, Referer: url } });
      dump(`va3-${label}-hop1.html`, hop1.text);
      row.hop1 = { url: target, status: hop1.status, bytes: hop1.text.length, title: (hop1.text.match(/<title>([^<]*)<\/title>/i) || [])[1] || null };
      const payloads = [...hop1.text.matchAll(/\["([A-Za-z0-9+/=_-]{200,})"\]/g)].map((m) => m[1]);
      row.hop1.payloadCount = payloads.length;
      if (payloads.length) {
        const dec = decodeVoe(payloads[0]);
        row.hop1.decodedKeys = dec && !dec.__parseError ? Object.keys(dec) : ["__parseError"];
        const streamUrl = dec?.source || dec?.hls || dec?.direct_access_url || dec?.file || null;
        row.hop1.streamUrl = streamUrl;
        if (streamUrl) {
          const origin = `https://${new URL(target).host}`;
          row.hop1.hls = await hlsVariants(streamUrl, { ...H, Referer: `${origin}/`, Origin: origin }, `va3-${label}`);
        }
      }
      // The SPA path: what scripts does it load, and does any of them name an API?
      row.hop1.scripts = [...hop1.text.matchAll(/<script[^>]+src="([^"]+)"/gi)].map((m) => m[1]).slice(0, 6);
      const apiHits = [...hop1.text.matchAll(/["'](\/[a-z0-9/_.-]*(?:api|source|stream|video|file)[a-z0-9/_.-]*)["']/gi)].map((m) => m[1]).slice(0, 10);
      row.hop1.apiHits = [...new Set(apiHits)];
      log(label, JSON.stringify({ status: row.status, redirect, hop1: row.hop1 && { status: row.hop1.status, payloads: row.hop1.payloadCount, keys: row.hop1.decodedKeys, stream: clip(row.hop1.streamUrl, 90) } }));
    } else {
      log(label, "no JS redirect; payloads:", row.hop1 ? row.hop1.payloadCount : 0);
    }
    out.push(row);
  }
  report.voeFamily = out;
}

// ---------------------------------------------------------------------------
// [3.3] VidMoly family: master, measured variant sizes, referer matrix
// ---------------------------------------------------------------------------
const REFERER_CANDIDATES = (pageUrl, playerOrigin) => ({
  "no-referer": {},
  "voir-anime page": { Referer: pageUrl },
  "player origin": { Referer: playerOrigin },
  "player origin slash": { Referer: `${playerOrigin}/` },
  "vidmoly.biz": { Referer: "https://vidmoly.biz/" },
  "vidmoly.to": { Referer: "https://vidmoly.to/" },
});

async function vidmoly(label, embedUrl, pageUrl) {
  log(`\n===== [3.3] vidmoly family: ${label} ${embedUrl} =====`);
  const playerOrigin = `https://${new URL(embedUrl).host}`;
  const embed = await req(embedUrl, { headers: { ...H, Referer: pageUrl } });
  dump(`va3-embed-${label}.html`, embed.text);
  const fileMatch = embed.text.match(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]/i)
    || embed.text.match(/file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]/i);
  const masterUrl = fileMatch ? fileMatch[1] : null;
  const row = { label, embedUrl, status: embed.status, bytes: embed.text.length, masterUrl, playerOrigin };
  log("status", embed.status, "masterUrl:", clip(masterUrl, 140));
  if (!masterUrl) { report[`vidmoly_${label}`] = row; return row; }

  const hdr = { ...H, Referer: `${playerOrigin}/`, Origin: playerOrigin };
  row.hls = await hlsVariants(masterUrl, hdr, `va3-${label}`);

  // Measure every variant: sum of real Content-Length values over all segments.
  for (const v of row.hls.variants) {
    if (!v.segmentUrls || !v.segmentUrls.length) continue;
    const m = await measureSegments(v.segmentUrls, hdr);
    v.measuredBytes = m.measuredBytes;
    v.measuredSegments = m.counted;
    v.measuredFailed = m.failed;
    v.measuredMb = +(m.measuredBytes / 1048576).toFixed(1);
    v.bandwidthImpliedMb = +(v.bandwidthImpliedBytes / 1048576).toFixed(1);
    v.exceedsFastLaneCeiling = m.measuredBytes > 200 * 1024 * 1024;
    log(`  ${v.resolution}: segments=${v.segments} measured=${v.measuredMb} MB (bandwidth-implied ${v.bandwidthImpliedMb} MB, failed=${m.failed}) exceeds200MB=${v.exceedsFastLaneCeiling}`);
  }

  // Referer/Origin matrix (audit §8.43): manifest and first media segment.
  const first = row.hls.variants.find((v) => v.firstSegment) || null;
  const probeUrl = first ? first.playlistUrl : masterUrl;
  const segUrl = first ? first.firstSegment : null;
  row.refererMatrix = [];
  for (const [name, extra] of Object.entries(REFERER_CANDIDATES(pageUrl, playerOrigin))) {
    const a = await req(probeUrl, { headers: { ...H, ...extra }, method: "HEAD" });
    const b = segUrl ? await req(segUrl, { headers: { ...H, ...extra }, method: "HEAD" }) : null;
    const rowM = { candidate: name, playlistStatus: a.status, playlistLength: Number(a.headers["content-length"] || 0), segmentStatus: b ? b.status : null, segmentLength: b ? Number(b.headers["content-length"] || 0) : null };
    row.refererMatrix.push(rowM);
    log("   referer", name, JSON.stringify(rowM));
  }
  report[`vidmoly_${label}`] = row;
  return row;
}

// ---------------------------------------------------------------------------
// [3.4] mfw09 SPA: does any bundle name an API that yields a stream?
// ---------------------------------------------------------------------------
async function mfwSpa() {
  log("\n===== [3.4] mfw09 SPA internals =====");
  const url = "https://mfw09.org/e/dhy4skfrq20x";
  const page = await req(url, { headers: { ...H, Referer: `${VA}/` } });
  const scripts = [...page.text.matchAll(/<script[^>]+src="([^"]+)"/gi)].map((m) => m[1]).slice(0, 4);
  const out = { url, status: page.status, chunks: page.text.length, scripts, bundles: [] };
  for (const s of scripts) {
    const abs = new URL(s, url).toString();
    const b = await req(abs, { headers: H });
    const hits = [...b.text.matchAll(/["'`](\/[a-z0-9/_.-]*(?:api|source|stream|video|file|embed)[a-z0-9/_.-]*)["'`]/gi)].map((m) => m[1]);
    const m3u8 = [...b.text.matchAll(/["'`]([^"'`]{0,80}\.m3u8[^"'`]{0,40})["'`]/gi)].map((m) => m[1]);
    out.bundles.push({ url: abs, status: b.status, bytes: b.text.length, apiPaths: [...new Set(hits)].slice(0, 20), m3u8Refs: m3u8.slice(0, 5) });
    log("bundle", abs, b.status, "apiPaths:", JSON.stringify(out.bundles.at(-1).apiPaths.slice(0, 8)));
  }
  report.mfwSpa = out;
}

async function main() {
  try { await mirrorSwitch(); } catch (e) { report.mirrorSwitchError = String(e); console.error("mirrorSwitch", e); }
  try { await voeFamily(); } catch (e) { report.voeFamilyError = String(e); console.error("voeFamily", e); }
  try { await vidmoly("voembed", "https://voembed.net/embed-zxtqco5wxp3d.html", `${VA}/anime/one-piece-vf/one-piece-1110-vf/`); }
  catch (e) { report.voembedError = String(e); console.error("voembed", e); }
  try { await vidmoly("vidmolyorg", "https://vidmoly.org/embed-v3es93abbcjm.html", `${NK}/anime/16/season/1/episode/1`); }
  catch (e) { report.vidmolyorgError = String(e); console.error("vidmolyorg", e); }
  try { await mfwSpa(); } catch (e) { report.mfwSpaError = String(e); console.error("mfwSpa", e); }
  writeFileSync(`${OUT}/recon3-report.json`, JSON.stringify(report, null, 2));
  log("\npass 3 complete");
}

main().catch((e) => {
  console.error("FATAL", e);
  writeFileSync(`${OUT}/recon3-report.json`, JSON.stringify({ ...report, fatal: String(e) }, null, 2));
  process.exitCode = 0;
});
