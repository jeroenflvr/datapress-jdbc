# Progress log

Running notes per phase: what was done, decisions made, open questions.

## Phase 0 — Contract verification and repo scaffold

**Done**

- Verified the live DataPress API contract against **server v0.4.27** (docs at
  https://docs.datap-rs.org) and wrote [`docs/CONTRACT.md`](CONTRACT.md) as the single
  source of truth: endpoints, auth, probes, raw-SQL semantics, Arrow media type, error model,
  and the HTTP→SQLException mapping.
- Corrected `SKILL.md` where it diverged from reality (see decisions below) and moved it to the
  documented location `.claude/skills/datapress-jdbc/SKILL.md`.
- Scaffolded the repo: Gradle Kotlin DSL build (`java-library`, shadow, spotless, maven-publish),
  `.editorconfig`, `.gitignore`, Apache-2.0 `LICENSE`, pre-alpha `README.md`, `CLAUDE.md`,
  and the `org.datapress.jdbc` / `internal.*` package skeleton with a build-generated
  `VersionInfo` (driver version sourced from the Gradle project version).
- Added `.github/workflows/ci.yml`: spotless check + build/test matrix on JDK 11/17/21.

**Decisions**

- **Contract corrections vs. the original skill:**
  - Error bodies are a flat `{"error":"..."}` envelope, **not** RFC-7807. The `/readyz` 503 is
    the one exception (`{"status":"not_ready","datasets":0}`).
  - The SQL endpoint, when disabled, returns a plain `404` from `POST /api/v1/sql` (identical to
    an unmounted route). An unknown table *inside* a statement is a `400`, not `404`. The driver
    disambiguates: 404-on-`/sql` ⇒ "SQL disabled" (`0A000`); 404-on-a-dataset-URL ⇒ unknown
    dataset (`42S02`).
  - Arrow can be requested via `?format=arrow` as well as the `Accept` header; the response
    carries an `X-Max-Rows` header. Media type string confirmed:
    `application/vnd.apache.arrow.stream`.
  - Auth token is an **OIDC-issued JWT** when the server's auth feature is enabled (401 for
    missing/invalid with `WWW-Authenticate`, 403 for missing scope), not a static secret. The
    driver still treats the token as opaque.
  - `/version` fields documented: `name`, `version`, `backend`, `profile` (required),
    `git_sha`, `build_time`, `target` (optional, omitted when unset).
- **Backend type quirk recorded for Phase 2:** DataFusion emits Arrow `Utf8View`/`BinaryView`
  for Parquet string/binary columns; `TypeMapping` must handle them. Bundled `arrow-vector`
  must be new enough to decode them.
- **Toolchain:** bytecode target Java 11 via `--release 11` (compiled by whatever JDK runs
  Gradle); no Java-11 toolchain provisioning required locally. CI exercises 11/17/21.
- **Dependencies pinned:** arrow-vector + arrow-memory-unsafe `17.0.0`, jackson-databind
  `2.17.2`, JUnit `5.10.3`, AssertJ `3.26.3`. Gradle wrapper pinned to `8.10.2` for shadow
  `8.3.5` / spotless `6.25.0` compatibility.
- **Published artifact** is the shaded jar (classifier removed); the thin jar takes the `thin`
  classifier. `maven-publish` is configured but publishing is left manual/unsigned.

**Open questions** (to resolve in Phase 3/4 against the `jeroenflvr/datapress` Docker image)

- Exact JSON field names of `GET /api/v1/datasets` and `GET /api/v1/datasets/{name}/schema`.
- Confirm fixed catalog/schema names (`datapress` / `main`) for `DatabaseMetaData`.
- Whether the Docker image ships the `auth` feature and how to set a static test token vs. a
  full OIDC flow for integration tests.

## Phase 1 — Driver, URL parsing, Connection, HTTP handshake

**Done**

- `DataPressDriver` (`java.sql.Driver`): static `DriverManager` registration + a
  `META-INF/services/java.sql.Driver` provider file for `ServiceLoader`. `connect()` returns
  `null` for foreign URLs, otherwise parses the config, opens an `HttpApi`, performs the
  `GET /version` handshake, and returns a `DataPressConnection` (closing the transport on
  failure). `jdbcCompliant()` is `false`; `getParentLogger()` throws.
- `UrlParser` / `ConnectionConfig`: parse `jdbc:datapress://…`, `jdbc:datapress:http://…`,
  and `jdbc:datapress:https://…` (https ⇒ TLS). Resolves host/port (default `8000`), TLS,
  token (`token`/`password` aliases, URL-decoded), `connectTimeout`, `socketTimeout`,
  `maxRows`, and `applicationName`. Precedence: `Properties` win over URL query. `user` is
  accepted-but-ignored. Malformed URL/port/boolean/int ⇒ `SQLNonTransientConnectionException`
  (`08001`). `ConnectionConfig.toString()` redacts the token.
