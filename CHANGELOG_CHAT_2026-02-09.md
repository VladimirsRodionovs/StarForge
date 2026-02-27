# Chat Summary (2026-02-09)

## 1) Mass Formatting
- `SystemText` now shows small masses without rounding to 0.000.

## 2) `ObjectPlanetType` classification (DB export)
- `5`: no solid surface (gas/ice/sub-neptune, HHE, envelope > 0.02)
- `6`: extreme (pressure >= 200 or tMinK >= 550)
- `2`: landable planet, `4`: landable moon
- same rules for planets and moons

## 3) MoonModel expansion
- Added fields: `atmosphereType`, `envelopeMassEarth`, `tMinK`, `tMaxK`, `teqK`, `tMeanK`, `greenhouseDeltaK`, `type`, `basePressureBar`, `fracIron/fracRock/fracIce`, `waterMassFrac`, `waterCoverage`, `waterGELkm`.
- All propagated from `MoonGenerator` pipeline.

## 4) JSON -> CSV string for ObjectDescription
- `ObjectDescription` for planets and moons is now a single CSV string (`PData`).
- Star JSON is still kept (ObjectType=1).
- Fixed order in `MySqlPerSystemTableExporter` comment:
```
tp,mE,envME,baseP,rE,sG,fracIron,fracRock,fracIce,waterMassFrac,
pBar,atmT,pN2,pO2,pCO2,pH2O,teqK,gsDK,tMeanK,tMinK,tMaxK,
tLock,tidHeat,habReg,biProv,biSur,biMic,biSub,heavHyd,lghtHyd,evnt,evAge,
wCov,wGel,o2Pct,co2Pct,n2Pct,PRes
```
- Enums stored by `ordinal`, booleans as `1/0`, numbers `%.4f`.

## 5) Resources (`PRes`)
- New `ResourceModelUtil`.
- Order: `metal,silicates,water_ice,methane_ice,ammonia_ice,organics`.
- Influences: composition fractions, temperature, snow line, star luminosity + activity,
  envelope suppression, metal burial for large bodies (surface availability).

## 6) Atmosphere percentages
- `o2Pct`, `co2Pct`, `n2Pct` included in `PData` (derived from atmosphere map).

## 7) Water coverage
- New enum `WaterCoverage`: `DRY, LAKES, SEAS, OCEAN, OCEANS, MANY_OCEANS, ARCHIPELAGOS, OCEAN_PLANET`.
- Computed from liquid window + `waterGELkm`.
- Exported as `wCov`, `wGel`.

## 8) Removed `hydrocarbonDeposits`
- Removed from model, logic, export, and UI.

## 9) Binary system cap
- New `CompanionIndex` by `base` + `x,y,z`.
- Cap: `capAU = 0.3 * separationAU`.
- Applied to disk, embryo generation, and post-filter (remove planets/zones beyond cap).

## Main files changed
- `core/io/MySqlPerSystemTableExporter.java`
- `core/model/MoonModel.java`
- `core/gen/MoonGenerator.java`
- `core/physics/BiospherePhysics.java`
- `core/model/WaterCoverage.java`
- `core/model/ResourceModelUtil.java` (new)
- `core/gen/CompanionIndex.java` (new)
- `core/gen/SystemGenerator.java`
- `core/gen/EmbryoGenerator.java`
- `core/model/DiskModel.java`
- `gui/SystemText.java`
- `gui/App.java`
