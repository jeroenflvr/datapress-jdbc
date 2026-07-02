---
name: datapress-jdbc
description: >
  Domain knowledge for building and maintaining the DataPress JDBC driver (repo: datapress-jdbc).
  Use this skill for ANY work in this repository: implementing java.sql interfaces, the DataPress
  HTTP/Arrow IPC protocol, type mapping, DatabaseMetaData synthesis, PreparedStatement parameter
  substitution, shading/packaging, or integration testing against a DataPress server. Consult it
  before writing any driver code, even for small fixes — it encodes the server's API contract,
  hard-won JDBC compatibility rules, and project conventions that are not obvious from the code.
---

# DataPress JDBC Driver — Development Skill

A Type-4 (pure Java, network-protocol) JDBC driver for DataPress (https://docs.datap-rs.org,
server repo: https://github.com/jeroenflvr/datapress). DataPress is a Rust columnar HTTP query
server over Parquet/Delta with DuckDB or DataFusion execution backends. The driver wraps its
REST + Arrow IPC API. The driver is **read-only and analytics-oriented** — that constraint
simplifies hundreds of JDBC methods.

## Ground rules

1. **Verify the API contract before trusting this document.** The server evolves. At the start of
   any protocol-touching task, check the live docs (https://docs.datap-rs.org, especially
   the endpoints reference, raw-SQL page, and Arrow/content-negotiation page) or the server
   source, and update `docs/CONTRACT.md` in this repo if reality differs from what's written
   here. `docs/CONTRACT.md` is the single source of truth once created.
2. **Never buffer a whole result set.** Arrow IPC batches are decoded lazily as `ResultSet.next()`
   advances. This is the driver's memory-safety mechanism; there is no server-side cursor.
3. **Unsupported ≠ broken.** For a read-only source, most of `java.sql` is legitimately
   `SQLFeatureNotSupportedException` (SQLState `0A000`) — but metadata methods that tools call
   unconditionally must return **empty result sets, never throw** (see DatabaseMetaData section).
4. **No information_schema tricks in the driver.** Do not intercept or rewrite
   `information_schema` SQL client-side; pass it through. Server-side support is on the server
   roadmap. Tool compatibility is achieved through `DatabaseMetaData`, not SQL rewriting.
5. **Zero unshaded third-party classes in the published jar.** See Packaging.

## Server API contract (expected shape — verify per rule 1)

> Verified against DataPress **v0.4.27**. The authoritative, detailed version lives in
> `docs/CONTRACT.md`; this section is the summary. If they disagree, `docs/CONTRACT.md` wins.

Base path `/api/v1`, bearer-token auth (`Authorization: Bearer <token>`), JSON by default,
Arrow IPC via content negotiation. When the server's optional OIDC/OAuth2 auth is enabled the
`<token>` is an **OIDC-issued JWT**, not a static secret; the driver treats it as opaque.
Probe endpoints (`/healthz`, `/readyz`, `/version`) are always unauthenticated.

| Endpoint | Method | Purpose | Driver use |
|---|---|---|---|
| `/healthz` | GET | liveness (`{"status":"ok"}`) | — |
| `/readyz` | GET | readiness (200/503) | `Connection.isValid()` |
| `/version` | GET | server version info (JSON: `name`,`version`,`backend`,`profile`,+opt) | connect handshake, `DatabaseMetaData.getDatabaseProductVersion()` |
| `/api/v1/datasets` | GET | list datasets | `getTables()` |
| `/api/v1/datasets/{name}/schema` | GET | dataset schema + one sample row | `getColumns()` |
| `/api/v1/sql` | POST | raw SQL: body `{"sql": "...", "max_rows": N}` | `Statement.executeQuery()` |
| `/api/v1/datasets/{name}/query` | POST | structured query JSON | not used by the driver (phase 1) |

Raw SQL semantics that shape the driver:
- **Disabled by default** on the server (`[sql].enabled = true` required). When disabled,
  **`POST /api/v1/sql` returns `404`** (identical to an unmounted route). A 404 *from the SQL
  endpoint* must surface as a clear `SQLException`: *"SQL endpoint disabled on server — set
  [sql].enabled=true in the DataPress config"*, not a raw HTTP error, and not as an
  unknown-dataset error. (An unknown table *inside* a statement is a `400`, not a `404`.)
- **Read-only**: only `SELECT`, `WITH … SELECT`, `DESCRIBE`/`DESC`. Single statement per request.
- **Single-dataset** (phase 1 server limitation): joins across datasets are rejected server-side
  with `400`. Dataset is named directly in `FROM`; matching is case-insensitive.
- **`max_rows`** in the body is clamped by the server into `[1, [sql].max_rows]`. Map
  `Statement.setMaxRows(n)` straight onto it; `0` (JDBC "no limit") means omit the field.
- **No parameter binding** server-side → PreparedStatement does client-side substitution.
- **No pagination/cursor** → one request, one streamed response.
- **Table-less queries (`SELECT 1`) may be rejected** by server validation. Therefore
  `Connection.isValid()` uses `GET /readyz`, never `SELECT 1`. Document this for users whose
  connection pools are configured with a validation query.

Result formats:
- Opt in with `Accept: application/vnd.apache.arrow.stream` **or** `?format=arrow` → Arrow IPC
  stream. **This is the only result path the driver implements.** The JSON envelope loses type
  fidelity; do not add a JSON fallback. The exact media type string
  `application/vnd.apache.arrow.stream` is **verified**. The response also carries an
  `X-Max-Rows` header echoing the applied cap. An empty result is a valid stream with the schema
  message only and zero batches (so column metadata is always available).
- **Backend type quirk:** DataFusion **dictionary-encodes string columns** — the SQL Arrow
  stream carries `Dictionary(Int32, Utf8)`, so a column's `Field.getType()` is the *index* type
  (`Int32`), not `Utf8`. Metadata mapping must resolve the dictionary *value* type via
  `TypeMapping.of(Field, DictionaryProvider)` (else `VARCHAR` columns look like `INTEGER`), and
  value decode must go through the `DictionaryProvider` (`ValueAccessors.forField(vector,
  provider)` → `DictionaryAccessor`, looked up lazily since the dictionary batch arrives with the
  first record batch; `ArrowResultIterator.provider()` exposes the reader). `TypeMapping` also
  still handles plain `Utf8View`/`BinaryView` (→ `VARCHAR`/`VARBINARY`) for backends/queries that
  emit them directly; the bundled `arrow-vector` must be new enough to decode them. Verified
  against `jeroenflvr/datapress:latest` (v0.5.0); DuckDB parity is unverified (that image can't
  load its parquet extension offline).

Error handling: every error is a flat JSON envelope `{"error": "<message>"}` (not RFC-7807; the
`/readyz` 503 is the one exception, `{"status":"not_ready",...}`). Parse defensively; include
HTTP status + `error` message in the `SQLException`. Auth failures: `401` (missing/invalid token,
with `WWW-Authenticate: Bearer realm="datapress"`) or `403` (valid token, missing scope).

### HTTP status → SQLException mapping

| Condition | SQLState | Exception |
|---|---|---|
| network/connect failure | `08001` | `SQLNonTransientConnectionException` |
| operation on closed connection/statement | `08003` | `SQLNonTransientConnectionException` |
| 401 / 403 | `28000` | `SQLInvalidAuthorizationSpecException` |
| 400 (parse/validation error) | `42000` | `SQLSyntaxErrorException` |
| unknown dataset (404 on a table) | `42S02` | `SQLSyntaxErrorException` |
| SQL endpoint disabled | `0A000` | `SQLFeatureNotSupportedException` (with the config hint) |
| 429 | `57014`-style transient | `SQLTransientException` |
| 5xx | `58000` | `SQLNonTransientException` |
| driver-unsupported JDBC feature | `0A000` | `SQLFeatureNotSupportedException` |

## JDBC URL and connection properties

```
jdbc:datapress://host[:port][/][?prop=value&...]
```

Properties (URL query params and `java.util.Properties` both accepted; Properties wins):
- `token` — bearer token. Also accept `password` as an alias (tools with user/password-only UIs);
  `user` is ignored but must not error.
- `tls` — `true|false`, default `false` (http). `jdbc:datapress:https://…` also acceptable if easy.
- `connectTimeout`, `socketTimeout` — milliseconds; sensible defaults (10 s / 300 s).
- `maxRows` — default per-statement cap applied when the app doesn't call `setMaxRows`.
- `applicationName` — sent as `User-Agent: datapress-jdbc/<version> (<applicationName>)`.

`Driver.acceptsURL` returns true only for the `jdbc:datapress:` prefix. Register via
`META-INF/services/java.sql.Driver` (ServiceLoader) **and** a static `DriverManager.registerDriver`
block for older code paths.

Connecting performs one `GET /version` (fail fast on unreachable/unauthorized) and caches the
server version string.

## Architecture and package layout

Package root `org.datapress.jdbc`. Public JDBC classes at the root, internals under `internal`.

```
org.datapress.jdbc
├── DataPressDriver
├── DataPressConnection
├── DataPressStatement
├── DataPressPreparedStatement
├── DataPressResultSet
├── DataPressResultSetMetaData
├── DataPressDatabaseMetaData
└── internal
    ├── http/    HttpApi (JDK java.net.http wrapper), auth, error mapping
    ├── arrow/   ArrowResultIterator (streaming IPC reader), TypeMapping, ValueAccessor per Arrow type
    ├── meta/    SyntheticResultSet (in-memory rows for metadata), MetadataQueries, LikePattern
    ├── sql/     ParameterSubstitutor (quote/comment-aware ? scanner), LiteralEncoder
    └── util/    UrlParser, VersionInfo, Preconditions
```

Key design points:
- **HTTP client is JDK built-in `java.net.http.HttpClient`** — one less dependency to shade.
- `ArrowResultIterator` opens `ArrowStreamReader` directly on the response `InputStream` and
  yields one `VectorSchemaRoot` batch at a time. `DataPressResultSet` holds (batch, rowIndexInBatch)
  and a `ValueAccessor[]` built once per schema. Closing the ResultSet closes the reader, the
  stream, and the allocator child; closing the Statement closes its open ResultSet; closing the
  Connection closes everything and the root `BufferAllocator`. Verify no allocator leaks in tests
  (`BufferAllocator.getAllocatedMemory() == 0` after close).
- One root `RootAllocator` per Connection, child allocator per Statement.
- Concurrency: JDBC requires Connection to be thread-safe for method calls; a Statement is used
  by one thread at a time. Guard connection state with a lock; don't over-engineer.
- Everything defaults: `TYPE_FORWARD_ONLY`, `CONCUR_READ_ONLY`, `autoCommit=true` (setter accepts
  only `true`), `TRANSACTION_NONE`, `isReadOnly()=true`, `commit()`/`rollback()` no-op silently
  (throwing breaks pools), `setReadOnly(false)` throws.

## Arrow → JDBC type mapping

Implement centrally in `TypeMapping` (Arrow `ArrowType` → `java.sql.Types` + Java class +
accessor). Table (extend as the server emits more types):

| Arrow | java.sql.Types | getObject class |
|---|---|---|
| Utf8 / LargeUtf8 | VARCHAR / LONGVARCHAR | String |
| Bool | BOOLEAN | Boolean |
| Int8 / Int16 / Int32 / Int64 | TINYINT / SMALLINT / INTEGER / BIGINT | Byte/Short/Integer/Long |
| UInt8/16/32 | next-larger signed type | Short/Integer/Long |
| UInt64 | DECIMAL(20,0) | BigDecimal |
| Float32 / Float64 | REAL / DOUBLE | Float / Double |
| Decimal128 / Decimal256 | DECIMAL (precision/scale from type) | BigDecimal |
| Date32 / Date64 | DATE | java.sql.Date (LocalDate internally) |
| Time32 / Time64 | TIME | java.sql.Time (LocalTime internally) |
| Timestamp (no tz) | TIMESTAMP | java.sql.Timestamp (LocalDateTime) |
| Timestamp (tz) | TIMESTAMP_WITH_TIMEZONE | java.time.OffsetDateTime |
| Binary / LargeBinary / FixedSizeBinary | VARBINARY / LONGVARBINARY / BINARY | byte[] |
| List / Struct / Map | VARCHAR (rendered as JSON text) | String |
| Null | NULL | null |

Rules:
- `wasNull()` tracks the last read; every typed getter goes through one code path that records it.
- Numeric getters perform widening/narrowing per JDBC spec; throw `SQLDataException` (`22003`)
  on overflow rather than silently truncating.
- `getString()` works for every type (format temporals as ISO-8601).
- Calendar-variant getters (`getTimestamp(i, Calendar)`) must be implemented — BI tools use them.
  Interpret tz-less values in the given calendar's zone.
- Nested types as JSON text is deliberate (BI compatibility); revisit only if a user needs
  `java.sql.Array`.

## DatabaseMetaData — the tool-compatibility surface

DBeaver/DataGrip/Metabase/Tableau drive their UI from this class. Rules:

- Single fixed catalog `datapress`, single schema `main` (must match whatever the server's
  future information_schema reports — coordinate via `docs/CONTRACT.md`).
- `getTables()` → `GET /api/v1/datasets`, each dataset one row, `TABLE_TYPE='TABLE'`. Apply
  `tableNamePattern` client-side with SQL `LIKE` semantics (`%`, `_`, escape) — implement once in
  `LikePattern`, test it well.
- `getColumns()` → `GET /api/v1/datasets/{name}/schema`, mapped through `TypeMapping` to fill
  `DATA_TYPE`, `TYPE_NAME`, `COLUMN_SIZE`, `DECIMAL_DIGITS`, `NULLABLE`, `ORDINAL_POSITION`.
  Column-name pattern also via `LikePattern`. May require N schema calls when the table pattern
  matches many datasets — fine for now; add a small TTL cache only if it proves slow.
- **Must return empty result sets (never throw):** `getPrimaryKeys`, `getImportedKeys`,
  `getExportedKeys`, `getIndexInfo`, `getProcedures`, `getFunctions`, `getUDTs`,
  `getTablePrivileges`, `getBestRowIdentifier`, `getVersionColumns`. Empty results must still
  carry the spec-correct column names/types — tools read the metadata of the empty ResultSet.
- `getTypeInfo()` → static table of exactly the types `TypeMapping` can emit.
- Capability flags: conservative truth. `supportsTransactions()=false`, `supportsBatchUpdates()=false`,
  `allProceduresAreCallable()=false`, `supportsSubqueriesInExists()=true` (DataFusion/DuckDB do),
  `getSQLKeywords()` etc. can start minimal. `getDatabaseProductName()="DataPress"`, versions from
  the cached `/version` handshake, driver version from a generated `VersionInfo` (single source:
  Gradle project version).
- All synthetic rows go through `SyntheticResultSet` — an in-memory ResultSet over
  `List<Object[]>` + column definitions. It is also reused for `DESCRIBE` fallbacks and tests.

## PreparedStatement — client-side substitution

No server-side binding exists, so `?` placeholders are substituted into the SQL text at execute
time. This must be safe:

- `ParameterSubstitutor` scans the SQL once and records `?` positions **outside** of:
  single-quoted strings (with `''` escapes), double-quoted identifiers, `--` line comments,
  `/* */` block comments (handle nesting the way the target engines do — check both DuckDB and
  DataFusion; when they differ, be conservative). Setting a parameter index that doesn't exist →
  `SQLException` `07009`.
- `LiteralEncoder` renders values: strings → `'…'` with quote doubling and rejection/encoding of
  NUL; numbers → plain literals (BigDecimal via `toPlainString()`); booleans → `TRUE`/`FALSE`;
  null → `NULL`; LocalDate → `DATE '2026-07-02'`; timestamps → `TIMESTAMP '…'` ISO format;
  byte[] → hex literal in the syntax both engines accept (verify: DuckDB and DataFusion differ
  in blob-literal syntax — if no common syntax exists, throw `0A000` for binary params and note
  it in README limitations).
- All parameters must be set before execute; missing → `07001`. `clearParameters()` supported.
- `addBatch`/`executeBatch` on PreparedStatement: not supported (read-only driver) → `0A000`.
- `getMetaData()` before execution may return null (allowed by spec); after execution returns the
  result metadata. `getParameterMetaData()` returns count with `parameterModeIn`/unknown types.

## Testing

Two layers, cleanly separated:

1. **Unit tests (no network)** — URL parsing, `LikePattern`, `ParameterSubstitutor` (heavy
   edge-case coverage: quotes, comments, `??`, unicode), `LiteralEncoder`, `TypeMapping`,
   `SyntheticResultSet`, error mapping. Arrow decoding is unit-tested by generating IPC streams
   in-memory with arrow-vector itself and feeding them to `ArrowResultIterator`.
2. **Integration tests** — JUnit 5, gated with
   `@EnabledIfEnvironmentVariable(named = "DATAPRESS_URL", matches = ".+")` so `./gradlew test`
   never fails on a laptop without a server. `docker-compose.yml` (or `scripts/run-server.sh`
   that downloads a release binary) starts DataPress with `[sql].enabled = true`, a test token,
   and fixture datasets mounted from `src/integrationTest/resources/fixtures/` (small Parquet
   files, committed; a `scripts/make_fixtures.py` regenerates them). Cover: connect/handshake,
   auth failure, executeQuery over every fixture type, setMaxRows clamping, DatabaseMetaData
   round-trip, PreparedStatement substitution end-to-end, streaming a >1-batch result, allocator
   leak check, statement/connection close semantics, SQL-disabled server error message.
   Run the matrix against **both server backends** (DuckDB and DataFusion) if the image allows —
   type emission differs.
3. **Manual smoke**: `docs/DBEAVER.md` documents adding the shaded jar to DBeaver, expected tree
   view, and known quirks. Update it whenever metadata behavior changes.

## Packaging and toolchain

- **Gradle (Kotlin DSL)**, `com.gradleup.shadow` for the fat jar.
- **Bytecode target Java 11**; toolchain/test matrix 11, 17, 21 in CI (GitHub Actions).
- Dependencies: `org.apache.arrow:arrow-vector`, `org.apache.arrow:arrow-memory-unsafe`
  (chosen over `arrow-memory-netty` to avoid relocating Netty and its native libs — revisit if
  Arrow deprecates it), Jackson (`jackson-databind`) only for metadata JSON, JUnit 5 + AssertJ
  for tests. Nothing else without good reason.
- **Shading**: relocate `org.apache.arrow`, `com.fasterxml.jackson`, `com.google.flatbuffers`
  and any other transitive deps under `org.datapress.jdbc.internal.shaded.*`. Merge/exclude
  `META-INF` correctly but **preserve `META-INF/services/java.sql.Driver`**. The published
  artifact is the shaded jar; a thin jar can be a classifier.
- **JPMS/JDK 16+ gotcha**: Arrow memory needs `--add-opens=java.base/java.nio=ALL-UNNAMED`.
  Add to test `jvmArgs`, document prominently in README (DBeaver/Tableau users hit this), and
  detect the failure at runtime to throw a friendly `SQLException` explaining the flag.
- `Automatic-Module-Name: org.datapress.jdbc` in the manifest.
- Version handshake: driver sends `User-Agent: datapress-jdbc/<version>`; keep driver version in
  one place (Gradle) and generate `VersionInfo` at build time.
- Maven Central publishing (`org.datap-rs:datapress-jdbc` or as the owner decides): separate,
  later task — keep `maven-publish` config ready but unsigned until then.

## Conventions

- Spotless + google-java-format (or palantir) enforced in CI; no checked-in IDE configs beyond
  `.editorconfig`.
- Every public JDBC method either works, returns a spec-compliant default, or throws
  `SQLFeatureNotSupportedException` — never `UnsupportedOperationException`, never silent wrong
  answers. When the JDBC javadoc allows a degenerate answer (e.g. empty result set), prefer it
  over throwing.
- Keep a `docs/COMPATIBILITY.md` matrix: JDBC method → supported / default / throws, updated as
  part of any PR touching public classes.
- Conventional commits; CHANGELOG.md kept by hand.
