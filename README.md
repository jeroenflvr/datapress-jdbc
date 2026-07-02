# datapress-jdbc

A read-only, Type-4 (pure Java) JDBC driver for
[DataPress](https://github.com/jeroenflvr/datapress) — a Rust columnar HTTP query server
over Parquet/Delta. The driver wraps DataPress's REST + Arrow IPC API so BI tools, query
consoles, and JVM applications can talk to it over standard `java.sql`.

> **Status: pre-alpha.** The public surface and behaviour may change without notice.
> Not yet published to Maven Central.

## What it is

- Pure-Java JDBC driver (`jdbc:datapress://…`), no native components.
- **Read-only and analytics-oriented**: `SELECT` / `WITH … SELECT` / `DESCRIBE` only.
- Streams results as Apache Arrow IPC, decoded lazily so large result sets use constant memory.
- Tool compatibility (DBeaver, DataGrip, …) via a synthesized `DatabaseMetaData`, not SQL rewriting.

## URL format

```
jdbc:datapress://host[:port][/][?token=<bearer>&tls=false&...]
```

## Limitations (by design)

- Read-only; a single dataset per SQL statement (Phase-1 server limit).
- No server-side parameter binding — `PreparedStatement` substitutes client-side.
- The server's raw-SQL endpoint is **disabled by default** and must be enabled
  (`[sql].enabled = true`).

## Development

The authoritative development guide is the project skill at
[`.claude/skills/datapress-jdbc/SKILL.md`](.claude/skills/datapress-jdbc/SKILL.md); the
verified server API contract is in [`docs/CONTRACT.md`](docs/CONTRACT.md). Read both before
touching driver code.

```bash
./gradlew build          # spotless + compile + unit tests + shaded jar
./gradlew test           # unit tests only
DATAPRESS_URL=… ./gradlew integrationTest   # integration tests (needs a live server)
```

On JDK 16+, Arrow's off-heap memory requires
`--add-opens=java.base/java.nio=ALL-UNNAMED` on the JVM running the driver.

## License

[Apache License 2.0](LICENSE).
