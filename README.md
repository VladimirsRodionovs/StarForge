# Starforge

Deterministic star-system generator with MySQL export.

Starforge reads stellar catalog data, generates stars/planets/moons from deterministic seeds, calculates physical and environmental parameters, and writes results to `StarSystems`.

## Highlights
- Deterministic procedural generation pipeline.
- Physics-driven derived parameters (orbit, atmosphere, biosphere-related signals).
- Compact `PData` payload for planet/moon objects.
- Per-system export partitioning by `StarSystemID`.

## Tech Stack
- Java 21
- Maven
- JavaFX
- Apache Commons CSV
- Jackson
- MySQL Connector/J

## Quick Start
1. Prepare local DB config:
   - `mkdir -p local`
   - `cp db.local.properties.example local/db.local.properties`
2. Build:
   - `mvn -q -DskipTests compile`
3. Run GUI:
   - `mvn -q javafx:run`

## Documentation
- Internal design: `ARCHITECTURE.md`
- PData schema: `PData_Schema.md`
- Migration context: `CONTEXT_2026-02-19.md`

## Contact
vladimirs.rodionovs@gmail.com
