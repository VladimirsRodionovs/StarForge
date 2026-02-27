# ARCHITECTURE — Starforge

## Purpose
`Starforge` is a deterministic star-system generator with MySQL export. Input is stellar catalog data (`stars.csv`), output is system objects (star/planets/moons) in `StarSystems`.

## Tech stack
- Java 21
- Maven
- JavaFX (desktop UI)
- Apache Commons CSV
- Jackson
- MySQL Connector/J

## High-level flow
1. UI (`gui/App`) selects a star row and generation parameters.
2. `core/io/StarCsvReader` loads source catalog data.
3. `core/gen/SystemGenerator` builds a deterministic system from seed.
4. `core/physics/*` computes orbital, atmospheric, and structural parameters.
5. `core/model/*` stores domain objects.
6. `core/io/MySqlPerSystemTableExporter` persists objects to `StarSystems` by `StarSystemID`.

## Layers
### 1) UI
- Package: `org.example.starforge.gui`
- Key classes:
  - `App` — main screen and orchestration;
  - `OrbitCanvas` — orbit visualization;
  - `SystemText` — textual system summary.

### 2) Generation core
- Package: `core/gen`
- Key classes:
  - `SystemGenerator`, `EmbryoGenerator`, `MoonGenerator`, `DiskGenerator`, `OuterRingGenerator`.
- Responsibility: system construction pipeline.

### 3) Physics
- Package: `core/physics`
- Key classes:
  - `OrbitStabilityPhysics`, `AtmospherePhysics`, `AtmosphereEscapePhysics`, `GasAccretionPhysics`, `BiospherePhysics`.
- Responsibility: derived physical calculations.

### 4) Domain model
- Package: `core/model`
- Main entities:
  - `StarModel`, `PlanetModel`, `MoonModel`, `SystemModel`, `Orbit`, typed enums.

### 5) IO and persistence
- Package: `core/io`
- Key classes:
  - `StarCsvReader` — input catalog reader;
  - `MySqlPerSystemTableExporter` — DB export and correction workflow.

### 6) Determinism
- Package: `core/random`
- Classes: `DeterministicRng`, `SeedUtil`
- Responsibility: reproducible output for same seed.

## Storage and contracts
- Main table: `StarSystems`
- Object kinds: `ObjectType` 1/2/3 (star/planet/moon)
- Planet/moon payload: compact `PData` format (see `PData_Schema.md`)

## Integrations
- Source: `stars.csv`
- Sink: MySQL `EXOLOG` (`StarSystems`)
- Downstream:
  - `ExodusServer` (runtime star system data)
  - `PlanetSurfaceGenerator` (astro input for surface generation)

## Extension points
- New physics models: add in `core/physics/*` and wire into pipeline.
- New generation stages: add in `core/gen/*` with explicit order.
- New PData fields: update serializer and schema docs together.

## Risks
- Growing coupling between UI orchestration and generation logic.
- Compatibility regressions when compact PData schema changes.
- Heavy operations on large batches / first-time runs.

## Quick navigation
- Entry point: `src/main/java/org/example/starforge/gui/App.java`
- Generation core: `src/main/java/org/example/starforge/core/gen/`
- Export: `src/main/java/org/example/starforge/core/io/MySqlPerSystemTableExporter.java`