- `HttpApi`: thin wrapper over the JDK `HttpClient` — bearer auth, `User-Agent`
  (`datapress-jdbc/<version>` + optional `(<appName>)`), per-call timeouts. `getVersion()` and
  `isReady()` (readiness probe backing `isValid`). Transport failures funnel through `SqlErrors`.
- `SqlErrors`: centralised HTTP-status → `SQLException` mapping with a `Context`
  (`GENERIC`/`SQL_ENDPOINT`/`DATASET`) to disambiguate `404`.
- `DataPressConnection` (`java.sql.Connection`): read-only, non-transactional. `autoCommit=true`
  only, `TRANSACTION_NONE`, `isReadOnly()=true`, `commit()`/`rollback()` are silent no-ops so
  pools don't break, `setReadOnly(false)`/`setAutoCommit(false)` are rejected. Fixed catalog
  `datapress` / schema `main`. `createStatement`/`prepareStatement`/`getMetaData` throw
  `SQLFeatureNotSupportedException` **temporarily** — to be implemented in Phases 2/4/5.
- Tests: URL/property parsing matrix, `acceptsURL` negatives, the full error-mapping table,
  driver registration + `getPropertyInfo`, and a `com.sun.net.httpserver`-based `StubServer`
  proving `DriverManager.getConnection` connects, sends `Bearer`/`User-Agent`, reflects
  `/readyz` in `isValid`, and enforces closed/read-only semantics.

**Decisions**

- `isValid(negative)` throws (per the JDBC contract); on a closed connection it returns `false`.
  `isValid` is backed by `GET /readyz` (200 ⇒ valid), and any transport error ⇒ `false`.
- `SQLException` implements `Iterable<Throwable>`, so AssertJ's `assertThat(SQLException)` is
  ambiguous — tests cast to `(Throwable)` for `isInstanceOf`/cause assertions.
- `HttpApi.close()` is a no-op: the JDK `HttpClient` has no `close()` on Java 11–20; the Arrow
  allocator lifecycle will be owned by `Connection`/`Statement` in Phase 2.

**Open questions**

- None new; Phase 3/4 open questions from Phase 0 still stand (dataset/schema JSON shapes,
  auth feature in the Docker image).

## Phase 2 — Statement + streaming Arrow ResultSet

**What**

- `HttpApi.executeSql(sql, maxRows)`: `POST /api/v1/sql` with body `{"sql":…,"max_rows":N}`
  (`max_rows` omitted when 0) and `Accept: application/vnd.apache.arrow.stream`. Non-200 responses
  are drained and mapped via `SqlErrors.fromHttpStatus(…, Context.SQL_ENDPOINT)` so a disabled SQL
  endpoint (404) surfaces as `0A000`; 200 returns the raw `InputStream` for streaming.
- `internal.arrow` layer: `TypeMapping`/`ColumnMeta` (Arrow `Field` → JDBC type facts),
  `ValueAccessor`/`ValueAccessors` (per-vector decode to canonical Java objects, incl. Utf8View /
  BinaryView, unsigned ints, tz/tz-less timestamps), `Convert` (spec widening/narrowing; range
  errors → `22003`, unparseable → `22018`), and `ArrowResultIterator` (streaming `ArrowStreamReader`
  wrapper — one batch resident, vectors reused across `loadNextBatch()`).
- `DataPressResultSet` (`java.sql.ResultSet`): forward-only, read-only. All typed getters + `getObject`,
  `wasNull`, `Calendar` variants, case-insensitive `findColumn`, `DataPressResultSetMetaData`. Every
  navigation beyond `next()`/`close()` and all update methods raise `0A000`.
- `DataPressStatement` (`java.sql.Statement`): `executeQuery`/`execute` stream a result set;
  `setMaxRows` feeds `max_rows`; every update-shaped call raises `0A000`.
- Allocator lifecycle: `RootAllocator` per `Connection` → child per `Statement` → grandchild per
  `ResultSet`. Close cascades (Connection → Statement → ResultSet) release every buffer;
  leak-checked via `getAllocatedMemory() == 0`.

**Decisions**

- `internal.arrow.Convert` is `public` because the getters live in the public `org.datapress.jdbc`
  package; the rest of the arrow layer is package-scoped where possible.
