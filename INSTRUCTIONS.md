# Build brief: DataPress JDBC driver (`datapress-jdbc`)

You are Claude Opus 4.8 working in Claude Code, in the empty repository `jeroenflvr/datapress-jdbc`.
Your task is to build a production-quality, read-only Type-4 JDBC driver for DataPress
(https://docs.datap-rs.org, server: https://github.com/jeroenflvr/datapress).

**Before writing any code, read the project skill at `.claude/skills/datapress-jdbc/SKILL.md`.**
It is the authoritative source for the server API contract, architecture, type mapping,
DatabaseMetaData rules, PreparedStatement substitution rules, error mapping, testing strategy,
and packaging decisions. This brief tells you *what to do in what order*; the skill tells you
*how*. If this brief and the skill ever conflict, the skill wins; if the skill and the live
server/docs conflict, the live server wins and you update both `docs/CONTRACT.md` and the skill.

Work in phases. Each phase ends with: all tests green, `./gradlew build` clean, a conventional
commit, and a short note appended to `docs/PROGRESS.md` (what was done, decisions made, open
questions). Do not start a phase before the previous one's acceptance criteria pass. Ask the
user only when a decision is genuinely blocking and not covered by the skill; otherwise decide,
document the decision in `docs/PROGRESS.md`, and proceed.

---

## Phase 0 — Contract verification and repo scaffold

1. Fetch and read the live DataPress docs: the endpoints reference, the raw-SQL page, the
   Arrow/content-negotiation page, and the auth/config pages. If anything is ambiguous, read the
   relevant server source on GitHub (actix-web route definitions and the SQL handler).
2. Write `docs/CONTRACT.md`: every endpoint the driver uses, exact request/response shapes,
   the exact Arrow media-type string, auth header format, error body shape, and the raw-SQL
   restrictions (read-only, single statement, single dataset, max_rows clamping, disabled by
   default). Note the server version you verified against. **Correct the skill file if it
   disagrees with what you found.**
3. Scaffold the repo: Gradle Kotlin DSL wrapper, `build.gradle.kts` per the skill's toolchain
   section (Java 11 target, shadow plugin, spotless, dependencies), `.editorconfig`,
   `.gitignore`, Apache-2.0 `LICENSE`, minimal `README.md` (what it is, status: pre-alpha),
   `CLAUDE.md` pointing future sessions at the skill and `docs/CONTRACT.md`, and empty package
   skeleton `org.datap_rs.jdbc` / `org.datap_rs.jdbc.internal.*`.
4. GitHub Actions: `ci.yml` running spotless + build + unit tests on JDK 11/17/21.

**Accept when:** `./gradlew build` succeeds on a clean clone; CONTRACT.md exists and cites the
verified server version; CI file is valid.

## Phase 1 — Driver, URL, Connection, handshake

Implement `DataPressDriver` (ServiceLoader registration + static register block, `acceptsURL`,
property metadata via `getPropertyInfo`), `internal.util.UrlParser`, `internal.http.HttpApi`
(JDK HttpClient, bearer auth, timeouts, User-Agent, centralized HTTP→SQLException mapping per
the skill's table), and `DataPressConnection` (handshake via `GET /version`, `isValid()` via
`/readyz`, read-only/transaction semantics per skill, close semantics, `unwrap`/`isWrapperFor`).

Unit tests: URL/property parsing matrix (token via URL, via Properties, password alias,
precedence), acceptsURL negatives, error mapping table, closed-connection behavior. HTTP tests
against a stub server (JDK `com.sun.net.httpserver` is fine for tests).

**Accept when:** `DriverManager.getConnection("jdbc:datapress://localhost:8000/?token=x")`
connects against a stub returning `/version`, and all unit tests pass.

## Phase 2 — Statement + streaming Arrow ResultSet (the core)

Implement `DataPressStatement.executeQuery` → `POST /api/v1/sql` with Arrow Accept header;
`internal.arrow.ArrowResultIterator` streaming batches off the response InputStream;
`TypeMapping` + per-type `ValueAccessor`s; `DataPressResultSet` (forward-only, all typed getters,
`getObject`, `wasNull`, calendar variants, `findColumn` case-insensitive) and
`DataPressResultSetMetaData`. `setMaxRows`/`maxRows` property → `max_rows` body field.
`execute()`/`getResultSet()`/`getUpdateCount()` protocol correct for query-only statements;
`executeUpdate` → `0A000`. Allocator lifecycle per skill (root per connection, child per
statement, leak-checked).

Unit tests: generate Arrow IPC streams in-memory covering every type in the skill's mapping
table (including nulls, multiple batches, zero-row results, decimal precision, tz and tz-less
timestamps) and assert every getter, wasNull, metadata, and overflow behavior (`22003`).
Allocator leak assertions after close, and close-cascade tests (connection→statement→resultset).

**Accept when:** the full type-mapping test matrix passes; a 3-batch stream is consumed with
constant memory (no whole-stream buffering — assert via allocator high-water mark); update-type
calls throw `0A000`.

## Phase 3 — Integration harness against a real server

Set up the integration layer per the skill's testing section: `docker-compose.yml` or
`scripts/run-server.sh` (whichever the server project supports — check for a published Docker
image first; if none exists, download the release binary), server config with
`[sql].enabled = true` and a fixed test token, fixture Parquet datasets (commit small files;
include `scripts/make_fixtures.py`), `integrationTest` source set gated on `DATAPRESS_URL`.
Wire an integration job into CI if and only if a server artifact is reliably fetchable in CI;
otherwise document how to run locally.

Port the Phase-2 assertions to run end-to-end, plus: auth-failure path, SQL-disabled error
message, server-side max_rows clamping, DESCRIBE, and (if the server image allows) both DuckDB
and DataFusion backends.

**Accept when:** `DATAPRESS_URL=… ./gradlew integrationTest` passes locally against a scripted
server start; `docs/PROGRESS.md` records any contract corrections discovered.

## Phase 4 — DatabaseMetaData

Implement `DataPressDatabaseMetaData` fully per the skill: fixed catalog/schema, `getTables`/
`getColumns` from the REST endpoints with `LikePattern`, spec-correct **empty** result sets for
keys/indexes/procedures/etc., `getTypeInfo`, conservative capability flags, product/driver
versions. Implement `SyntheticResultSet` first and reuse it everywhere.

Tests: LikePattern edge cases (%, _, escapes, regex metacharacters in names); every metadata
method returns without throwing; empty result sets have spec-correct columns; integration test
asserting getTables/getColumns match the fixtures exactly.

**Accept when:** all metadata methods callable without exception; integration round-trip matches
fixtures; then perform the DBeaver smoke test — build the shaded jar, write `docs/DBEAVER.md`
with setup steps, and ask the user to confirm the tree view renders (tables + columns visible,
data preview works).

## Phase 5 — PreparedStatement

Implement `ParameterSubstitutor` and `LiteralEncoder` exactly per the skill (quote/comment-aware
scanning, typed literal rendering, `07001`/`07009` errors, binary-literal decision documented),
then `DataPressPreparedStatement` and `getParameterMetaData()`.

Unit tests are the heart of this phase: an adversarial substitution suite (placeholders inside
strings/comments/identifiers, quote doubling, injection attempts through string params, unicode,
all temporal types) — aim for exhaustive, table-driven tests. Integration test: parameterized
query end-to-end with every supported type.

**Accept when:** the adversarial suite passes; a string parameter containing `'; DROP TABLE x;--`
round-trips as data; unsupported binary params (if that's the decision) throw `0A000` with a
clear message.

## Phase 6 — Hardening, packaging, docs

1. Shaded jar per the skill (relocations, service-file preservation, `Automatic-Module-Name`);
   verify with `jar tf` that no unrelocated third-party packages leak, and that
   `META-INF/services/java.sql.Driver` survives shading.
2. Runtime detection of the missing `--add-opens` flag with a friendly SQLException; README
   section for it.
3. `docs/COMPATIBILITY.md` (JDBC surface matrix), README rewrite (quick start, URL format,
   properties table, limitations: read-only, single-dataset SQL, no server-side binding,
   validation-query note for pools), CHANGELOG.md, `maven-publish` config prepared but
   publishing left manual.
4. Final pass: run the whole suite on JDK 11, 17, 21; fix warnings; ensure a plain
   `Class.forName`-free `DriverManager` flow works from a scratch `main()` using only the shaded
   jar on the classpath.

**Accept when:** shaded jar passes the scratch-classpath test on all three JDKs; docs complete;
CI green; version tagged `v0.1.0`.

---

## Standing orders

- Read `.claude/skills/datapress-jdbc/SKILL.md` at the start of every session in this repo.
- Never weaken a test to make it pass; fix the driver or, if the contract was wrong, fix
  `docs/CONTRACT.md` + the skill + the test together in one commit.
- Prefer boring, explicit Java. No reflection tricks, no annotation processors, no Lombok.
- Keep public API surface = `java.sql` only; everything else stays in `internal`.
- If you discover a server-side gap that blocks correctness (e.g. Arrow media type mismatch,
  table-less SELECT rejection breaking a tool), don't work around it silently: document it in
  `docs/SERVER_ISSUES.md` with a proposed server change, and implement the cleanest client-side
  mitigation available.
