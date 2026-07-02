# CLAUDE.md

Guidance for AI coding sessions in this repository.

## Read these first, every session

1. **`.claude/skills/datapress-jdbc/SKILL.md`** — the authoritative development skill:
   server API contract, architecture, type mapping, DatabaseMetaData rules,
   PreparedStatement substitution rules, error mapping, testing strategy, packaging.
2. **`docs/CONTRACT.md`** — the verified DataPress server API contract (single source of
   truth once created). Currently verified against server **v0.4.27**.
3. **`docs/PROGRESS.md`** — running log of what's done, decisions made, and open questions.

If the skill and `docs/CONTRACT.md` disagree, `docs/CONTRACT.md` wins. If either disagrees
with the live server/docs, the live server wins — fix `docs/CONTRACT.md`, the skill, and the
affected test together in one commit.

## Working agreement

- Work in phases (see `INSTRUCTIONS.md`). End each phase with: tests green,
  `./gradlew build` clean, a conventional commit, and a note appended to `docs/PROGRESS.md`.
- Never weaken a test to make it pass. Fix the driver, or fix the contract + skill + test together.
- Public API surface = `java.sql` only. Everything else lives under `org.datapress.jdbc.internal`.
- Prefer boring, explicit Java. No reflection tricks, no annotation processors, no Lombok.

## Common commands

```bash
./gradlew build                              # spotless + compile + unit tests + shaded jar
./gradlew test                               # unit tests
./gradlew spotlessApply                      # auto-format
DATAPRESS_URL=… ./gradlew integrationTest    # integration tests (needs a live server)
```
