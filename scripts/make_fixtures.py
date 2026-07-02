#!/usr/bin/env python3
"""Generate the small Parquet fixtures used by the integration tests.

Run with uv (no global installs needed):

    uv run --with pyarrow scripts/make_fixtures.py

Files are written to src/integrationTest/resources/fixtures/ and are committed so the
integration suite works without regenerating them. Re-run this only when the fixture
shape changes; keep the files tiny.
"""

from __future__ import annotations

import datetime as dt
import decimal
import pathlib

import pyarrow as pa
import pyarrow.parquet as pq

FIXTURES = pathlib.Path(__file__).resolve().parent.parent / (
    "src/integrationTest/resources/fixtures"
)


def write(table: pa.Table, name: str) -> None:
    FIXTURES.mkdir(parents=True, exist_ok=True)
    path = FIXTURES / name
    pq.write_table(table, path)
    print(f"wrote {path} ({path.stat().st_size} bytes, {table.num_rows} rows)")


def people() -> pa.Table:
    return pa.table(
        {
            "id": pa.array([1, 2, 3, 4], pa.int64()),
            "name": pa.array(["alice", "bob", "carol", None], pa.string()),
            "active": pa.array([True, False, True, None], pa.bool_()),
            "score": pa.array([1.5, 2.25, None, 4.0], pa.float64()),
            "created": pa.array(
                [
                    dt.datetime(2021, 1, 15, 12, 30, 15),
                    dt.datetime(2022, 6, 1, 8, 0, 0),
                    dt.datetime(2023, 12, 31, 23, 59, 59),
                    None,
                ],
                pa.timestamp("us", tz="UTC"),
            ),
        }
    )


def types() -> pa.Table:
    # Row 0 fully populated; row 1 all-NULL. Covers the mapped Arrow types that both
    # the DuckDB and DataFusion backends can emit from Parquet.
    ts = dt.datetime(2021, 1, 15, 12, 30, 15)
    return pa.table(
        {
            "c_bool": pa.array([True, None], pa.bool_()),
            "c_int8": pa.array([7, None], pa.int8()),
            "c_int16": pa.array([300, None], pa.int16()),
            "c_int32": pa.array([100000, None], pa.int32()),
            "c_int64": pa.array([10_000_000_000, None], pa.int64()),
            "c_float32": pa.array([1.5, None], pa.float32()),
            "c_float64": pa.array([2.5, None], pa.float64()),
            "c_decimal": pa.array(
                [decimal.Decimal("123.45"), None], pa.decimal128(10, 2)
            ),
            "c_utf8": pa.array(["hello", None], pa.string()),
            "c_binary": pa.array([b"\x01\x02\x03", None], pa.binary()),
            "c_date": pa.array([dt.date(2021, 1, 15), None], pa.date32()),
            "c_time": pa.array([dt.time(12, 30, 15), None], pa.time64("us")),
            "c_ts": pa.array([ts, None], pa.timestamp("us")),
            "c_tstz": pa.array([ts, None], pa.timestamp("us", tz="UTC")),
        }
    )


def numbers(rows: int = 20_000) -> pa.Table:
    # Large enough to span more than one Arrow record batch (backend batch size is a
    # few thousand rows) so the streaming / max_rows tests are meaningful.
    return pa.table({"n": pa.array(range(rows), pa.int64())})


def main() -> None:
    write(people(), "people.parquet")
    write(types(), "types.parquet")
    write(numbers(), "numbers.parquet")


if __name__ == "__main__":
    main()
