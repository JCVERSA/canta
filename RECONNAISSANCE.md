# Reconnaissance — voir-anime.to, nakanime.tv and their players

**Date of the captured evidence: 2026-09-20.** Every selector, URL, status code,
byte count and variant table below was observed during a live run of
`tools/recon/recon.mjs`, executed on a GitHub Actions runner (the sandbox used
for development cannot reach these hosts). The raw captures are committed under
[`tools/recon/evidence/`](tools/recon/evidence/) and listed in
[`tools/recon/evidence/INDEX.txt`](tools/recon/evidence/INDEX.txt); the JSON
reports are `recon-summary.json` (pass 1), `recon2-report.json` (pass 2) and
`recon3-report.json` (pass 3).

Nothing in this document is an estimate. Where a value could not be measured it
is written as *not measured*, not guessed. Where a site is reached through
JavaScript that was not executed, the document says so.

Re-run it yourself:

```bash
gh workflow run recon.yml          # publishes tools/recon/evidence/* and the JSON reports
```

---

## 1. Target 1 — `voir-anime.to` (primary catalogue, VF + VOSTFR)

| Probe | Result |
| --- | --- |
| `GET https://voir-anime.to/` | **200**, 215 256 bytes |
| WordPress markers | `wp-content` present, Madara theme selectors present |
| Cloudflare challenge | **none** (HTML is served to a datacenter IP without a browser) |
| `GET /wp-json/` | **403** (Cloudflare) — the REST API is not an option, HTML only |
| Home cards `div.page-item-detail` | 20 per page |
| `div.c-tabs-item__content` on home | 8 |
| `a[href*="/anime/"]` on home | 64 |
| `.eplister` / `.epl-num` (old Madara episode list) | 0 — not used by this theme |
| `li.wp-manga-chapter` rows on a detail page | 1 111 (`…/one-piece-vf/`) |
| `iframe` / `embed-*.html` on the home page | 0 (players exist only on episode pages) |

The site is a stock WordPress + Madara install served without a JS challenge.
The catalogue is therefore scrapable with Jsoup, which is what
`VoirAnimeScraper` does.

### 1.1 Catalogue and search — exact selectors

| Purpose | Selector | Evidence |
| --- | --- | --- |
| Home / genre card | `div.page-item-detail` | 20 per page |
| Search result card | `div.row.c-tabs-item__content` | pass 2, `search.cardBlock` |
| Home title link | `h3.h5 > a[href]` | `itemSummary` capture |
| Search title link | `h3.h4 > a[href]` | pass 2, `titleAnchor` |
| Card link | `div.item-thumb a[href]` | card capture |
| Card image | `div.item-thumb img, div.tab-thumb img` | home uses `item-thumb`, search uses `tab-thumb` |
| Detail title | `div.post-title h1` | detail capture |
| Detail cover | `div.summary_image img` | detail capture |
| Detail synopsis | `div.description-summary div.summary__content` | detail capture |
| Episode row | `li.wp-manga-chapter` | 1 111 rows on `one-piece-vf` |
| Episode date | `span.chapter-release-date` | row capture |

Important shape facts:

* A search response contains **no** `div.page-item-detail` at all (pass 2:
  `rows: []`, `itemSummary: ""`), so a parser that only knows the home-card
  selector returns an empty list for every search. The app therefore tries the
  home card, then the search card, then falls back to `a[href^=/anime/]` links.
* Each season and each language is its own entry with its own slug
  (`-vf` / `-vostfr` suffix), so the language of a card is structural, not a
  guess (`splitVfSuffix` in `ScraperUtils.kt`).
* Films and OAVs carry **no episode number** in the slug
  (`Selectors.VA_EPISODE_SLUG_MOVIE` matches `-film|-oav|-movie`), which is why
  `Episode.number` stays `0` and the label becomes `Film`/`OAV`.

### 1.2 Episode page — the mirror list

On `https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/` (200):

* `div#chapter-video-frame p iframe` → `https://voembed.net/embed-zxtqco5wxp3d.html`
  (the player that is shown by default).
