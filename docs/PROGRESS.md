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
