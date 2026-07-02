# DBeaver smoke test

Manual verification that the DataPress JDBC driver exposes tables and columns to a real SQL
tool. This exercises the Phase 4 `DatabaseMetaData` surface (`getTables` / `getColumns`) plus the
Phase 2 query path (data preview).

## 1. Build the driver jar

```bash
./gradlew build
```

The shaded, self-contained driver is:

```
build/libs/datapress-jdbc-0.1.0-SNAPSHOT.jar
```

It bundles (relocated) Arrow, Jackson, and FlatBuffers, so no extra jars are required.

## 2. Start a DataPress server with the sample fixtures

```bash
DATAPRESS_PORT=18080 ./scripts/run-server.sh up datafusion
```

This serves the `people`, `types`, and `numbers` fixtures at
`http://127.0.0.1:18080`. Tear it down later with `./scripts/run-server.sh down`.

## 3. Register the driver in DBeaver

1. **Database → Driver Manager → New**.
2. Settings:
   - **Driver Name:** `DataPress`
   - **Class Name:** `org.datapress.jdbc.DataPressDriver`
   - **URL Template:** `jdbc:datapress://{host}:{port}/`
   - **Default Port:** `18080`
3. **Libraries → Add File** → select
   `build/libs/datapress-jdbc-0.1.0-SNAPSHOT.jar`.
4. **Find Class** should resolve `org.datapress.jdbc.DataPressDriver`. Click **OK**.

## 4. Create a connection

- **JDBC URL:** `jdbc:datapress://127.0.0.1:18080/`
- Leave user/password empty (this image runs without auth). If your server requires a bearer
  token, add it as a connection property named `token` (or `password`).
- **Test Connection** should succeed (the driver performs a `GET /version` handshake).

## 5. What to confirm

In the database navigator, expand the connection:

- **Catalog:** `datapress` → **Schema:** `main`.
- **Tables:** `people`, `types`, `numbers` are listed.
- Expand a table's **Columns**; for `people` you should see:

  | Column  | Type                     |
  | ------- | ------------------------ |
  | id      | BIGINT                   |
  | name    | VARCHAR                  |
  | active  | BOOLEAN                  |
  | score   | DOUBLE                   |
  | created | TIMESTAMP WITH TIME ZONE |

- Double-click `people` → **Data** tab: rows render (alice, bob, carol, …), including the
  dictionary-encoded `name` column and the `NULL` values in the last rows.
- Right-click the connection → **SQL Editor** and run `SELECT * FROM types` to confirm the wide
  type coverage previews correctly.

If the tree shows the tables and columns and the data preview loads, the smoke test passes.