* `var thisChapterSources = { … }` — the site's own mirror table, four entries,
  each value an HTML string containing an escaped `<iframe src="…">` and
  terminated by `};` before `var defaultSources`. Captured verbatim in
  `va-episode.html.gz` and asserted in `VoirAnimeScraperTest`:

  | Label | Player URL | Host |
  | --- | --- | --- |
  | `LECTEUR myTV` | `https://voembed.net/embed-zxtqco5wxp3d.html` | voembed.net |
  | `LECTEUR MOON` | `https://mfw09.org/e/dhy4skfrq20x` | mfw09.org |
  | `LECTEUR VOE` | `https://voe.sx/e/8ncp6aa32n0u` | voe.sx |
  | `LECTEUR Stape` | `https://streamtape.com/e/jAwaD3OggKtzZAq` | streamtape.com |

* `select.selectpicker.host-select` — four `option[data-redirect]` values
  (duplicated in the DOM, so eight option nodes), each a **relative** redirect
  back to the same episode with a `?host=` parameter that contains a space:

  ```
  /anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR myTV
  /anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR MOON
  /anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR VOE
  /anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR Stape
  ```

  Pass 3 confirmed that each `?host=` page renders a *different* player
  (`sameAsFirst: false` for three of the four), so the host switcher is a real
  failover path: the app walks those pages in DOM order when the default page
  yields no player at all at most one extra fetch per host, stopping at the
  first page that yields one. The values contain spaces, so they are resolved
  as strings and percent-encoded *after* resolution — handing
  `?host=LECTEUR MOON` to `java.net.URI` throws.

### 1.3 Players found on the episode page

| Host | Status | Extracted | Notes |
| --- | --- | --- | --- |
| `voembed.net` | 200, 36 921 bytes | **yes** | VidMoly-family player (see §2.1) |
| `vidmoly.org` (same family, different entry point) | 200, 37 546 bytes | **yes** | two variants, see §2.1 |
| `mfw09.org` | 200, 1 972 bytes of HTML + a 282 104-byte SPA bundle | no | React SPA, 1 972 chunks, bundle contains **0** `.m3u8` references and 0 API paths; extracting it would need executing its JS, which this app does not do |
| `voe.sx` | 200, 745 bytes | no | JS redirect to `jamesbornmain.com/e/8ncp6aa32n0u` (200, 125 040 bytes, title `Watch One Piece - 1110 VF.mp4 - VOE \| …`, 0 inline payloads in the served HTML) |
| `streamtape.com` | 200 | no | no playlist in the served HTML; not pursuable without JS |

`mfw09.org` and `voe.sx` are therefore *listed*, not played: the resolver tries
them (a URL that is not an embed link simply fails to yield a playlist within
its per-host timeout) and falls through. This is the honest behaviour: the app
never claims to support a host it cannot extract.

---

## 2. Player structures and the M3U8 extraction method

### 2.1 `voembed.net` / `vidmoly.org` — VidMoly family (primary)

Both embeds are the same family. The served HTML (pass 3, `va3-embed-voembed.html.gz`)
contains a `jwplayer` setup call whose `sources[0].file` is the HLS master:

```js
player.setup({
  sources: [{ file: "https://prx-1316-ant.vmget.online/hls2/01/02824/zxtqco5wxp3d_n/master.m3u8?t=…&s=…&e=43200&…" }],
  image: "…", bitrate: "2160000", label: "720p HD", duration: "1430"
});
```

**Extraction method used by the app** (`VidmolyExtractor`):

1. Fetch the embed page with the site's headers.
2. Find the master URL in the player setup `sources[].file`
   (`Selectors.VIDMOLY_FILE`, falling back to any `*.m3u8` string with the
   `/hls2/` signature — VidMoly CDN hosts observed: `vmget.online`,
   `vmeas.cloud`, and `vmbox.space`/`vmcld.space` in the reference audit).
3. Parse the master playlist for `#EXT-X-STREAM-INF` entries (variant URL on the
   following line) and label each variant from its own `RESOLUTION=` attribute.
   Nothing else is trusted as a quality label.
4. For the chosen variant, measure the real size by `HEAD`/`Range` requests
   against its segments.

Two independent master playlists were measured in pass 3:

