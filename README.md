# SpecDrift

A [Burp Suite](https://portswigger.net/burp/pro) extension that parses an OpenAPI specification, shows its endpoints in a
table, and then **diffs the spec against the traffic you actually generate** during a test —
so you can quickly see which documented routes you have barely (or never) exercised, and
focus your testing on the gaps.

Load a spec, test as usual, and each endpoint shows how many matching requests have been
seen, split by source (Proxy / Repeater / Other). Proxy traffic is also recovered from
Burp's saved history, so routes exercised before the extension was loaded still count.

## Features

- **Import OpenAPI 2.0 / 3.0 / 3.1** (JSON or YAML) from a file, a URL, or straight from a
  Burp response via a *Send to SpecDrift* context-menu item.
- **Endpoint table** — base, method, path, auth, and per-source hit counts; sortable, with
  a master/detail view showing parameters, request/response bodies and generated examples.
- **Per-column header filters** (Excel-style) plus a path search box.
- **Base resolution & overrides** — resolves server URLs from the spec (root / path /
  operation level) and lets you override the base globally or per route.
- **Coverage counting** — matches live and historical requests to routes (method + host +
  templated path) and counts them as Proxy / Repeater / Other hits.
- **Recalculate proxy hits** — reset and recompute a tab's proxy hits from history after a
  base change.
- **Send to Repeater / Intruder** from the table (with `Ctrl+R` / `Ctrl+I`).
- **Project persistence** — tabs, overrides and counts are stored in the Burp project file.
- Multiple tabs, Repeater-style, each with its own spec.

## Limitations

Hit counts are intentionally **approximate**: they answer "have I already exercised this
route?", not an exact call count. In particular, Repeater/Other traffic made while the
extension is unloaded cannot be recovered, host matching is lenient, and a few hits may
drift around an unclean shutdown. See the design notes for the full list before relying on
the numbers.

→ **[Hit counting: design, limitations & assumptions](docs/hit-counting.md)**
(and **[step-by-step testing guide](docs/testing-hit-counting.md)**).

## Install

Requires Burp Suite 2026.7 or later.

Download the jar from the [latest release](https://github.com/mattiadr/BurpIO/releases/latest), then load it from the extensions menu in Burp Suite.

## Build

Requires a JDK 21+ (the Gradle toolchain will fetch one if needed).

```sh
./gradlew shadowJar
```

The extension jar is produced at `build/libs/BurpSpecDrift.jar` (a fat jar: it bundles the
OpenAPI parser and merges the service files its Swagger-2.0 converter needs). The Montoya
API itself is `compileOnly` and provided by Burp at runtime.

To run the unit tests:

```sh
./gradlew test
```
