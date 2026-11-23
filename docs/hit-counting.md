# Hit counting & route matching (`runtime` package)

How BurpSpecDrift counts, per endpoint, how many times a matching request has been
seen — the data behind the "Proxy Hits / Repeater Hits / Other Hits" columns and,
ultimately, the coverage/diff feature ("which routes have I barely tested?").

This document records the **design, and especially its limitations and assumptions**,
because the counts are deliberately *approximate*: they answer "have I already
exercised this route?", not "exactly how many times". Correctness is traded for
performance and simplicity where it does not matter for that question.

All of this lives in the `runtime` package (engine + matcher). `gui/` stays GUI-only;
`model/` holds the generic data (`EndpointRow` carries the three counters).

## Sources

Traffic is attributed to one of three counters, by originating Burp tool:

| Counter        | Source                                 | Mechanism                              |
|----------------|----------------------------------------|----------------------------------------|
| `proxyHits`    | Proxy                                  | HTTP handler (live) + history backfill |
| `repeaterHits` | Repeater                               | HTTP handler (live) only               |
| `otherHits`    | everything else (Intruder, Scanner, …) | HTTP handler (live) only               |

A single `HttpHandler` (`handleHttpRequestToBeSent`) inspects `toolSource().toolType()`:
`PROXY → proxyHits`, `REPEATER → repeaterHits`, anything else `→ otherHits`.

Repeater/Other are **live only**: requests made while the extension is unloaded are
lost and cannot be recovered (Burp exposes no persistent history for those tools).
This is an accepted limitation.

## Why the proxy is special

The proxy is the one tool with a **fully persisted history** (`proxy().history()`),
and each entry has a stable numeric `id()` (contiguous, 1-based, ascending). So proxy
traffic missed while unloaded *can* be recovered by scanning the history. The proxy is
therefore counted by **both** the live handler and a history backfill, coordinated by
an offset so nothing is counted twice.

### The offset (`lastProxyId`, per tab)

Each tab stores how far into the proxy history it has already counted
(`lastProxyId` / `processedCount`), in `PersistedTab`. Per-tab (not global) on purpose:
loading a spec into a tab mid-session must re-scan the *existing* history for that tab,
independently of the others.

### When `history()` is read — and when it is NOT

`proxy().history()` rebuilds the whole list on every call (O(n)). To avoid paying that
repeatedly, it is read **only** on user-driven, infrequent events:

- **startup** — backfill the gap accumulated while unloaded;
- **spec load into a tab** — backfill that tab against the existing history;
- **unload** — one read to pin the exact offset (see drift below).

During a live session there are **zero** `history()` calls. The handler counts in O(1)
per request.

### Derived offset (the trick that keeps it crash-safe without polling history)

Because proxy history ids are contiguous and roughly one id is created per proxy
request, the offset does not need to be re-read from the history during the session.
Starting from `baseId = max id` seen at the last backfill, the handler does
`lastProxyId++` on every proxy request. So `lastProxyId` is always available in O(1),
and can be persisted **together with the counters** at every flush.

This makes persistence a consistent checkpoint:

- **Clean unload** → read the history once, set the *exact* offset (corrects any drift),
  persist. Next startup only replays the while-unloaded gap.
- **Crash** → roll back to the last consistent checkpoint. The in-memory increments lost
  with the crash were never persisted, so the next startup's backfill replays exactly
  that tail **once** — no duplication, no skew.

### Backfill: fast path + fallback

Normal case (no deletions): `history.subList(processedCount, size)` — an O(1) view over
the new tail. If the history shrank (`size < processedCount`) or the first expected entry's
`id()` is misaligned — i.e. the user used *Delete item* / *Clear history* in Burp — fall
back to `history.filter { it.id() > lastProxyId }`.

### Collapsing per-tab backfills

A backfill reads `history()` **once** and iterates a single time from
`minFrontier = min(processedCount over tabs with a spec)`; each entry is matched only
against the tabs whose frontier is behind it. Steady state: every tab matched against the
small new tail. A freshly added tab drops `minFrontier` to its value (e.g. 0) and gets its
backfill in the same pass.

## Matching

A request matches a route when:

- **method** is equal, and
- **host** equals the host of the route's *effective* base (global/per-route overrides
  applied); the port is ignored unless non-default, the scheme is ignored, and
- **path** (without query string) matches the route's path template compiled to a regex:
  `{param}` → `[^/]+`, prefixed with the server base-path if any.

Special cases / rules:

- **No base (`<none>`)** on a route → host is ignored; match on **path + method** only
  (matches any host). Risk of cross-host over-counting, accepted; the user can set an
  override base to tighten it.
- **Ambiguous matches within one spec** (e.g. `/user/me` vs `/user/{id}`) → only the
  **most specific** route (fewest wildcards) is incremented, to avoid inflating counts.
- Matching runs against the routes of **all tabs** (coverage is per-tab). Regexes are
  precompiled and indexed by method.
- **Scope**: all traffic is counted; Burp's in-scope filter is not applied.

## Threading

All counter mutations happen on a **single-threaded executor**: the HTTP handler (Burp
threads) and the backfill post work to it, so the counters stay plain `Int` with no locks
and the serialization model is unchanged. The table is repainted on the EDT, throttled.

## Persistence

- Counters live in `EndpointRow` (already `@Serializable`); `lastProxyId` /
  `processedCount` are added to `PersistedTab`.
- Each flush writes **counters + offset together** (consistent checkpoint). The periodic
  30s flush already in place is reused; it needs no `history()` call thanks to the derived
  offset.
- Loading a new spec into a tab resets that tab's counters and offset → full re-backfill.

## GUI

The Proxy/Repeater/Other Hits columns reflect the live counters via a throttled repaint.

## Limitations & assumptions (read this)

1. **Counts are approximate**, by design. They support "have I already seen this route?",
   not exact call counts.
2. **Repeater/Other are live-only.** Traffic while the extension is unloaded is lost and
   unrecoverable.
3. **Derived-offset assumes ~1 proxy request = 1 history entry.** If that ratio is off
   (dropped-in-intercept requests, non-logged traffic, WebSocket, etc.), `lastProxyId`
   drifts slightly from the true id. Impact is limited to a few duplicated/missed entries,
   and **only after a crash** — a clean unload re-syncs the exact offset.
4. **Startup gap race.** Between snapshotting the history at startup and registering the
   handler, a request may slip through uncounted. Bounded and tiny; we prefer a small
   under-count (the tester re-checks, harmless) to an over-count (a real gap looks covered).
5. **History edits.** *Delete item* / *Clear history* in Burp break the contiguous-id
   assumption; handled by the fallback scan, but after a *Clear* the counts for already
   processed entries are not rebuilt (we only move forward).
6. **`history()` is O(n) to build.** Mitigated by only calling it on startup / spec-load /
   unload, never on a timer.
7. **Host matching is lenient** (port/scheme ignored; `<none>` base ignores host), which
   can over-count across environments sharing paths.
8. **Most-specific-wins** can still be ambiguous if two templates are equally specific;
   resolution between equally-specific matches is unspecified (first match wins).

## Possible future improvements

- Optional in-scope-only counting.
- Stricter host/port/scheme matching as a toggle.
- Periodic exact offset re-sync (bounded extra `history()` reads) if crash accuracy ever
  matters more than it does today.
- Native XML/other body generation is unrelated but similarly deferred (see importer).
