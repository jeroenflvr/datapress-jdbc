# datapress-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/org.datap-rs/datapress-jdbc.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.datap-rs/datapress-jdbc)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A read-only, Type-4 (pure Java) JDBC driver for
[DataPress](https://github.com/jeroenflvr/datapress) — a Rust columnar HTTP query server
over Parquet/Delta. The driver wraps DataPress's REST + Arrow IPC API so BI tools, query
consoles, and JVM applications can talk to it over standard `java.sql`.

> **Status: early release.** The public surface and behaviour may still change before 1.0.

## What it is

- Pure-Java JDBC driver (`jdbc:datapress://…`), no native components.
- **Read-only and analytics-oriented**: `SELECT` / `WITH … SELECT` / `DESCRIBE` only.
- Streams results as Apache Arrow IPC, decoded lazily so large result sets use constant memory.
- Tool compatibility (DBeaver, DataGrip, …) via a synthesized `DatabaseMetaData`, not SQL rewriting.
- Single self-contained (shaded) jar — Arrow and Jackson are relocated internally, so there are
  no dependency conflicts on your classpath.

## Installation

The driver is published to **Maven Central** under the coordinates:

| | |
|------------|------------------|
| groupId | `org.datap-rs` |
| artifactId | `datapress-jdbc` |
| version | `0.1.0` |

Browse it on [central.sonatype.com](https://central.sonatype.com/artifact/org.datap-rs/datapress-jdbc).

**Maven** — `pom.xml`:

```xml
<dependency>
  <groupId>org.datap-rs</groupId>
  <artifactId>datapress-jdbc</artifactId>
  <version>0.1.0</version>
</dependency>
```

**Gradle (Kotlin DSL)** — `build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.datap-rs:datapress-jdbc:0.1.0")
}
```

**Gradle (Groovy DSL)** — `build.gradle`:

```groovy
dependencies {
    implementation 'org.datap-rs:datapress-jdbc:0.1.0'
}
```

For a GUI SQL tool (DBeaver, DataGrip, …), download the shaded jar from the
[Maven Central artifact page](https://central.sonatype.com/artifact/org.datap-rs/datapress-jdbc)
and register it as a new driver with class `org.datap_rs.jdbc.DataPressDriver`.
See [docs/DBEAVER.md](docs/DBEAVER.md) for a step-by-step DBeaver walkthrough.

## Usage

The driver auto-registers via the JDBC `ServiceLoader`, so no `Class.forName(...)` is
needed on modern JVMs.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

String url = "jdbc:datapress://localhost:8080/?token=" + bearerToken;

try (Connection conn = DriverManager.getConnection(url);
     Statement stmt = conn.createStatement();
     ResultSet rs = stmt.executeQuery("SELECT id, name FROM my_dataset LIMIT 100")) {
    while (rs.next()) {
        System.out.println(rs.getLong("id") + " " + rs.getString("name"));
    }
}
```

Credentials can also be passed via `java.util.Properties` (handy for tools with
user/password fields — `password` is accepted as an alias for `token`):

```java
Properties props = new Properties();
props.setProperty("token", bearerToken);
Connection conn = DriverManager.getConnection("jdbc:datapress://localhost:8080/", props);
```

### JVM flag for Arrow (JDK 16+)

Arrow's off-heap memory needs an `--add-opens` on the JVM running the driver:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
```

## URL format

```
jdbc:datapress://host[:port][/][?prop=value&...]
```

`jdbc:datapress:http://…` and `jdbc:datapress:https://…` are also accepted.

### Connection properties

Accepted both as URL query parameters and via `java.util.Properties` (Properties wins on conflict):

| Property | Description | Default |
|-------------------|---------------------------------------------------------------------|-----------|
| `token` | Bearer token. `password` is accepted as an alias; `user` is ignored. | — |
| `tls` | `true` \| `false` — use HTTPS. | `false` |
| `connectTimeout` | Connect timeout in milliseconds. | `10000` |
| `socketTimeout` | Socket/read timeout in milliseconds. | `300000` |
| `maxRows` | Default per-statement row cap when the app doesn't call `setMaxRows`. | unlimited |
| `applicationName` | Sent as `User-Agent: datapress-jdbc/<version> (<applicationName>)`. | — |

Connecting performs a single `GET /version` handshake and fails fast on an unreachable
or unauthorized server.

## Limitations (by design)

- Read-only; a single dataset per SQL statement (Phase-1 server limit).
- No server-side parameter binding — `PreparedStatement` substitutes client-side.
- The server's raw-SQL endpoint is **disabled by default** and must be enabled
  (`[sql].enabled = true`).

## Development

```bash
./gradlew build          # spotless + compile + unit tests + shaded jar
./gradlew test           # unit tests only
DATAPRESS_URL=… ./gradlew integrationTest   # integration tests (needs a live server)
```

If you have [go-task](https://taskfile.dev) installed, common flows are wrapped in the
[Taskfile.yml](Taskfile.yml):

```bash
task build                 # format, compile, test, shaded jar
task test                  # unit tests
task release -- 0.2.0      # bump version and publish to Maven Central
```

## License

[Apache License 2.0](LICENSE).