| Embed | Master bytes | Variants |
| --- | --- | --- |
| `voembed.net` (36 921 B page) | 625 | **one**: `1920x1080`, `BANDWIDTH=3338770`, 144 segments, 1 430.8 s |
| `vidmoly.org` (37 546 B page) | 1 163 | `1920x1080` `BANDWIDTH=3947469`, 78 segments, 1 542.6 s **and** `852x480` `BANDWIDTH=481301`, 78 segments, 1 542.6 s |

This is the finding that matters for honesty: the `voembed.net` page *labels*
its stream `720p HD`, while the playlist says `1920x1080`, and the second family
member does have a 480p rendition with a 78-segment, 1 542.6-second playlist.
`RESOLUTION=` is the only admissible label; `label:`/file names are not.

**Referer/Origin matrix (pass 3).** For both masters, the manifest and a
segment were requested with six header combinations — no referer, the
voir-anime episode page, the player origin with and without a trailing slash,
`vidmoly.biz`, `vidmoly.to`. Every combination returned `200`
(`playlistStatus: 200`, `segmentStatus: 200`, identical `segmentLength`), so on
these CDN nodes the headers are not enforced. The reference audit (§8.43) has
the opposite case (403 without the right pair) and brute-forces up to 26
path/referer combinations, so the app keeps the header ladder: it costs one
request and turns a 403 into a playable stream.

### 2.2 `voe.sx` → `jamesbornmain.com` — JS redirect chain

`https://voe.sx/e/8ncp6aa32n0u` answers 200 with 745 bytes whose only content is
a JavaScript redirect to `https://jamesbornmain.com/e/8ncp6aa32n0u`. Following
it returns 200 and 125 040 bytes, but the player payload is assembled by the
page's own script: **0** inline payloads in the served HTML (pass 2 and pass 3
agree). The Voe family is consequently implemented as *up to three hops of
redirect following plus a payload decode*; when the payload is absent, the
mirror is reported as unavailable instead of being retried forever.

### 2.3 `mfw09.org` — SPA (not extractable statically)

1 972 chunks, two scripts (`/assets/index-DocunfmE.js`, Cloudflare beacon). The
main bundle is 282 104 bytes and contains **no** `.m3u8` reference and no API
path. Documented here so the absence of support is a stated fact rather than an
omission.

---

## 3. Target 2 — `nakanime.tv` (VOSTFR / VF rescue catalogue)

The secondary catalogue is used as a cross-source rescue wheel: when every
mirror of the primary source fails, the same episode is looked up here.

| Endpoint | Method | Result |
| --- | --- | --- |
| `/` | GET | 200, 16 806 bytes, React SPA with inline JSON |
| `/anime` | GET | 200 (SEO page, `nk-browse.html.gz`) |
| `/api/catalog/search?q=…&sort=trending&page=1&per_page=6` | GET | 200, XOR-encoded JSON → `{"data":[…]}` |
| `/api/catalog/search?q=…&sort=relevance&page=1&per_page=10` | GET | 200, XOR-encoded JSON |
| `/anime/<id>/season/<s>/episode/<e>` | GET | 200, episode page with an embedded `seasons` script |
| `/api/anime/<id>/episodes` | GET | XOR-encoded JSON (episode list) |
| `/api/anime/<id>` | GET | **404** — a detail endpoint that looks plausible and does not exist |
| `/api/sources/anime` | POST | 200, XOR-encoded JSON: the player list (see below) |

### 3.1 Response encoding — exactly reproducible

The body is XOR-encoded with a 32-byte key derived from the request path
*including its query string*:

```js
const MAGIC = "nkapiv1";                       // the app's NAKANIME_XOR_MAGIC
key[v] = fold31(MAGIC + pathWithQuery, v);     // g = (g*31 + charCode + v) & 0xFF, 32 times
plain[i] = cipher[i] ^ key[i % 32];
```

Two implementation traps, both of which the app handles:

* the fold is over the **raw bytes** of the response, not a decoded string;
* the key depends on the *exact* path+query sent, so the same bytes decoded with
  a different query string produce garbage.

### 3.2 `/api/sources/anime` — measured response

Decoded verbatim (`nk2-sources-decoded.json`):

