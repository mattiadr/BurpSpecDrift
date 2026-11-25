# Spec import mapping (`spec` package)

How each source format is flattened into the generic model (`model` package), one
[`EndpointRow`](../src/main/kotlin/com/mattiadr/burpSpecDrift/model/EndpointRow.kt) per
operation. Everything downstream of `ImportedSpec` (table, detail view, diff, persistence)
is format-agnostic, so adding a format means writing a parser + importer that produce
`EndpointRow`s — nothing else changes.

Columns: **OpenAPI** (swagger-parser model) and **Postman** (Collection v2.1 JSON).
Bruno will be added as a third column when we implement it.

## The row-per-operation rule (and what collection formats change)

OpenAPI is **spec-shaped**: a path has at most one operation per method, so one
`(path, method)` → exactly one `EndpointRow`, and within a request body each media type
is a unique key → at most one example per content type.

Postman/Bruno are **collection-shaped**: they are lists of concrete requests, so the
*same* `(path, method)` can appear **multiple times** (e.g. "Create admin user" and
"Create guest user", both `POST /users`). We **collapse** these into a **single
`EndpointRow` per `(path, method)`** and keep each original request body as a **separate
example** on that row.

Consequence: a row can now carry **several examples with the same media type**
(two `application/json` bodies). This breaks the OpenAPI invariant of
one-example-per-content-type, so an example is identified by its **position** in
`contents`, not by its media type.

Saved responses collapse the same way (multiple examples per status code are possible).

## EndpointRow mapping

| `EndpointRow` field | OpenAPI | Postman (v2.1) |
|---|---|---|
| `method` | operation key (`get`/`post`/…) | `item.request.method` |
| `path` | path-item key, verbatim (`/users/{id}`) | `url.path[]` joined with `/`; `:id` and `{{id}}` segments normalized to `{id}` (required by `RouteMatcher`) |
| `specBase` | resolved server URL (operation → path → root precedence; variables default-substituted) | host resolved from `url.host[]`/`url.raw` via collection variables; an unresolved `{{baseUrl}}` → `null` (user supplies it via the base-override UI) |
| `alternativeBases` | the remaining resolved servers | other distinct bases seen for the same `(path, method)` across collapsed requests |
| `operationId` | `operation.operationId` | — (none) → `null` |
| `summary` | `operation.summary` | `item.name` (representative item) |
| `description` | `operation.description` | `item.request.description` |
| `tags` | `operation.tags[]` | folder path in the `item` tree (optional) |
| `deprecated` | `operation.deprecated` | — (none) → `false` |
| `securityRequirements` | `operation.security ?? root.security`, resolved against `components.securitySchemes`; `[]` = explicitly none, absent = `null` (unknown) | from `request.auth` (see [auth](#auth)); `noauth` → `[]`, absent → inherited from folder/collection auth, else `null` |
| `parameters` | merged path + operation parameters (see [parameters](#parameters)) | from `url.variable[]`, `url.query[]`, `request.header[]` |
| `requestBody` | `operation.requestBody` (see [request body](#request-body)) | `request.body` of each collapsed request → **multiple examples, possibly same media type** |
| `responses` | `operation.responses` | `item.response[]` saved examples |
| `routeOverride`, `proxyHits`, `repeaterHits`, `otherHits` | — runtime only, never from the source | — runtime only |

### parameters

One `Param` per entry.

| `Param` field | OpenAPI | Postman |
|---|---|---|
| `name` | `parameter.name` | key of the query/header/variable entry |
| `location` | `parameter.in` (`path`/`query`/`header`/`cookie`) | `query` ← `url.query[]`, `path` ← `url.variable[]`, `header` ← `request.header[]` |
| `required` | `parameter.required` | path vars: implicitly `true`; query/header: `!disabled` → included, no required concept → `false` |
| `type` | `parameter.schema.type` | — (none) → `null` |
| `description` | `parameter.description` | entry `.description` |
| `example` | priority chain from the schema (example → named example → `generateScalar` → placeholder) | the concrete `.value` in the request (verbatim) |

### request body

`Body(required, contents: List<ContentExample>)`.

| field | OpenAPI | Postman |
|---|---|---|
| `Body.required` | `requestBody.required` | — (no concept) → `false` |
| `ContentExample.mediaType` | the `content` map key (unique) | from `body.mode` / `body.options.raw.language` / the `Content-Type` header |
| `ContentExample.example` | declared example, or generated (`generateJson` / `generateFormUrlEncoded`); may be absent | the **verbatim** body: `raw` as-is, `urlencoded` → `k=v&…`, `formdata` → reconstructed; always concrete |
| `ContentExample.name` | `null` | `item.name`, to disambiguate several examples that share a media type in the dropdown |

### responses

One `ResponseInfo` per entry.

| `ResponseInfo` field | OpenAPI | Postman |
|---|---|---|
| `statusCode` | response key (`200`, `default`, …) | `response.code` (e.g. `200`) |
| `description` | `response.description` | `response.status` or the example `response.name` |
| `contents` | one `ContentExample` per media type | `response.body` + media type from the response's `Content-Type` header |

### auth

OpenAPI `securityRequirements` is an OR of AND-groups resolved against
`components.securitySchemes`. Postman `auth` is a single mechanism per request
(`bearer`/`basic`/`apikey`/`oauth2`/`digest`/…), mapped to a single-scheme requirement;
`noauth` maps to an empty requirement list (explicitly none); an absent `auth` inherits
the folder's/collection's `auth`, and falls back to `null` (unknown) when none is
declared anywhere. (Collapsed requests may disagree on auth; the representative/first
non-inherited one wins — to be refined during implementation.)

## Notes

### Variable resolution

Only **collection-level** `variable[]` are resolved for now. Unresolved `{{var}}`
tokens are left literal (and an unresolved base becomes `null`, so the existing
base-override UI covers it). The resolver is a single pluggable component so Postman
environment files (and Bruno `environments/*.bru`) can be merged in later without
touching the importer.

### Path normalization

`RouteMatcher` compiles path templates in OpenAPI `{param}` syntax
(`wildcardCount = count('{')`). Postman `:id` and `{{id}}` path segments **must** be
rewritten to `{id}` at import time, or traffic matching against that route silently
fails.
