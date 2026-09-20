/**
 * Canta — Phase 0 reconnaissance harness.
 *
 * Runs on a GitHub Actions runner (full internet egress) and produces:
 *   recon-out/*.html   raw HTML captures of every layer of every source
 *   recon-out/*.json   structured findings (selectors, mirrors, HLS variants)
 *   stdout             a bounded, greppable summary (STEP:/HIT:/MISS: lines)
 *
 * The artifact is the evidence base for RECONNAISSANCE.md. Nothing in this
 * file is shipped inside the APK; it exists so selector claims are reproducible.
 */

import { writeFileSync, mkdirSync } from "node:fs";
import crypto from "node:crypto";

const OUT = "recon-out";
mkdirSync(OUT, { recursive: true });

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const FR_HEADERS = {
  "User-Agent": UA,
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
};
const summary = { generatedAt: new Date().toISOString(), node: process.version, steps: {} };

const T0 = Date.now();
const stamp = () => `+${((Date.now() - T0) / 1000).toFixed(1)}s`;

function log(...a) {
  console.log(`[${stamp()}]`, ...a);
}
function section(name) {
  console.log(`\n===== STEP: ${name} =====`);
  summary.steps[name] = summary.steps[name] || {};
}
function hit(k, v) {
  console.log(`HIT: ${k} = ${typeof v === "string" ? v : JSON.stringify(v)}`);
}
function miss(k, v = "") {
  console.log(`MISS: ${k} ${v}`);
}
function dump(name, content) {
  try {
    writeFileSync(`${OUT}/${name}`, content);
    log(`saved ${OUT}/${name} (${content.length} bytes)`);
  } catch (e) {
    log(`dump failed ${name}: ${e.message}`);
  }
}
function clip(s, n = 1200) {
  return (s || "").replace(/\s+/g, " ").slice(0, n);
}

async function req(url, { headers = FR_HEADERS, method = "GET", timeout = 20000, body } = {}) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(url, { method, headers, body, redirect: "follow", signal: ctrl.signal });
    const buf = Buffer.from(await res.arrayBuffer());
    return { status: res.status, headers: Object.fromEntries(res.headers), text: buf.toString("utf8"), raw: buf, bytes: buf.length, url: res.url };
  } catch (e) {
    return { status: 0, error: e.message, headers: {}, text: "", bytes: 0, url };
  } finally {
    clearTimeout(t);
  }
}

/** HEAD (falls back to a 1-byte ranged GET) → measured Content-Length, never estimated. */
async function measure(url, headers = FR_HEADERS) {
  const h = await req(url, { headers, method: "HEAD" });
  if (h.status >= 200 && h.status < 300) {
    const len = Number(h.headers["content-length"] || 0);
    if (len > 0) return { status: h.status, bytes: len, method: "HEAD" };
  }
  const g = await req(url, { headers: { ...headers, Range: "bytes=0-0" } });
  const cr = g.headers["content-range"];
  if (g.status === 206 && cr) {
    const total = Number(String(cr).split("/")[1] || 0);
    if (total > 0) return { status: g.status, bytes: total, method: "RANGE" };
  }
  return { status: h.status || g.status, bytes: 0, method: "NONE", note: h.error || g.error || "no content-length" };
}

// ===========================================================================
// A. Toolchain versions (the runner has full Maven/Google access)
// ===========================================================================
async function versions() {
  section("versions");
  const targets = {
    agp: "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml",
    kotlin: "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml",
    composeBom: "https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml",
    media3: "https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml",
    media3Session: "https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/maven-metadata.xml",
    work: "https://dl.google.com/dl/android/maven2/androidx/work/work-runtime-ktx/maven-metadata.xml",
    datastore: "https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml",
    navigation: "https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml",
    activityCompose: "https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml",
    lifecycle: "https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml",
    coil: "https://repo1.maven.org/maven2/io/coil-kt/coil3/coil-compose/maven-metadata.xml",
    jsoup: "https://repo1.maven.org/maven2/org/jsoup/jsoup/maven-metadata.xml",
    okhttp: "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml",
    gradle: "https://services.gradle.org/versions/current",
  };
  const out = {};
  for (const [k, url] of Object.entries(targets)) {
    const r = await req(url, { headers: { "User-Agent": UA }, timeout: 25000 });
    if (r.status !== 200) {
      miss(k, `http ${r.status} ${r.error || ""}`);
      continue;
    }
    if (k === "gradle") {
      try {
        const j = JSON.parse(r.text);
        out[k] = j.version;
      } catch {
        out[k] = "?";
      }
    } else {
      const all = [...r.text.matchAll(/<version>([^<]+)<\/version>/g)].map((m) => m[1]);
      const stable = all.filter((v) => !/(alpha|beta|rc|dev|-M\d|SNAPSHOT)/i.test(v));
      out[k] = { latest: all[all.length - 1], latestStable: stable[stable.length - 1], stableTail: stable.slice(-6) };
    }
    hit(`version.${k}`, out[k]);
  }
  summary.steps.versions.versions = out;
}

