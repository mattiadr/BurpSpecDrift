# Testing hit counting — step by step

Manual/integration test plan for the `runtime` hit-counting feature (see
`docs/hit-counting.md` for the design). It drives real proxy traffic through Burp and
compares the extension's counters against a known request plan.

Pieces:
- `examples/coverage-test.yaml` — the spec under test (varied bases, params, overrides).
- `scripts/hit_counting_test.py` — starts a local web server and fires ~437 proxied
  requests (342 that should match a route, 95 noise), then prints the expected proxy
  hits per route.
- **SpecDrift → Copy Hits to Clipboard** (menu bar) — copies the actual counters as
  `method <tab> path <tab> proxy <tab> repeater <tab> other <tab> tab`.
- The RouteMatcher unit tests (`./gradlew test`) already cover the pure matching logic;
  this document covers the live/stateful behaviour that needs Burp.

The pure-logic sanity check is `./gradlew test`. Everything below needs Burp running.

## Prerequisites

- Burp Suite with the SpecDrift extension loaded (build the jar yourself, as usual).
- Burp **Proxy** listener on `127.0.0.1:8080` (default) and **Intercept OFF**.
- Python 3 (standard library only). The script starts its own web server on `:8000`.
- If `:8000` is taken, pass `--server-port N` to the script and edit the server port in
  `examples/coverage-test.yaml` to match (two `localhost:PORT` / `127.0.0.1:PORT` lines).

Conventions used below:
- **[You]** = an action you perform in Burp or a terminal.
- **[Expect]** = what should be true afterwards.

To compare: run **Copy Hits to Clipboard**, paste into a text editor, and line up the
`proxy` column with the script's "EXPECTED proxy hits" block. The counts should be
**equal**; `repeater` and `other` should be **0** for all script-only tests.

---

## Test 0 — Baseline match (the happy path)

1. **[You]** In SpecDrift, open a tab and **Load from File** → `examples/coverage-test.yaml`.
2. **[Expect]** The table fills with the routes; the Base column shows
   `http://localhost:8000/api/v1` for most, and `http://127.0.0.1:8000` for `GET /legacy`.
3. **[You]** Run: `python3 scripts/hit_counting_test.py`. Wait for "Done: 437 sent, 0 failed".
4. **[You]** SpecDrift menu → **Copy Hits to Clipboard**, paste, compare the proxy column.
5. **[Expect]** Exactly:
   - `GET /users` = 40, `POST /users` = 30
   - `GET /users/{id}` = 60, `DELETE /users/{id}` = 20
   - `GET /users/me` = 25 (the ambiguity test: the 25 `/users/me` hits must land here, **not**
     on `/users/{id}`)
   - `GET /users/{id}/posts/{postId}` = 35
   - `GET /files/{name}.json` = 28
   - `GET /health` = 50, `GET /search` = 32, `GET /legacy` = 22
   - every `repeater`/`other` = 0
6. **[Expect]** No route is over-counted by the 95 noise requests (wrong method/host/case,
   trailing slash, wrong extension, extra segment, missing base-path). If any count is higher
   than above, a noise case is matching when it shouldn't.

> If `failed > 0`: the proxy isn't listening on `127.0.0.1:8080` or intercept is on.

## Test 1 — Live incrementing

1. **[You]** Without reloading anything, run the script again.
2. **[Expect]** Every count **doubles** (80, 60, 120, …). Counts update live (within ~1s of
   the traffic) — you can watch the Hits columns change while the script runs.

## Test 2 — Persistence across reload (no double counting)

1. **[You]** Note the current counts (Copy Hits).
2. **[You]** Burp → Extensions → untick and re-tick the SpecDrift extension (reload), **without**
   sending any traffic in between.
3. **[Expect]** After reload the counts are **unchanged** (restored from the project file). The
   proxy history already seen is **not** re-counted (offset was persisted).
4. **[You]** Run the script once more.
5. **[Expect]** Counts increase by exactly one run's worth again — the backfill picked up only the
   new history entries, nothing double-counted.

## Test 3 — Backfill of pre-existing history (mid-session load)

This is the "I added a spec halfway through" case.

1. **[You]** Close/reset: remove the spec tab (or start a fresh Burp project) so there is **no**
   loaded spec, but first generate traffic: run the script once with no spec loaded.
2. **[Expect]** Nothing to count yet (no routes); the requests are in Burp's proxy history.
3. **[You]** Now **Load from File** → `coverage-test.yaml` into a tab.
4. **[Expect]** Within ~1s the counts jump straight to the full expected values **from the existing
   history** — the tab backfilled the old traffic it never saw live.

## Test 4 — Two tabs / overlapping specs

1. **[You]** With the spec loaded in tab 1, open tab 2 and load `coverage-test.yaml` again.
2. **[You]** Run the script once.
3. **[Expect]** Both tabs show the same counts for the run (each tab counts independently against
   its own routes).

## Test 5 — Repeater and Other

1. **[You]** Right-click a route in the table → **Send to Repeater**; in Repeater, send it a few
   times (use a URL that matches, e.g. `GET http://localhost:8000/api/v1/health`).
2. **[Expect]** That route's **repeater** column increments; proxy/other unchanged.
3. **[You]** Right-click → **Send to Intruder**; run a short attack hitting a matching route.
4. **[Expect]** The **other** column increments for that route.
   (Repeater/Other are live-only: traffic sent while the extension is unloaded is not recovered.)

## Test 6 — Overrides change future matching only

1. **[You]** Set a **global Base Override** (toolbar) to a wrong host, e.g.
   `http://wrong.example/api/v1`.
2. **[You]** Run the script.
3. **[Expect]** The localhost routes get **no new proxy hits** (traffic host no longer matches the
   effective base); existing counts are untouched. `GET /legacy` still matches (its per-route base
   is unaffected by the global override unless you override it too).
4. **[You]** Clear the override; run again → counts resume increasing.

## Test 7 — Proxy history deletions (fallback path)

1. **[You]** In Burp **Proxy → HTTP history**, select some entries and **Delete item** (or
   **Clear history**).
2. **[You]** Generate more traffic (run the script).
3. **[Expect]** No crash; counts for the new traffic still increase correctly. After a deletion the
   per-tab offset no longer lines up with list indices, so the extension falls back to an id-based
   scan — the new requests are still counted. (Counts already recorded for deleted entries are not
   rebuilt; we only move forward. This is the documented limitation.)

## Test 8 — Crash / unclean shutdown (approximate, no duplication)

1. **[You]** Run the script, let counts settle, then **kill Burp** (don't close cleanly) within a
   few seconds of traffic.
2. **[You]** Reopen the same project.
3. **[Expect]** Counts are at roughly the last checkpoint and the startup backfill replays the lost
   tail **once** — no large duplication. Small drift (a few hits) around the crash boundary is
   expected and acceptable per the design doc.

## Noise / edge cases (already baked into the script)

The 95 noise requests exercise, and must **not** increment anything:
- extra segment past a param (`/users/5/extra`), wrong extension (`/files/report.txt`),
- method not defined on the path (`POST /users/5`), trailing slash (`/health/`),
- wrong case (`/Users/5`), unknown path (`/nonexistent`),
- `/legacy` on the wrong host (localhost vs 127.0.0.1), users path on the wrong host,
- request missing the `/api/v1` base-path.

If the totals in Test 0 match exactly, all of these were correctly rejected.

## Resetting between runs

Counts accumulate across script runs and persist in the project file. To start clean: use a fresh
Burp project, or reload a spec into the tab (loading a spec resets that tab's counters and offset,
then re-backfills from whatever is in the current proxy history).
