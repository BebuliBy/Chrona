"""SQL-only migration probe, not Android Room/Keystore runtime verification."""
import json
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
SCHEMAS = ROOT / "chrona/schemas/by.bebuliby.chrona.data.ChronaDatabase"


def schema(version):
    return json.loads((SCHEMAS / f"{version}.json").read_text())["database"]


def create(db, version):
    for table in schema(version)["entities"]:
        db.execute(table["createSql"].replace("${TABLE_NAME}", table["tableName"]))
        for index in table.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table["tableName"]))


class MigrationTest(unittest.TestCase):
    def test_real_migration_statements_match_v2_and_preserve_v1_raw_rows(self):
        db = sqlite3.connect(":memory:")
        db.execute("PRAGMA foreign_keys=ON")
        create(db, 1)
        db.execute("INSERT INTO habits VALUES ('h','reading','5:pagesстраниц','AT_LEAST','MANUAL','2026-10-18T00:00:00Z',NULL,NULL)")
        db.execute("INSERT INTO manual_entries VALUES ('e','h','2026-10-18|UTC','123456789.123456789','2026-10-18T00:00:00Z',NULL)")
        source = (ROOT / "chrona/src/main/java/by/bebuliby/chrona/data/ClientMigration.kt").read_text()
        statements = re.findall(r'^\s*"(CREATE [^"\n]+)"[,\s]*$', source, re.M)
        self.assertEqual(5, len(statements))
        for sql in statements:
            db.execute(sql)
        self.assertEqual("123456789.123456789", db.execute("SELECT value FROM manual_entries").fetchone()[0])
        expected = sqlite3.connect(":memory:")
        create(expected, 2)
        for entity in schema(2)["entities"]:
            name = entity["tableName"]
            self.assertEqual(list(expected.execute(f"PRAGMA table_info(`{name}`)")), list(db.execute(f"PRAGMA table_info(`{name}`)")))
        self.assertEqual([], list(db.execute("PRAGMA foreign_key_check")))

    def test_frozen_request_survives_sql_roundtrip_and_failed_attempt(self):
        db = sqlite3.connect(":memory:")
        create(db, 2)
        body = '{"operations":[{"baseRevision":"12","payload":{"value":"12.7500"}}]}'
        db.execute("INSERT INTO wire_requests VALUES ('op','https://example.test','a','POST','/v1/sync/push',?,'op',NULL)", (body,))
        db.execute("UPDATE wire_requests SET errorCode='REVISION_CONFLICT' WHERE requestId='op'")
        self.assertEqual(body, db.execute("SELECT body FROM wire_requests").fetchone()[0])
        self.assertEqual(1, db.execute("SELECT count(*) FROM wire_requests").fetchone()[0])

    def test_ack_cursor_transaction_rollback_keeps_pending_operations(self):
        db = sqlite3.connect(":memory:")
        create(db, 2)
        db.execute("INSERT INTO outbox VALUES ('op','manual_entry','e','upsert',NULL,'{}','2026-10-18T00:00:00Z')")
        db.execute("INSERT INTO sync_meta VALUES ('main','https://example.test','account','old',NULL,1,NULL,NULL)")
        db.commit()
        with self.assertRaises(RuntimeError):
            with db:
                db.execute("DELETE FROM outbox WHERE operationId='op'")
                db.execute("UPDATE sync_meta SET cursor='new'")
                raise RuntimeError("simulated failure")
        self.assertEqual(1, db.execute("SELECT count(*) FROM outbox").fetchone()[0])
        self.assertEqual('old', db.execute("SELECT cursor FROM sync_meta").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