```json
[
  {"id": 99756,  "url": "https://video.sibnet.ru/shell.php?videoid=4826196", "host": "sibnet", "language": "VOSTFR", "episodeId": 35276},
  {"id": 99879,  "url": "https://video.sibnet.ru/shell.php?videoid=4833453", "host": "sibnet", "language": "VF",     "episodeId": 35276},
  {"id": 162845, "url": "https://jamesbornmain.com/e/w4fbd6fu6yix",        "host": "voe",    "language": "VOSTFR", "episodeId": 35276},
  {"id": 169556, "url": "https://jamesbornmain.com/e/n0ik5gb8a7mg",        "host": "voe",    "language": "VF",     "episodeId": 35276},
  {"id": 618222, "url": "https://vidmoly.org/embed-w9d5mepjkeoa.html",     "host": "vidmoly","language": "VOSTFR", "episodeId": 35276},
  {"id": 618340, "url": "https://vidmoly.org/embed-v3es93abbcjm.html",     "host": "vidmoly","language": "VF",     "episodeId": 35276},
  {"id": 618356, "url": "https://vidmoly.org/embed-pxcgm3x36ai4.html",     "host": "vidmoly","language": "VOSTFR", "episodeId": 35276}
]
```

Consequences for the app:

* the VOSTFR VF/VOSTFR label is taken from this field, but the reference audit
  (§8.5/§8.12) and this capture agree that it is **not reliable enough to
  auto-select**: the app never auto-picks a VF track on nakanime, it reports the
  language of the URL that actually answered;
* `sibnet` URLs are SPA shells whose inline player is built by JS; the reference
  audit (§8.4) found *fabricated* quality tracks there, so the app gives sibnet
  the lowest priority and never prints a quality it did not read from a
  playlist;
* the same episode is offered by several hosts, which is exactly what makes
  nakanime a usable rescue layer.

---

## 4. What the app does with all of this

| Finding | Where it lives in the code |
| --- | --- |
| voir-anime selectors, VF/VOSTFR slugs, film/OAV slugs | `scraper/ScraperUtils.kt` (`Selectors`), `scraper/VoirAnimeScraper.kt` |
| `thisChapterSources` escape-aware parsing | `VoirAnimeScraper.parseChapterSources` |
| host switcher walk (`?host=` pages, spaces) | `VoirAnimeScraper.parseHostOptions` / `absoluteHostPage` |
| VidMoly family master → variants → measured sizes | `extractor/VideoExtractor.kt`, `extractor/QualityGuard.kt` |
| Voe redirect chain (≤3 hops) | `extractor/VoeExtractor.kt` |
| Mirror priority VidMoly → Voe → mfw09/streamtape → generic | `VideoExtractor.hostPriority`, `MirrorResolver` |
| nakanime XOR + endpoints + honest language | `scraper/NakanimeScraper.kt` |
| No ffmpeg, Media3 HLS playback + offline cache | `manager/DownloadManager.kt`, `ui/PlayerScreen.kt` |

### Quality labels and sizes

The app derives a quality label only from `RESOLUTION=` in the master playlist,
and it reports a size only when it has asked the CDN for it (per-segment
`HEAD`/`Range`; when only a sample fits in the budget the UI marks the value
« échantillon » and says so). Two consequences are visible in the UI:

* an embed that claims `720p HD` while its playlist says `1920x1080` is shown as
  1080P;
* if a measured 480P stream exceeds the fast-lane ceiling (200 MiB, from the
  reference audit §8.13) the app downgrades to the lightest variant ≤ the
  requested height, and the downgrade decision — with both numbers — is printed
  under the player instead of being silent.

## 5. Limits of this reconnaissance — stated, not hidden

* **Snapshot.** Captured 2026-09-20. Player hosts rotate domains
  (`voembed.net` has siblings `vidmoly.*`, `vmget.online`, `vmeas.cloud`), so a
  parser must match structure, not domains. The workflows can re-run the
  capture on demand and the evidence is versioned under `tools/recon/evidence/`.
* **Not extractable statically, and therefore not promised:** `mfw09.org`
  (SPA), `streamtape.com` (no playlist in the served HTML), `voe.sx` when its
  payload script cannot be decoded, and sibnet's inline player.
* **No DRM investigation was performed** because none was encountered: every
  playlist captured was a plain HLS manifest.
* **Geo-blocking was not tested** — all requests came from GitHub Actions
  runners in one region.
* **No `/wp-json/` usage** is possible: it answers 403 behind Cloudflare while
  the HTML pages answer 200.
