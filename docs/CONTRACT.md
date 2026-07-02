# DataPress server API contract

**Verified against:** DataPress **v0.4.27** (docs at https://docs.datap-rs.org,
server repo https://github.com/jeroenflvr/datapress).
**Last verified:** 2026-07-02.

This document is the **single source of truth** for the driver's view of the server.
When the live server or docs disagree with this file, the live server wins — update
this file and `SKILL.md` together in the same commit.

Only the endpoints and behaviours the driver actually depends on are specified
normatively; other endpoints are listed for completeness.

---

## Base URLs and versioning

- Versioned API mounted under `/api/v1`. Unversioned legacy aliases exist under `/api`
  (same handlers) but are slated for deprecation — **the driver always uses `/api/v1`**.
- A configurable URL `prefix` shifts everything under `/api/...`; the three probe
  endpoints (`/healthz`, `/readyz`, `/version`) always sit at the bare host root,
  independent of the prefix.

## Authentication

- Header: `Authorization: Bearer <token>`.
- Auth is **opt-in on the server** (OIDC/OAuth2, compiled with the `auth` feature and
  `[auth] enabled = true`). When enabled, `<token>` is an **OIDC-issued JWT**, not a
  static server-side secret. The driver treats the token as an opaque string and does
  not mint, parse, or refresh it.
- When auth is disabled, requests need no token.
- Probe endpoints (`/healthz`, `/readyz`, `/version`) are **always unauthenticated**.
- Admin/reload endpoints use a separate `X-Admin-Token` header — **not used by the driver**.

### Auth failure responses

| Condition | Status | Notes |
|---|---|---|
| missing or invalid token | `401` | carries `WWW-Authenticate: Bearer realm="datapress"` |
| valid token, missing scope / wrong tenant | `403` | body `{"error":"forbidden"}` |

## Probe endpoints (driver: handshake + validation)

| Method | Path | Status | Body |
|---|---|---|---|
| GET | `/healthz` | 200 | `{"status":"ok"}` |
| GET | `/readyz` | 200 / 503 | `{"status":"ready","datasets":N}` / `{"status":"not_ready","datasets":0}` |
| GET | `/version` | 200 | build metadata (below) |

`/readyz` returns 200 once at least one dataset is loaded, 503 while loading or if all
datasets failed. **`Connection.isValid()` uses `GET /readyz`.**

### `/version` response

```json
{
  "name":       "datapress-core",
  "version":    "0.4.27",
  "backend":    "DuckDB | DataFusion | unknown",
  "git_sha":    "a1b2c3d4",
  "build_time": "2025-01-15T14:32:09Z",
  "profile":    "release",
  "target":     "x86_64-unknown-linux-gnu"
}
```

- Required fields: `name`, `version`, `backend`, `profile`.
- Optional fields (`git_sha`, `build_time`, `target`) are **omitted** when unset —
  parse defensively.
- The connect handshake does one `GET /version`, failing fast on unreachable/unauthorized,
  and caches `version` + `backend` for `DatabaseMetaData`.

## Dataset discovery (driver: DatabaseMetaData)

| Method | Path | Purpose | Driver use |
|---|---|---|---|
| GET | `/api/v1/datasets` | list configured datasets + metadata (JSON) | `getTables()` |
| GET | `/api/v1/datasets/{name}/schema` | inferred schema + one sample row (JSON) | `getColumns()` |

- Unknown dataset name in a URL path → `404` with `{"error":"dataset '<name>' not found"}`.
- These endpoints are **JSON-only** (no Arrow).

## Raw SQL (driver: Statement / PreparedStatement)

`POST /api/v1/sql` (legacy alias `POST /api/sql`).

### Enablement

- **Disabled by default.** Requires `[sql] enabled = true` (config) or `sql_enabled=True`
  (Python `DataPressConfig`).
- **While disabled the route returns `404 Not Found`** — deliberately identical to an
  unmounted route so probing leaks nothing.
- `[sql] max_rows` (default `100000`) is a server-side hard cap; every query is wrapped
  in an outer `LIMIT`.

> **Disambiguation:** a `404` **from `POST /api/v1/sql`** means the SQL endpoint is
> disabled. An unknown table *inside* a SQL statement is rejected with `400`, not `404`
> (see rejections). The driver maps a 404 on the SQL endpoint to
> `SQLFeatureNotSupportedException` (`0A000`) with the config hint; it does **not** treat
> it as an unknown-dataset error.

### Request body

```json
{ "sql": "SELECT ...", "max_rows": 500 }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `sql` | string | yes | a single read-only `SELECT` / `WITH … SELECT`, or `DESCRIBE` / `DESC <table>`, referencing exactly one dataset |
| `max_rows` | integer | no | clamped into `[1, [sql].max_rows]`; can never raise the server cap; **omit** to use the server cap |

- The dataset is named directly in the SQL `FROM` clause using its configured `name`
  (the slug from `/api/v1/datasets/{name}/...`). **Matching is case-insensitive.**
- `Statement.setMaxRows(n)`: map `n > 0` onto `max_rows`; `n == 0` (JDBC "no limit")
  → omit the field.

### Requesting Arrow (the only result path the driver implements)

Opt in with **either**:
- header `Accept: application/vnd.apache.arrow.stream`, **or**
- query param `?format=arrow`.

Response:
- `Content-Type: application/vnd.apache.arrow.stream`
- `X-Max-Rows: <applied cap>` header echoing the effective cap.
- Body is an **Arrow IPC stream**: one schema message, zero or more `RecordBatch`
  messages, then an end marker. It is written incrementally (streamed) as it is encoded.
- An **empty result** is a valid stream carrying **the schema message only, zero batches**
  — so column metadata is always available even with no rows.

The exact media-type string is **`application/vnd.apache.arrow.stream`** (verified).

> The JSON envelope (`{"data":[...],"max_rows":N}`) loses type fidelity and is **not**
> used by the driver — no JSON fallback for result sets.

### Statements that are rejected (`400 Bad Request`)

The same validation gate runs for DuckDB and DataFusion. Rejected when the statement:
- is not a single read-only statement — anything other than `SELECT` / `WITH … SELECT` /
  `DESCRIBE` / `DESC` (no `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `COPY`,
  `ATTACH`, `INSTALL`, `PRAGMA`, `EXPLAIN`, …); or contains multiple statements;
