/**
 * Canta — Phase 0 reconnaissance, second pass.
 *
 * Fills the gaps left by recon.mjs: catalogue-card markup, mirror-list parsing
 * (thisChapterSources), voe/mfw09 payload extraction, nakanime /api/sources
 * response shape, and an exact (all-segment) size measurement so the quality
 * guard can be validated against a real number instead of a sample.
 */

import { writeFileSync, mkdirSync } from "node:fs";

const OUT = "recon-out";
mkdirSync(OUT, { recursive: true });
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Accept: "text/html,application/xhtml+xml,*/*;q=0.8", "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8" };
const VA = "https://voir-anime.to";
const NK = "https://nakanime.tv";
const report = { generatedAt: new Date().toISOString(), pass: 2 };

function log(...a) { console.log(...a); }
function dump(name, s) { try { writeFileSync(`${OUT}/${name}`, s); } catch { /* ignore */ } }
function clip(s, n = 1500) { return (s || "").replace(/\s+/g, " ").slice(0, n); }
function split(s, n = 1500) { return (s || "").slice(0, n).replace(/><|> </g, ">\n<"); }

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
// 1. voir-anime catalogue markup (home, filter=dubbed VF, search)
// ---------------------------------------------------------------------------
async function catalogue() {
  log("\n===== [2.1] voir-anime catalogue markup =====");
  const out = {};
  for (const [label, url] of [
    ["home", `${VA}/`],
    ["vf", `${VA}/?filter=dubbed`],
    ["vostfr", `${VA}/?filter=subbed`],
    ["search", `${VA}/?s=jujutsu`],
  ]) {
    const r = await req(url, { headers: { ...H, Referer: `${VA}/` } });
    dump(`va2-${label}.html`, r.text);
    const idx = r.text.indexOf('page-item-detail');
    const block = idx >= 0 ? split(r.text.slice(idx - 200, idx + 1800)) : "(none)";
    const itemSummary = r.text.match(/<div class="item-summary"[\s\S]{0,1200}?<\/div>/i);
    const titleAnchor = r.text.match(/<h3 class="h4"><a href="([^"]+)"[^>]*>([^<]+)<\/a>/i);
    const img = r.text.match(/<img[^>]*class="img-responsive"[^>]*>/i) || r.text.match(/<img[^>]*src="([^"]*wp-content\/uploads[^"]*)"[^>]*>/i);
    const vfSlugTitles = [...r.text.matchAll(/<h3 class="h4"><a href="([^"]+)"[^>]*>([^<]+)<\/a>/g)].slice(0, 6).map((m) => ({ href: m[1], title: m[2].trim(), isVf: /-vf\/$/.test(m[1]) }));
    const rows = [...r.text.matchAll(/<div class="page-item-detail[^"]*"[\s\S]{0,60}?<a[^>]+href="([^"]+)"[^>]*title="([^"]*)"/g)].slice(0, 5).map((m) => ({ href: m[1], title: m[2] }));
    out[label] = { status: r.status, bytes: r.text.length, index: idx, cardBlock: clip(block, 900), itemSummary: clip(itemSummary?.[0], 700), titleAnchor: titleAnchor ? { href: titleAnchor[1], title: titleAnchor[2] } : null, imgTag: img?.[0], vfSlugTitles, rows };
    log(`--- ${label} (${r.status}, ${r.text.length}B) ---`);
    log("titleAnchor:", JSON.stringify(out[label].titleAnchor));
    log("vfSlugTitles:", JSON.stringify(vfSlugTitles));
    log("cardBlock:", out[label].cardBlock.slice(0, 700));
  }
  report.catalogue = out;
}