- `Statement.execute(sql, …)` overloads delegate to `execute(sql)` (read-only, no generated keys);
  `getGeneratedKeys`/batch/named cursors raise `0A000`. `cancel()` is a no-op (streaming HTTP).
- Constant-memory streaming verified by asserting the connection root allocator's resident bytes are
  identical across three equally-sized batches and `0` after `ResultSet.close()` — no whole-stream
  buffering.

**Open questions**

- None new. Timestamp-with-timezone currently decodes to `OffsetDateTime`; revisit `getTimestamp`
  calendar semantics if server tz metadata differs from the verified contract.

## Phase 3 — Integration harness against a real server

**What**

- **Fixtures** (`scripts/make_fixtures.py`, run via `uv run --with pyarrow`): three committed
  Parquet files under `src/integrationTest/resources/fixtures/` — `people` (id/name/active/score/
  created, incl. nulls), `types` (one populated + one all-null row covering every mapped Arrow
  type), `numbers` (20 000 rows for streaming / `max_rows`).
- **Server bring-up:** `scripts/run-server.sh {up [datafusion|duckdb] | up-nosql | down}` runs the
  `jeroenflvr/datapress:latest` container, mounts `src/integrationTest/resources/datasets.toml`
  (backend rewritten per leg) + the fixtures, waits on `/readyz`, and prints the `DATAPRESS_URL`
  to export. `up-nosql` starts a second instance with the `[sql]` block stripped (endpoint 404s).
  `docker-compose.yml` is a DataFusion-pinned convenience equivalent.
- **`integrationTest` suite** (`src/integrationTest/java`, gated on `DATAPRESS_URL`):
  `ConnectionHandshakeIT` (connect/`isValid`/read-only/cached `/version`),
  `QueryIT` (people incl. **dictionary-decoded strings**, all fixture types + null row, result-set
  metadata, `DESCRIBE`), `StreamingAndMaxRowsIT` (client `setMaxRows`, **server-side clamp**,
  multi-batch stream + allocator leak check), `ErrorPathsIT` (unknown-table `42xxx`; SQL-disabled
  `0A000`, gated on `DATAPRESS_NOSQL_URL`).
- **Verified:** `DATAPRESS_URL=jdbc:datapress://127.0.0.1:18080/ ./gradlew integrationTest` → 12
  tests green against a live DataFusion container (SQL-disabled leg included when the second server
  is up).

**Contract corrections discovered (folded into `docs/CONTRACT.md`)**

- Server is **v0.5.0** (was documented v0.4.27).
- **DataFusion dictionary-encodes string columns** — the SQL Arrow stream carries
  `Dictionary(Int32, Utf8)`, not `Utf8`/`Utf8View`. This is the big one: naive metadata mapping
  reported string columns as `INTEGER` (the index type). Fixed by
  `TypeMapping.of(Field, DictionaryProvider)` (resolves the value type for metadata) +
  `ValueAccessors.forField(vector, provider)` / `DictionaryAccessor` (lazy provider lookup for
  values) + `ArrowResultIterator.provider()`. Confirmed unit + integration green.
- Confirmed JSON shapes for `GET /api/v1/datasets` and `/schema`; unknown-dataset body is
  `{"error":"not found: dataset: <name>"}`.

**Decisions / environment notes**

- **Port shadowing gotcha:** a *native* `datapress` process on the host was bound to IPv4
  `*:8080` while OrbStack published the container on IPv6 `:8080`; `curl 127.0.0.1:8080` hit the
  native server (a different dataset) and every dataset query 400'd with "not a registered
  dataset". Ran the integration container on **port 18080** (host process left untouched) to avoid
  the conflict. `run-server.sh` honours `DATAPRESS_PORT` / `DATAPRESS_NOSQL_PORT`.
- **No static REST auth** in this image (auth is optional OIDC, off by default) → the auth-failure
  integration test has no fixed-token path here; gated on an optional env var and skipped by
  default.
- **DuckDB backend not runnable offline** in this image: it auto-installs the DuckDB `parquet`
  extension from the network at load time and fails (404) before readiness. Integration coverage
  is DataFusion-only with `jeroenflvr/datapress:latest`; the DuckDB leg is deferred to an image
  that bundles the extension.
- Server `max_rows` set to **10 000** in the test config so the clamp test (query 20 000-row
  `numbers` with no client limit → exactly 10 000) also exercises **multi-batch streaming**
  (10 000 > the ~8 192 DataFusion batch size).

**Open questions**

- DuckDB backend parity (string encoding + type emission) remains unverified until an image ships
  the DuckDB parquet extension.
- `DatabaseMetaData` catalog/schema (`datapress`/`main`) still to be confirmed against a real
  `information_schema` in Phase 4.