- references an unknown table (every relation must be a registered dataset or a CTE
  defined in the same query);
- references **more than one dataset** (Phase-1 server limit — no cross-dataset joins);
- uses a file-reading / external-access function anywhere (`read_parquet`, `read_csv`,
  `read_json`, `read_text`, `read_blob`, `glob`, `parquet_scan`, …), even in scalar
  position.

Example rejection bodies:
```json
{ "error": "only read-only SELECT and DESCRIBE statements are allowed" }
{ "error": "exactly one SQL statement is allowed" }
{ "error": "could not parse SQL: ..." }
{ "error": "this endpoint allows at most 1 dataset(s) per query; the statement references 2" }
```

> **Table-less queries (`SELECT 1`) may be rejected** by validation (every relation must
> be a registered dataset). Therefore `Connection.isValid()` must **not** use `SELECT 1`;
> it uses `GET /readyz`. Document this for connection pools configured with a validation
> query.

## Endpoints not used by the driver (Phase 1)

For reference only:
- `POST /api/v1/datasets/{name}/query` — structured query JSON.
- `POST /api/v1/datasets/{name}/query/stream` — full-result Arrow stream for one dataset.
- `POST /api/v1/datasets/{name}/count` — row count.
- `GET /api/v1/datasets/{name}/parquet` and `/all.parquet` — Parquet export.
- `POST /api/v1/datasets/{name}/reload`, `POST /api/v1/datasets`, `.../persist`,
  `POST /api/v1/config/reload` — admin (`X-Admin-Token`).
- Optional `{metrics.path}` (Prometheus), `{docs.path}` (embedded MkDocs / Swagger UI).

## Error model

Every error is a flat JSON envelope:

```json
{ "error": "<short human-readable message>" }
```

The **status code** carries the structure; the message is for humans. (Exception: the
`/readyz` 503 body is `{"status":"not_ready","datasets":0}`.) It is **not** RFC-7807;
parse defensively and surface HTTP status + `error` message in the `SQLException`.

### Documented status codes

| Status | Meaning | Typical cause |
|---|---|---|
| 400 | bad request / validation | malformed body, invalid SQL, unknown column/table in SQL, too many datasets |
| 401 | unauthorized | missing/invalid bearer token (auth enabled) |
| 403 | forbidden | valid token missing scope; or admin endpoint without `X-Admin-Token` |
| 404 | not found | unknown dataset in a URL path; **or SQL endpoint disabled** |
| 413 | payload too large | request body exceeded `max_body_bytes` |
| 500 | internal server error | engine/storage error during execution |
| 503 | service unavailable | `/readyz` while loading; reload in progress |
| 504 | gateway timeout | handler exceeded `request_timeout_ms` |

> `429 Too Many Requests` is **not documented** for v0.4.27. The driver keeps a
> defensive mapping for it (transient) but must not rely on it being emitted.

## HTTP status → SQLException mapping (driver)

| Condition | SQLState | Exception |
|---|---|---|
| network / connect failure | `08001` | `SQLNonTransientConnectionException` |
| operation on closed connection/statement | `08003` | `SQLNonTransientConnectionException` |
| `401` / `403` | `28000` | `SQLInvalidAuthorizationSpecException` |
| `400` (parse/validation) | `42000` | `SQLSyntaxErrorException` |
| unknown dataset (`404` on a dataset URL path) | `42S02` | `SQLSyntaxErrorException` |
| SQL endpoint disabled (`404` on `POST /api/v1/sql`) | `0A000` | `SQLFeatureNotSupportedException` (with config hint) |
| `429` (if ever emitted) | `57014`-style transient | `SQLTransientException` |
| `5xx` | `58000` | `SQLNonTransientException` |
| driver-unsupported JDBC feature | `0A000` | `SQLFeatureNotSupportedException` |

## Arrow type-emission notes (feeds Phase 2 TypeMapping)

- **DataFusion emits Arrow `Utf8View` (and can emit `BinaryView`) for Parquet string/
  binary columns.** The driver's `TypeMapping` must handle `Utf8View`/`LargeUtf8`/`Utf8`
  as `VARCHAR`, and `BinaryView`/`LargeBinary`/`Binary` as `VARBINARY`. This is a known
  divergence between the two backends (DuckDB does not use the `*View` variants). The
  bundled `arrow-vector` version must be new enough to decode `Utf8View`/`BinaryView`.
- Otherwise follow the type-mapping table in `SKILL.md`.

## Open questions / to confirm against a live server

1. Exact JSON shape of `GET /api/v1/datasets` (field names for dataset name/row count)
   and `GET /api/v1/datasets/{name}/schema` (column name/type/nullable fields). Confirm
   in Phase 3/4 against the `jeroenflvr/datapress` Docker image and record here.
2. Fixed catalog/schema names for `DatabaseMetaData` — `SKILL.md` proposes `datapress` /
   `main`; confirm against whatever a future `information_schema` reports.
3. Whether the `docker.io/jeroenflvr/datapress` image ships with the `auth` feature and
   how to configure a static test token vs. a full OIDC flow for integration tests.