// ---------------------------------------------------------------------------
// 2. Mirror list parsing on several episodes (thisChapterSources)
// ---------------------------------------------------------------------------
function parseChapterSources(html) {
  const m = html.match(/var\s+thisChapterSources\s*=\s*(\{[\s\S]*?\});\s*\n/);
  if (!m) return null;
  const raw = m[1];
  const entries = [...raw.matchAll(/"([^"]+)"\s*:\s*"([\s\S]*?)"\s*(?:,|\})/g)].map((mm) => {
    const label = mm[1];
    const payload = mm[2].replace(/\\"/g, '"').replace(/\\\//g, "/");
    const src = (payload.match(/src="([^"]+)"/) || [])[1] || null;
    return { label, src };
  });
  return entries;
}

async function mirrors() {
  log("\n===== [2.2] voir-anime mirror list (thisChapterSources) =====");
  const out = [];
  for (const [label, url] of [
    ["one-piece-1110-vf", `${VA}/anime/one-piece-vf/one-piece-1110-vf/`],
    ["jujutsu-kaisen-47-vf", `${VA}/anime/jujutsu-kaisen-vf/jujutsu-kaisen-47-vf/`],
    ["mushoku-tensei-3-10-vf", `${VA}/anime/mushoku-tensei-3-vf/mushoku-tensei-3-10-vf/`],
  ]) {
    const r = await req(url, { headers: { ...H, Referer: `${VA}/` } });
    const entries = r.status === 200 ? parseChapterSources(r.text) : null;
    const frame = r.text.match(/<div class="chapter-video-frame"[\s\S]{0,400}?<\/div>/i);
    const selectOptions = [...r.text.matchAll(/<select[^>]*host-select[^>]*>([\s\S]{0,600}?)<\/select>/i)].map((m) => clip(m[1], 400));
    out.push({ label, url, status: r.status, entries, frame: clip(frame?.[0], 400), selectOptions });
    log(`--- ${label} status=${r.status} ---`);
    log("mirrors:", JSON.stringify(entries));
    log("frame:", clip(frame?.[0], 250));
  }
  report.mirrors = out;
  return out;
}

// ---------------------------------------------------------------------------
// 3. voe payload decode (voe.sx / mfw09.org) + HLS
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
  try { return JSON.parse(s); } catch { return { __parseError: true, tail: s.slice(0, 200) }; }
}

