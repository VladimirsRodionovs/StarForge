# PData CSV Schema (Planets + Moons)

## Field Order (strict)
1. `tp` — body type string (UI label)
2. `mE` — mass in Earth masses
3. `envME` — envelope mass in Earth masses
4. `baseP` — base pressure at envelope boundary (bar)
5. `rE` — radius in Earth radii
6. `sG` — surface gravity (in g)
7. `fracIron` — iron fraction (0..1)
8. `fracRock` — rock/silicates fraction (0..1)
9. `fracIce` — ice fraction (0..1)
10. `waterMassFrac` — water mass fraction in solids (0..1)
11. `pBar` — total surface pressure (bar)
12. `atmT` — AtmosphereType enum ordinal
13. `pN2` — N2 partial pressure (bar)
14. `pO2` — O2 partial pressure (bar)
15. `pCO2` — CO2 partial pressure (bar)
16. `pH2O` — H2O partial pressure (bar)
17. `teqK` — equilibrium temperature (K)
18. `gsDK` — greenhouse delta (K)
19. `tMeanK` — mean surface temperature (K)
20. `tMinK` — min surface temperature (K)
21. `tMaxK` — max surface temperature (K)
22. `tLock` — tidally locked (1/0)
23. `tidHeat` — tidal heating level string (`NONE/WEAK/STRONG`)
24. `habReg` — habitable region present (1/0)
25. `biProv` — BiosphereProvenance enum ordinal
26. `biSur` — BiosphereSurfaceStatus enum ordinal
27. `biMic` — BiosphereMicrobialStatus enum ordinal
28. `biSub` — BiosphereSubsurfaceStatus enum ordinal
29. `heavHyd` — heavy hydrocarbons (1/0)
30. `lghtHyd` — light hydrocarbons (1/0)
31. `evnt` — event string
32. `evAge` — event age (Myr)
33. `wCov` — WaterCoverage enum ordinal
34. `wGel` — global equivalent water layer depth (km)
35. `o2Pct` — O2 percent of total atmosphere (%)
36. `co2Pct` — CO2 percent of total atmosphere (%)
37. `n2Pct` — N2 percent of total atmosphere (%)
38. `PRes` — resource mix string: `metal,silicates,water_ice,methane_ice,ammonia_ice,organics`

## Enum Ordinals
### AtmosphereType
0 `NONE`
1 `THIN`
2 `SUB_EARTH`
3 `N2_DOMINATED`
4 `CO2_THICK`
5 `STEAM`
6 `HHE`

### WaterCoverage
0 `DRY`
1 `LAKES`
2 `SEAS`
3 `OCEAN`
4 `OCEANS`
5 `MANY_OCEANS`
6 `ARCHIPELAGOS`
7 `OCEAN_PLANET`

### BiosphereProvenance
0 `ABIOGENIC`
1 `PRIMORDIAL`
2 `PRIMORDIAL_DORMANT`
3 `SEEDED_RECENT`
4 `SEEDED_RECENT_DORMANT`
5 `UNKNOWN`

### BiosphereSurfaceStatus
0 `NONE`
1 `COMPLEX`
2 `DEAD`

### BiosphereMicrobialStatus
0 `NONE`
1 `PRESENT`
2 `RELIC`

### BiosphereSubsurfaceStatus
0 `NONE`
1 `PRESENT`
2 `RELIC`

## Example
```
Rocky,1.0000,0.0000,NaN,1.0000,1.0000,0.3200,0.6500,0.0300,0.0300,1.0000,3,0.7800,0.2100,0.0004,0.0100,255.0,33.0,288.0,273.0,310.0,0,NONE,1,1,1,1,1,0,,0.0000,4,2.7000,21.0,0.04,78.9,0.3200,0.6500,0.0300,0.0000,0.0000,0.0000
```

## Parsing Notes
- Split by comma into 38 fields.
- Empty field means "missing/NaN".
- Enums are ordinal indexes (see tables above).
- `PRes` is a nested CSV of 6 values; split that field by comma after the main split.