// ===========================================================================
// B. voir-anime.to — catalogue → detail → episode → embed → HLS
// ===========================================================================
const VA = "https://voir-anime.to";
const vaHeaders = { ...FR_HEADERS, Referer: `${VA}/` };

function vaStructure(html, label) {
  const probes = {
    "wp-content (WordPress)": /wp-content\//i.test(html),
    "madara theme marker": /madara|wp-manga/i.test(html),
    ".page-item-detail": (html.match(/page-item-detail/g) || []).length,
    ".post-title": (html.match(/post-title/g) || []).length,
    ".item-summary / .item-thumb": (html.match(/item-(summary|thumb)/g) || []).length,
    ".c-tabs-item__content": (html.match(/c-tabs-item__content/g) || []).length,
    ".eplister (episode list)": (html.match(/eplister/g) || []).length,
    ".epl-num/.epl-title/.epl-date": (html.match(/epl-(num|title|date)/g) || []).length,
    ".chapter-item": (html.match(/chapter-item/g) || []).length,
    'a[href*="/anime/"]': (html.match(/href="[^"]*\/anime\//g) || []).length,
    "episode slug -NN-vf/": (html.match(/-\d+-(vf|vostfr)\//gi) || []).length,
    "iframe tags": (html.match(/<iframe/gi) || []).length,
    "embed-*.html": (html.match(/embed-[a-z0-9]+\.html/gi) || []).length,
    "voembed": (html.match(/voembed/gi) || []).length,
    "vidmoly": (html.match(/vidmoly/gi) || []).length,
    "sibnet": (html.match(/sibnet/gi) || []).length,
    "sendvid": (html.match(/sendvid/gi) || []).length,
    "voe": (html.match(/voe\./gi) || []).length,
    "cloudflare challenge": /just a moment|cf_chl_opt|challenge-platform/i.test(html),
  };
  hit(`va.${label}.markers`, probes);
  summary.steps[`voir-anime.${label}`] = { probes, bytes: html.length };
  return probes;
}

function firstMatch(html, re) {
  const m = html.match(re);
  return m ? m[0] : null;
}

async function voiranime() {
  // ---- B1. catalogue / homepage -------------------------------------------
  section("voir-anime.catalogue");
  const home = await req(`${VA}/`, { headers: vaHeaders });
  hit("va.home.status", home.status);
  dump("va-home.html", home.text);
  if (home.status === 200) {
    vaStructure(home.text, "home");
    const card = firstMatch(home.text, /<div class="page-item-detail[\s\S]{0,900}?<\/div>\s*<\/div>/i);
    dump("va-home-card.txt", card || "(no .page-item-detail block)");
    hit("va.home.firstCard", clip(card || "none", 600));
    hit("va.home.animeLinks", [...new Set([...home.text.matchAll(/https:\/\/voir-anime\.to\/anime\/[a-z0-9-]+\//gi)].map((m) => m[0]))].slice(0, 6));
  }

  // ---- B2. search ---------------------------------------------------------
  section("voir-anime.search");
  const searchUrl = `${VA}/?s=one+piece`;
  const s = await req(searchUrl, { headers: vaHeaders });
  hit("va.search.status", s.status);
  dump("va-search-one-piece.html", s.text);
  if (s.status === 200) {
    vaStructure(s.text, "search");
    const titles = [...s.text.matchAll(/<h3 class="h4">\s*<a[^>]*href="([^"]+)"[^>]*>([^<]*)<\/a>/gi)].slice(0, 8).map((m) => ({ href: m[1], title: m[2].trim() }));
    hit("va.search.h3.h4 results", titles);
    const anyTitles = [...s.text.matchAll(/<a[^>]+href="(https:\/\/voir-anime\.to\/anime\/[^"]+)"[^>]*title="([^"]*)"/gi)].slice(0, 8).map((m) => ({ href: m[1], title: m[2] }));
    hit("va.search.anchorsWithTitle", anyTitles);
    // VF flag = slug suffix (structural guarantee, audit §8.9)
    const vf = anyTitles.filter((t) => /-vf\/$/.test(t.href));
    hit("va.search.vfSlugCount", vf.length);
  }

  // ---- B3. detail page ----------------------------------------------------
  section("voir-anime.detail");
  const detailUrl = `${VA}/anime/one-piece-vf/`;
  const d = await req(detailUrl, { headers: vaHeaders });
  hit("va.detail.status", { url: detailUrl, status: d.status });
  dump("va-detail-one-piece-vf.html", d.text);
  if (d.status === 200) {
    vaStructure(d.text, "detail");
    hit("va.detail.title", firstMatch(d.text, /<h1[^>]*class="[^"]*entry-title[^"]*"[^>]*>([\s\S]{0,200}?)<\/h1>/i));
    hit("va.detail.titleFallback", firstMatch(d.text, /<h1[^>]*>([\s\S]{0,160}?)<\/h1>/i));
    hit("va.detail.synopsis", clip(firstMatch(d.text, /<div[^>]*class="[^"]*(summary|description)[^"]*"[^>]*>[\s\S]{0,600}?<\/div>/i) || "none", 500));
    hit("va.detail.cover", firstMatch(d.text, /<img[^>]+class="[^"]*(wp-post-image|img-responsive)[^"]*"[^>]*>/i));
    hit("va.detail.ogImage", firstMatch(d.text, /<meta property="og:image" content="([^"]+)"/i));
    hit("va.detail.episodeCountMarkers", {
      eplister: (d.text.match(/eplister/g) || []).length,
      chapterItem: (d.text.match(/chapter-item/g) || []).length,
      episodeLinks: (d.text.match(/-\d+-(vf|vostfr)\//gi) || []).length,
    });
    const epBlock = firstMatch(d.text, /<ul class="eplister[\s\S]{0,2500}?<\/ul>/i) || firstMatch(d.text, /<div class="episode-list[\s\S]{0,2500}?<\/div>/i);
    dump("va-detail-episode-list.txt", epBlock || "(episode list block not matched)");
    hit("va.detail.episodeListSnippet", clip(epBlock || "none", 900));
    const eps = [...d.text.matchAll(/<a[^>]+href="(https:\/\/voir-anime\.to\/anime\/[^"]*?\/([^"/]+))\/"[^>]*>([\s\S]{0,200}?)<\/a>/gi)]
      .map((m) => ({ url: m[1], slug: m[2], snippet: clip(m[3], 120) }))
      .filter((e) => /-\d+-(vf|vostfr)$/i.test(e.slug))
      .slice(0, 5);
    hit("va.detail.firstEpisodesParsed", eps);
    summary.steps["voir-anime.detail"].firstEpisodes = eps;
  }

  // ---- B4. episode page → mirrors ----------------------------------------
  section("voir-anime.episode");
  const epUrl = d.status === 200
    ? (summary.steps["voir-anime.detail"].firstEpisodes?.[0]?.url || `${VA}/anime/one-piece-vf/one-piece-1000-vf/`)
    : `${VA}/anime/one-piece-vf/one-piece-1000-vf/`;
  const ep = await req(epUrl, { headers: vaHeaders });
  hit("va.episode.status", { url: epUrl, status: ep.status });
  dump("va-episode.html", ep.text);
  if (ep.status === 200) {
    vaStructure(ep.text, "episode");
    const iframes = [...ep.text.matchAll(/<iframe[^>]*>/gi)].map((m) => m[0]);
    hit("va.episode.iframes", iframes.slice(0, 6));
    const embeds = [...new Set([...ep.text.matchAll(/https?:\/\/[^\s"'<>]+\/embed-[a-z0-9-]+\.html/gi)].map((m) => m[0]))];
    hit("va.episode.embedUrls", embeds.slice(0, 10));
    const players = [...ep.text.matchAll(/<li[^>]*class="[^"]*(player|source|mirror)[^"]*"[\s\S]{0,300}?<\/li>/gi)].map((m) => clip(m[0], 240));
    hit("va.episode.playerListItems", players.slice(0, 8));
    const dataAttr = firstMatch(ep.text, /<[^>]*data-(player|embed|source|url)[^>]*>/gi);
    hit("va.episode.firstDataAttr", clip(dataAttr || "none", 400));
    summary.steps["voir-anime.episode"].embedUrls = embeds.slice(0, 10);
  }
  return { episodeUrl: epUrl, embeds: summary.steps["voir-anime.episode"]?.embedUrls || [] };
}

// ===========================================================================
// C. nakanime.tv — VOSTFR fallback layers
// ===========================================================================
const NK = "https://nakanime.tv";
const XORMAGIC = "nkapiv1";

function nakanimeKey(pathWithQuery) {
  const n = XORMAGIC + pathWithQuery;
  const key = Buffer.alloc(32);
  for (let v = 0; v < 32; v++) {
    let g = 0;
    for (let q = 0; q < n.length; q++) g = (g * 31 + n.charCodeAt(q) + v) & 255;
    key[v] = g;
  }
  return key;
}
function nakanimeDecode(body, pathWithQuery) {
  const key = nakanimeKey(pathWithQuery);
  const out = Buffer.alloc(body.length);
  for (let i = 0; i < body.length; i++) out[i] = body[i] ^ key[i % key.length];
  return out.toString("utf8");
}

async function nakanime() {
  const nkHeaders = { ...FR_HEADERS, Referer: `${NK}/` };

  // ---- C1. catalogue page -------------------------------------------------
  section("nakanime.catalogue");
  const home = await req(`${NK}/`, { headers: nkHeaders });
  hit("nk.home.status", home.status);
  dump("nk-home.html", home.text);
  if (home.status === 200) {
    hit("nk.home.markers", {
      bytes: home.text.length,
      isReactSpa: /<div id="(root|__next|app)"/i.test(home.text),
      hasNextData: /__NEXT_DATA__/.test(home.text),
      hasNuxt: /__NUXT__/.test(home.text),
      inlineJson: (home.text.match(/<script[^>]*type="application\/json"[^>]*>/gi) || []).length,
      animeLinks: [...new Set([...home.text.matchAll(/href="(\/anime\/[^"]+)"/g)].map((m) => m[1]))].slice(0, 8),
      cards: (home.text.match(/class="[^"]*(card|anime-card|poster)[^"]*"/gi) || []).length,
      title: firstMatch(home.text, /<title>([\s\S]*?)<\/title>/i),
    });
  }

  // ---- C1b. plain HTML page (SEO/SSR check) ------------------------------
  const browse = await req(`${NK}/anime`, { headers: nkHeaders });
  hit("nk.browse.status", browse.status);
  dump("nk-browse.html", browse.text.slice(0, 400000));
  if (browse.status === 200) {
    hit("nk.browse.markers", {
      bytes: browse.text.length,
      animeLinks: (browse.text.match(/href="\/anime\//g) || []).length,
      cards: (browse.text.match(/class="[^"]*(card|poster)[^"]*"/gi) || []).length,
    });
  }

  // ---- C2. search API (XOR-encrypted JSON) --------------------------------
  section("nakanime.search");
  const q = "one piece";
  const searchPath = `/api/catalog/search?q=${encodeURIComponent(q)}&sort=relevance&page=1&per_page=10`;
  const sr = await req(`${NK}${searchPath}`, { headers: { ...nkHeaders, Accept: "*/*" } });
  hit("nk.search.status", sr.status);
  if (sr.status === 200) {
    const decoded = nakanimeDecode(sr.raw, searchPath);
    dump("nk-search-decoded.json", decoded);
    hit("nk.search.decodedHead", clip(decoded, 700));
    try {
      const j = JSON.parse(decoded);
      const items = j?.data || [];
      hit("nk.search.items", items.slice(0, 4).map((it) => ({ id: it.id, slug: it.slug, title: it.title, language: it.language, type: it.type })));
      summary.steps["nakanime.search"].firstItem = items[0];
    } catch (e) {
      miss("nk.search.json", e.message);
    }
  }

  // ---- C3. episode page (seasons script) / API ---------------------------
  section("nakanime.episode");
  const id = summary.steps["nakanime.search"]?.firstItem?.id;
  if (id) {
    const pageUrl = `${NK}/anime/${id}/season/1/episode/1`;
    const pg = await req(pageUrl, { headers: nkHeaders });
    hit("nk.episode.status", { url: pageUrl, status: pg.status });
    dump("nk-episode-1.html", pg.text);
    if (pg.status === 200) {
      hit("nk.episode.markers", {
        bytes: pg.text.length,
        dataEpisodeId: firstMatch(pg.text, /data-episode-id="[^"]*"/i),
        hasAnimeIdScript: /"animeId"/.test(pg.text),
        seasonsScripts: (pg.text.match(/"seasons"/g) || []).length,
      });
      const seasonsScript = [...pg.text.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
        .map((m) => m[1])
        .find((b) => b.includes("animeId") && b.includes("seasons"));
      dump("nk-seasons-script.json", seasonsScript || "(no embedded seasons script)");
      hit("nk.episode.seasonsScriptHead", clip(seasonsScript || "none", 700));
    }
    const epsPath = `/api/anime/${id}/episodes`;
    const er = await req(`${NK}${epsPath}`, { headers: { ...nkHeaders, Accept: "*/*" } });
    hit("nk.api.episodes.status", er.status);
    if (er.status === 200) {
      const decoded = nakanimeDecode(er.raw, epsPath);
      dump("nk-api-episodes.json", decoded);
      hit("nk.api.episodes.head", clip(decoded, 500));
    }
  }

  return { animeId: id };
}

// ===========================================================================
// D. Embed providers → HLS
// ===========================================================================
function embedEvidence(html, label) {
  const ev = {
    bytes: html.length,
    scriptTags: (html.match(/<script/gi) || []).length,
    packedDeanEdwards: /eval\(function\(p,a,c,k,e,d\)/.test(html),
    packedCanonicalTail: /\}\([^)]*\)\s*,\s*\d+\s*,\s*\d+\s*,\s*'[^']*'\.split\('\|'\)\s*,\s*0\s*,\s*\{\}\s*\)/.test(html),
    sourcesArray: firstMatch(html, /sources:\s*\[[\s\S]{0,300}?\]/i),
    jwplayerFile: firstMatch(html, /file:\s*["'][^"']+["']/i),
    m3u8InPage: [...new Set([...html.matchAll(/https?:\\?\/\\?\/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*/gi)].map((m) => m[0].replace(/\\\//g, "/")))].slice(0, 6),
    mp4InPage: [...new Set([...html.matchAll(/https?:\\?\/\\?\/[^\s"'<>\\]+\.mp4[^\s"'<>\\]*/gi)].map((m) => m[0].replace(/\\\//g, "/")))].slice(0, 4),
    evalBase64Blob: (html.match(/eval\(/g) || []).length,
  };
  hit(`${label}.evidence`, ev);
  return ev;
}

/** Variant listing from a master playlist: advertised labels + MEASURED sizes. */
async function hlsVariants(masterText, masterUrl, headers, label) {
  const lines = masterText.split(/\r?\n/);
  const variants = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
    const next = (lines[i + 1] || "").trim();
    if (!next || next.startsWith("#")) continue;
    const bw = Number((line.match(/BANDWIDTH=(\d+)/i) || [])[1] || 0);
    const res = (line.match(/RESOLUTION=(\d+x\d+)/i) || [])[1] || "?";
    const codecs = (line.match(/CODECS="([^"]*)"/i) || [])[1] || "";
    const url = new URL(next, masterUrl).toString();
    const pl = await req(url, { headers });
    const isMedia = /#EXTINF/.test(pl.text);
    const durations = [...pl.text.matchAll(/#EXTINF:([\d.]+)/g)].map((m) => Number(m[1]));
    const duration = durations.reduce((a, b) => a + b, 0);
    const segs = pl.text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
    const segUrl = segs[0] ? new URL(segs[0], url).toString() : null;
    const measuredFirst = segUrl ? await measure(segUrl, headers) : { bytes: 0, status: 0 };
    variants.push({
      advertised: { RESOLUTION: res, BANDWIDTH: bw, CODECS: codecs },
      mediaPlaylistStatus: pl.status,
      isMediaPlaylist: isMedia,
      segmentCount: segs.length,
      measured: {
        durationSeconds: Number(duration.toFixed(2)),
        firstSegmentBytes: measuredFirst.bytes,
        firstSegmentStatus: measuredFirst.status,
        firstSegmentMethod: measuredFirst.method,
        // measured bytes/sec from the first segment, applied to the measured duration
        measuredTotalBytes: duration > 0 && measuredFirst.bytes > 0
          ? Math.round((measuredFirst.bytes / (durations[0] || 1)) * duration)
          : 0,
        advertisedTotalBytes: bw > 0 && duration > 0 ? Math.round((bw * duration) / 8) : 0,
      },
      url,
    });
  }
  hit(`${label}.variants`, variants.map((v) => ({
    q: v.advertised.RESOLUTION,
    bw: v.advertised.BANDWIDTH,
    segs: v.segmentCount,
    dur: v.measured.durationSeconds,
    measuredMB: +(v.measured.measuredTotalBytes / 1048576).toFixed(1),
    advertisedMB: +(v.measured.advertisedTotalBytes / 1048576).toFixed(1),
  })));
  return variants;
}

/** Referer/Origin matrix: which header set unblocks master, variant, segment. */
async function refererMatrix(urls, candidates, label) {
  const results = [];
  for (const [name, hdrs] of Object.entries(candidates)) {
    const row = { candidate: name };
    for (const [kind, url] of Object.entries(urls)) {
      if (!url) continue;
      const r = await req(url, { headers: { ...FR_HEADERS, ...hdrs }, timeout: 12000 });
      row[kind] = r.status;
    }
    results.push(row);
  }
  hit(`${label}.refererMatrix`, results);
  return results;
}

async function embeds(embedUrls) {
  section("embeds");
  const captured = [];
  for (const url of embedUrls.slice(0, 6)) {
    const host = new URL(url).host.replace(/[^a-z0-9.-]/gi, "_");
    const r = await req(url, { headers: { ...FR_HEADERS, Referer: "https://voir-anime.to/" } });
    hit(`embed.${host}.status`, r.status);
    dump(`embed-${host}.html`, r.text);
    if (r.status !== 200) continue;
    const ev = embedEvidence(r.text, `embed.${host}`);
    const playerOrigin = `https://${new URL(url).host}`;
    // Unpack Dean-Edwards (canonical + non-canonical tails) without eval.
    const unpacked = unpackDeanEdwardsLocal(r.text);
    dump(`embed-${host}-unpacked.js`, unpacked.slice(0, 200000));
    const combined = r.text + "\n" + unpacked;
    const m3u8 = [...new Set([...combined.matchAll(/https?:\\?\/\\?\/[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*/gi)].map((m) => m[0].replace(/\\\//g, "/")))];
    const relM3u8 = [...new Set([...combined.matchAll(/["'(\s](\/[^\s"'<>]*\.(?:m3u8|txt)[^\s"'<>]*)/gi)].map((m) => m[1]))];
    hit(`embed.${host}.m3u8`, { absolute: m3u8.slice(0, 4), relative: relM3u8.slice(0, 4), unpackedBytes: unpacked.length, evalCount: ev.evalBase64Blob });
    const manifestUrl = m3u8.find((u) => /master/i.test(u)) || m3u8[0] || (relM3u8[0] ? new URL(relM3u8[0], playerOrigin).toString() : null);
    if (!manifestUrl) {
      miss(`embed.${host}.manifest`);
      continue;
    }
    hit(`embed.${host}.manifestUrl`, manifestUrl);
    const playerReferer = { Referer: url, Origin: playerOrigin };
    let master = await req(manifestUrl, { headers: { ...FR_HEADERS, ...playerReferer } });
    if (master.status !== 200) {
      const alt = { Referer: `${playerOrigin}/`, Origin: playerOrigin };
      master = await req(manifestUrl, { headers: { ...FR_HEADERS, ...alt } });
      hit(`embed.${host}.manifestRetry`, { status: master.status, headers: Object.keys(alt) });
    }
    hit(`embed.${host}.manifestStatus`, master.status);
    dump(`embed-${host}-master.m3u8`, master.text);
    if (master.status === 200 && /#EXT/.test(master.text)) {
      const isMaster = /#EXT-X-STREAM-INF/.test(master.text);
      const variants = isMaster
        ? await hlsVariants(master.text, manifestUrl, { ...FR_HEADERS, ...playerReferer }, `embed.${host}`)
        : [{ url: manifestUrl, note: "media playlist (single rendition)", advertised: {}, measured: {}, segmentCount: (master.text.match(/#EXTINF/g) || []).length }];
      const firstSeg = (() => {
        const segs = master.text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
        return segs[0] ? new URL(segs[0], manifestUrl).toString() : null;
      })();
      const matrix = await refererMatrix(
        { master: manifestUrl, variant: variants[0]?.url, segment: firstSeg },
        {
          "voir-anime (site)": { Referer: "https://voir-anime.to/" },
          "player origin": playerReferer,
          "player origin slash": { Referer: `${playerOrigin}/`, Origin: playerOrigin },
          "vidmoly.biz": { Referer: "https://vidmoly.biz/", Origin: "https://vidmoly.biz" },
          "no referer": {},
        },
        `embed.${host}`
      );
      captured.push({ url, manifestUrl, variants, matrix, isMaster });
    }
  }
  summary.steps.embeds.captured = captured;
  return captured;
}

/** Dean-Edwards unpacker (accepts canonical `.split('|'),0,{}))` tails — audit R4). */
function unpackDeanEdwardsLocal(html) {
  let out = "";
  const re = /eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?return\s+p;?\}\((?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\.split\(['"]\|['"]\)(?:\s*,\s*[^)]*)?\)/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    try {
      const pRaw = m[1] ?? m[2];
      const a = parseInt(m[3], 10);
      const c = parseInt(m[4], 10);
      const k = (m[5] ?? m[6]).split("|");
      let p = decodeJsString("'" + pRaw + "'");
      let count = c;
      while (count--) if (k[count]) p = p.split(new RegExp("\\b" + count.toString(a) + "\\b", "g")).join(k[count]);
      out += "\n" + p;
    } catch { /* ignore */ }
  }
  return out;
}
function decodeJsString(lit) {
  const body = lit.slice(1, -1);
  return body
    .replace(/\\x([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\n/g, "\n")
    .replace(/\\(['"\\/])/g, "$1");
}

// ===========================================================================
// E. main
// ===========================================================================
async function main() {
  await versions();
  let va = { embeds: [] };
  try {
    va = await voiranime();
  } catch (e) {
    miss("voir-anime", e.message);
  }
  let nk = {};
  try {
    nk = await nakanime();
  } catch (e) {
    miss("nakanime", e.message);
  }
  try {
    await embeds(va.embeds || []);
  } catch (e) {
    miss("embeds", e.message);
  }

  // Direct probe of the mirror hosts named by the task (independent of the site)
  section("direct-probe");
  const direct = {
    vidmoly: await req("https://vidmoly.to/", { headers: FR_HEADERS }),
    "vidmoly.biz": await req("https://vidmoly.biz/", { headers: FR_HEADERS }),
    voe: await req("https://voe.sx/", { headers: FR_HEADERS }),
    voembed: await req("https://voembed.net/", { headers: FR_HEADERS }),
    "nakanime.tv": await req("https://nakanime.tv/", { headers: FR_HEADERS }),
  };
  for (const [k, v] of Object.entries(direct)) {
    hit(`probe.${k}`, { status: v.status, bytes: v.bytes, server: v.headers?.server, cf: v.headers?.["cf-ray"] ? true : false });
  }

  summary.node = process.version;
  summary.va = va;
  summary.nak = nk;
  writeFileSync(`${OUT}/recon-summary.json`, JSON.stringify(summary, null, 2));
  log("recon complete");
}

main().catch((e) => {
  console.error("FATAL", e);
  writeFileSync(`${OUT}/recon-summary.json`, JSON.stringify({ ...summary, fatal: String(e) }, null, 2));
  process.exitCode = 0; // never fail the job: partial evidence is still evidence
});