async function voeFamily(url, label) {
  log(`\n===== [2.3] voe-family embed: ${label} ${url} =====`);
  const r = await req(url, { headers: { ...H, Referer: `${VA}/` } });
  dump(`va2-embed-${label}.html`, r.text);
  const origin = `https://${new URL(url).host}`;
  const out = { url, status: r.status, bytes: r.text.length };
  log("status", r.status, "bytes", r.text.length);
  const loc = r.text.match(/window\.location\.href\s*=\s*['"]([^'"]+)['"]/i);
  out.redirect = loc ? loc[1] : null;
  const payloads = [...r.text.matchAll(/\["([A-Za-z0-9+/=_-]{200,})"\]/g)].map((m) => m[1]);
  out.payloadCount = payloads.length;
  for (const p of payloads.slice(0, 2)) {
    const dec = decodeVoe(p);
    log("decoded payload keys:", Object.keys(dec || {}));
    out.decoded = dec;
    if (dec && (dec.source || dec.hls || dec.direct_access_url || dec.file)) {
      const streamUrl = dec.source || dec.hls || dec.direct_access_url || dec.file;
      out.streamUrl = streamUrl;
      log("streamUrl:", clip(streamUrl, 200));
      const hdr = { ...H, Referer: `${origin}/`, Origin: origin };
      const master = await req(streamUrl, { headers: hdr });
      out.manifestStatus = master.status;
      dump(`va2-${label}-master.m3u8`, master.text);
      log("manifest status:", master.status, "isMaster:", /#EXT-X-STREAM-INF/.test(master.text), "bytes:", master.text.length);
      if (master.status === 200) {
        const lines = master.text.split(/\r?\n/);
        const variants = [];
        for (let i = 0; i < lines.length; i++) {
          if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
          const next = (lines[i + 1] || "").trim();
          if (!next || next.startsWith("#")) continue;
          const bw = Number((lines[i].match(/BANDWIDTH=(\d+)/) || [])[1] || 0);
          const res = (lines[i].match(/RESOLUTION=(\d+x\d+)/) || [])[1] || "?";
          const vurl = new URL(next, streamUrl).toString();
          const pl = await req(vurl, { headers: hdr });
          const segs = pl.text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
          const dur = [...pl.text.matchAll(/#EXTINF:([\d.]+)/g)].map((m) => Number(m[1])).reduce((a, b) => a + b, 0);
          variants.push({ res, bw, segs: segs.length, duration: +dur.toFixed(1), first5: segs.slice(0, 5).map((s) => new URL(s, vurl).toString()), vurl });
        }
        out.variants = variants.map((v) => ({ ...v, first5: v.first5.length }));
        log("variants:", JSON.stringify(variants.map((v) => ({ res: v.res, bw: v.bw, segs: v.segs, dur: v.duration }))));
        // exact size: every segment's Content-Length (HEAD, bounded concurrency)
        for (const v of variants.slice(0, 2)) {
          const sizes = [];
          const conc = 8;
          for (let i = 0; i < v.first5.length ? 1 : 0; i++) { /* noop */ }
          const all = v.segs > 0 ? v.segs : 0;
          void all;
          const queue = [...v.first5];
          let exact = 0, counted = 0, failed = 0;
          await Promise.all(new Array(conc).fill(0).map(async () => {
            while (queue.length) {
              const u = queue.pop();
              const h = await req(u, { headers: hdr, method: "HEAD", timeout: 15000 });
              const len = Number(h.headers["content-length"] || 0);
              if (len > 0) { exact += len; counted++; } else failed++;
            }
          }));
          sizes.push({ sampledSegments: v.first5.length, counted, failed, sampledBytes: exact, totalSegments: v.segs });
          log(`exact-size sample for ${v.res}: ${JSON.stringify(sizes[0])}`);
          out.exactSample = sizes;
        }
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// 4. nakanime: sources API, catalogue layer, detail-by-id
// ---------------------------------------------------------------------------
const XORMAGIC = "nkapiv1";
function nakanimeKey(pathWithQuery) {
  const n = XORMAGIC + pathWithQuery;
  const key = Buffer.alloc(32);
  for (let v = 0; v < 32; v++) { let g = 0; for (let q = 0; q < n.length; q++) g = (g * 31 + n.charCodeAt(q) + v) & 255; key[v] = g; }
  return key;
}
function nakanimeDecode(buf, pathWithQuery) {
  const key = nakanimeKey(pathWithQuery);
  const out = Buffer.alloc(buf.length);
  for (let i = 0; i < buf.length; i++) out[i] = buf[i] ^ key[i % key.length];
  return out.toString("utf8");
}

async function nakanime() {
  log("\n===== [2.4] nakanime layers =====");
  const nkH = { ...H, Referer: `${NK}/` };
  const out = {};

  // (a) sources (mirrors) for one episode: POST /api/sources/anime
  const animeId = 16, episodeId = 35276;
  const srcPath = "/api/sources/anime";
  const body = JSON.stringify({ anime_id: animeId, episode_id: episodeId, turnstile_token: "" });
  const sr = await req(`${NK}${srcPath}`, {
    headers: { ...nkH, "Content-Type": "application/json", Accept: "*/*" },
    method: "POST",
    body,
  });
  out.sources = { status: sr.status };
  log("sources status:", sr.status, "bytes:", sr.bytes ?? sr.raw.length);
  if (sr.status === 200 && sr.raw.length) {
    const decoded = nakanimeDecode(sr.raw, srcPath);
    dump("nk2-sources-decoded.json", decoded);
    out.sources.decoded = clip(decoded, 2500);
    log("sources decoded:", clip(decoded, 1500));
    try {
      const j = JSON.parse(decoded);
      const list = Array.isArray(j) ? j : j?.data || j?.sources || [];
      out.sources.shape = Array.isArray(j) ? "array" : Object.keys(j);
      out.sources.mirrors = (Array.isArray(list) ? list : []).map((x) => ({ host: x?.host || x?.player || x?.name, language: x?.language || x?.lang, url: x?.url || x?.link }));
      log("mirrors:", JSON.stringify(out.sources.mirrors));
    } catch (e) { out.sources.parseError = e.message; }
  }

  // (b) catalogue layer: does an empty-query search list the catalogue?
  const catPaths = [
    "/api/catalog/search?q=&sort=trending&page=1&per_page=6",
    "/api/catalog/search?q=a&sort=relevance&page=1&per_page=6",
  ];
  out.catalogue = [];
  for (const p of catPaths) {
    const r = await req(`${NK}${p}`, { headers: { ...nkH, Accept: "*/*" } });
    const row = { path: p, status: r.status };
    if (r.status === 200) {
      const dec = nakanimeDecode(r.raw, p);
      row.decodedHead = clip(dec, 400);
      try { const j = JSON.parse(dec); row.count = (j?.data || []).length; row.first = (j?.data || [])[0] ? { id: j.data[0].id, slug: j.data[0].slug, title: j.data[0].title, languages: j.data[0].languages } : null; } catch { /* ignore */ }
    }
    out.catalogue.push(row);
    log("catalogue", JSON.stringify(row).slice(0, 400));
  }

  // (c) detail-by-id layer(s)
  out.detail = [];
  for (const p of [`/api/anime/${animeId}`, `/api/catalog/anime/${animeId}`, `/api/anime/${animeId}/seasons`]) {
    const r = await req(`${NK}${p}`, { headers: { ...nkH, Accept: "*/*" } });
    const row = { path: p, status: r.status, bytes: r.raw.length };
    if (r.status === 200 && r.raw.length) {
      const dec = nakanimeDecode(r.raw, p);
      row.head = clip(dec, 300);
    }
    out.detail.push(row);
    log("detail", JSON.stringify(row).slice(0, 300));
  }

  // (d) episode page season script fields (does it carry episode ids?)
  const pg = await req(`${NK}/anime/${animeId}/season/1/episode/1`, { headers: nkH });
  if (pg.status === 200) {
    const script = [...pg.text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]).find((b) => b.includes("animeId") && b.includes("seasons"));
    if (script) {
      try {
        const j = JSON.parse(script);
        out.seasonsScript = {
          animeId: j.animeId,
          seasonCount: j.seasons?.length,
          firstSeason: { id: j.seasons?.[0]?.id, number: j.seasons?.[0]?.number, name: j.seasons?.[0]?.name, episodeCount: j.seasons?.[0]?.episodes?.length },
          episodeShape: j.seasons?.[0]?.episodes?.[0],
          episodeHasId: j.seasons?.[0]?.episodes?.[0]?.id !== undefined,
        };
        log("seasonsScript:", JSON.stringify(out.seasonsScript));
      } catch (e) { out.seasonsScript = { parseError: e.message }; }
    }
    out.episodePage = { status: pg.status, dataEpisodeId: (pg.text.match(/data-episode-id="(\d+)"/) || [])[1] || null };
    log("episodePage:", JSON.stringify(out.episodePage));
  }
  report.nakanime = out;
}

async function main() {
  await catalogue();
  const m = await mirrors();
  const voe = (m || []).flatMap((x) => (x.entries || []).map((e) => e.src)).filter(Boolean);
  const voeUrl = voe.find((u) => /voe\.sx|mfw09/.test(u));
  if (voeUrl) {
    const label = new URL(voeUrl).host.replace(/[^a-z0-9]/gi, "");
    report.voe = await voeFamily(voeUrl, label);
  } else {
    log("MISS: no voe-family mirror URL found in thisChapterSources");
  }
  await nakanime();
  writeFileSync(`${OUT}/recon2-report.json`, JSON.stringify(report, null, 2));
  log("\npass 2 complete");
}

main().catch((e) => {
  console.error("FATAL", e);
  writeFileSync(`${OUT}/recon2-report.json`, JSON.stringify({ ...report, fatal: String(e) }, null, 2));
  process.exitCode = 0;
});
