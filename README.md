# StarForge

Deterministic star system generator with MySQL export pipeline.

The project generates stars, planets and moons from source catalog data (`stars.csv`), computes physical/environmental attributes, and exports results into a unified DB table (`StarSystems`).

## What it does

- reads stellar catalog (distance-sorted subset for UI workflow);
- generates procedural systems (star, planets, moons) from deterministic seeds;
- derives atmosphere/temperature/water/resource descriptors;
- serializes planetary/moon compact `PData` payload;
- exports objects to MySQL (`ObjectType` 1/2/3) with `StarSystemID` partitioning;
- provides correction mode for close-moon Roche-limit handling.

## Entry point

- GUI app: `org.example.starforge.gui.App`

## Tech stack

- Java 21
- Maven
- JavaFX
- Apache Commons CSV
- MySQL Connector/J
- Jackson

## Repository structure

- `src/main/java/org/example/starforge/gui/App.java` - UI, export and corrections controls.
- `src/main/java/org/example/starforge/core/gen/` - system generation pipeline.
- `src/main/java/org/example/starforge/core/model/` - domain models and physics data.
- `src/main/java/org/example/starforge/core/io/MySqlPerSystemTableExporter.java` - DB export/corrections.
- `PData_Schema.md` - strict field format for planet/moon payload.
- `CONTEXT_2026-02-19.md` - migration context (`StarSystem_N` -> `StarSystems`).

## Local DB config (safe)

Create local DB config (ignored by git):

```bash
cd /home/vladimirs/StarSystemsGenerator/Starforge
mkdir -p local
cp db.local.properties.example local/db.local.properties
```

Example values:

```properties
db.url=jdbc:mysql://localhost:3306/EXOLOG
db.user=YOUR_DB_USER
db.password=YOUR_DB_PASSWORD
```

Resolution order in exporter:
1. `local/db.local.properties`
2. `-Dstarforge.db.url|user|pass`
3. `STARFORGE_DB_URL|USER|PASS`
4. hardcoded non-secret defaults for url/user only

## Build

```bash
cd /home/vladimirs/StarSystemsGenerator/Starforge
mvn -q -DskipTests compile
```

## Run GUI

```bash
mvn -q javafx:run
```

## Export behavior

Main export writes to **single table** `StarSystems`.

- For each generated system, exporter rewrites objects only for its `StarSystemID`.
- Rotation speed/inclination updates are part of the main export path.
- Corrections mode iterates distinct `StarSystemID` values.

## PData payload

Planets/moons `ObjectDescription` uses a compact CSV payload with fixed schema (38 fields).
See full contract in `PData_Schema.md`.

## Notes

- `stars.csv` is large; first-run operations may take time.
- Generation is deterministic for same seed/catalog row.
