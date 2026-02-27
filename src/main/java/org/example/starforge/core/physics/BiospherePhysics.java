package org.example.starforge.core.physics;

import org.example.starforge.core.gen.EventCatalog;
import org.example.starforge.core.model.*;
import org.example.starforge.core.random.DeterministicRng;

import java.util.LinkedHashMap;

/**
 * Biosphere pass (reworked pipeline):
 *
 * Goals (gameplay + internal consistency):
 *  1) Decide whether a planet can host liquid water by Tmin/Tmax + pressure (size not important here).
 *  2) Check insolation proxy WITHOUT greenhouse (Teq) to gate NATURAL origin chances (Primordial).
 *  3) Apply origin chances:
 *        - Primordial: 15% if eligible (native life)
 *        - Seeded: 90% if eligible (artificial multi-stage seeding ~20–30 Myr ago, includes complex life)
 *  4) Apply biosphere-driven atmospheric transformation:
 *        - Primordial: stronger "natural" oxygenation / CO2 drawdown when wet-belt is plausible.
 *        - Seeded: controlled CO2 regulation to hit target Tmin/Tmax window (avoid overshoot; rare failure only).
 *  5) Recompute climate from new atmosphere (no insolation gate now), then assign:
 *        - If liquid water window exists: COMPLEX + MICROBIAL + SUBSURFACE
 *        - Else: microbial/subsurface/sterilized outcomes based on Tmin/Tmax thresholds
 *  6) Apply rare "dead biosphere" events (~10%) with partial/legacy atmosphere effects.
 *
 * This pass expects AtmospherePhysics.computeForPlanet + recomputeDerivedFromAtmosphere has already run once.
 * SystemGenerator currently calls recomputeDerivedFromAtmosphere again after this pass; that's fine.
 */
public final class BiospherePhysics {

    private BiospherePhysics() {}

    // --- Core probabilities (per eligible planet) ---
    private static final double PRIMORDIAL_CHANCE = 0.15;
    private static final double SEEDED_CHANCE = 0.90;

    // --- Liquid water criteria (pressure + Tmin/Tmax window) ---
    private static final double WATER_T_FREEZE_K = 273.15;
    private static final double WATER_T_BOIL_K_AT_1BAR = 373.15;

    // We do not simulate phase diagram in detail; this is a gameplay-friendly gate:
    private static final double P_LIQUID_MIN_BAR = 0.06;   // above triple-point-ish; also avoids tiny airless worlds
    private static final double P_LIQUID_MAX_BAR = 600.0;  // beyond this, "surface liquid water" is questionable for our model

    // --- Primordial insolation (Teq) wet-belt gate (no greenhouse) ---
    private static final double PRIMORDIAL_TEQ_MIN_K = 220.0;
    private static final double PRIMORDIAL_TEQ_MAX_K = 310.0;

    // --- Seeded eligibility (broader; they can terraform difficult worlds) ---
    // Still reject extreme, physically hopeless conditions.
    private static final double SEEDED_TEQ_MIN_K = 160.0;
    private static final double SEEDED_TEQ_MAX_K = 520.0;

    // --- Seeded target climate window (as agreed) ---
    private static final double SEEDED_TMIN_TARGET_K = 263.15; // -10C
    private static final double SEEDED_TMAX_TARGET_K = 340.00; // +67C

    // Overshoot->iceball should be rare for seeded.
    private static final double SEEDED_OVERSHOOT_ICEBALL_CHANCE = 0.05;

    // Dead-biosphere events: after "good" biospheres exist.
    private static final double DEAD_BIOSPHERE_CHANCE = 0.10;

    // Earth ocean mass in Earth masses (~1.4e21 kg)
    private static final double EARTH_OCEAN_EARTH_MASS = 2.34e-4;
    private static final double EARTH_OCEAN_GEL_KM = 2.7;

    public static void applyForPlanet(StarModel star, PlanetModel p, DeterministicRng rng) {
        if (star == null || p == null || rng == null) return;

        // --- water coverage (always compute; used for later surface generation) ---
        double oceans = 0.0;
        if (p.invH2OEarth > 0.0) oceans = p.invH2OEarth / EARTH_OCEAN_EARTH_MASS;
        p.waterGELkm = oceans * EARTH_OCEAN_GEL_KM;

        double pressure = Math.max(0.0, p.pressureBar);
        boolean pressureOkForLiquid = (pressure >= P_LIQUID_MIN_BAR && pressure <= P_LIQUID_MAX_BAR);
        boolean liquidWindowNow = pressureOkForLiquid && hasLiquidWaterWindowByMinMax(p.tMinK, p.tMaxK);
        p.waterCoverage = liquidWindowNow ? classifyWaterCoverage(p.waterGELkm) : WaterCoverage.DRY;

        // Default statuses
        if (p.biosphereSurface == null) p.biosphereSurface = BiosphereSurfaceStatus.NONE;
        if (p.biosphereMicrobial == null) p.biosphereMicrobial = BiosphereMicrobialStatus.NONE;
        if (p.biosphereSubsurface == null) p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
        if (p.biosphereProvenance == null) p.biosphereProvenance = BiosphereProvenance.ABIOGENIC;

        // If already authored earlier (e.g., handcrafted content), don't override statuses;
        // still allow active biospheres to tweak gases (but we won't force provenance).
        boolean hasAny = (p.biosphereSurface != BiosphereSurfaceStatus.NONE)
                || (p.biosphereMicrobial != BiosphereMicrobialStatus.NONE)
                || (p.biosphereSubsurface != BiosphereSubsurfaceStatus.NONE);

        if (!hasAny) {
            generateBiospherePipeline(star, p, rng);
        } else {
            // Even for authored biospheres, allow the bio atmosphere shift if they are active.
            applyBioAtmosphereShiftLegacy(star, p, rng);
        }

        // Hydrocarbons are gameplay resources; compute after statuses are final-ish.
        double teq = (Double.isFinite(p.teqK) && p.teqK > 0.0)
                ? p.teqK
                : AtmospherePhysics.equilibriumTemperatureK(star, p, 0.30);
        generateHydrocarbons(p, teq, rng);
    }

    private static void generateBiospherePipeline(StarModel star, PlanetModel p, DeterministicRng rng) {

        // --- Base insolation proxy WITHOUT greenhouse ---
        double teqNoGH = AtmospherePhysics.equilibriumTemperatureK(star, p, 0.30);

        // --- Quick physical gates ---
        double pressure = Math.max(0.0, p.pressureBar);
        boolean pressureOkForLiquid = (pressure >= P_LIQUID_MIN_BAR && pressure <= P_LIQUID_MAX_BAR);

        boolean liquidWindowNow = pressureOkForLiquid && hasLiquidWaterWindowByMinMax(p.tMinK, p.tMaxK);

        // Hard sterilization for absurd steam/heat in our model (prevents nonsense cases).
        // Seeded can *attempt* difficult worlds, but we still reject truly hopeless conditions.
        double tMin = safeTemp(p.tMinK, p.tMeanK, teqNoGH);
        double tMax = safeTemp(p.tMaxK, p.tMeanK, teqNoGH);
        boolean extremeHot = (tMin > 650.0) || (p.tMeanK > 700.0);
        if (extremeHot) {
            setSterile(p, BiosphereProvenance.ABIOGENIC);
            return;
        }

        // --- Origin eligibility ---
        boolean primordialEligible =
                pressureOkForLiquid
                        && liquidWindowNow
                        && (teqNoGH >= PRIMORDIAL_TEQ_MIN_K && teqNoGH <= PRIMORDIAL_TEQ_MAX_K);

        boolean seededEligible =
                (pressure >= 0.02) // seeded missions can work with thinner atmospheres too
                        && (teqNoGH >= SEEDED_TEQ_MIN_K && teqNoGH <= SEEDED_TEQ_MAX_K);

        // --- Decide provenance (as agreed: primordial roll first, then seeded roll) ---
        boolean isPrimordial = false;
        boolean isSeeded = false;

        if (primordialEligible && rng.chance(PRIMORDIAL_CHANCE)) {
            isPrimordial = true;
        } else if (seededEligible && rng.chance(SEEDED_CHANCE)) {
            isSeeded = true;
        }

        if (!isPrimordial && !isSeeded) {
            // No life. Still might have interesting abiotic organics handled elsewhere.
            setSterile(p, BiosphereProvenance.ABIOGENIC);
            return;
        }

        // Cache "before-bio" partial pressures for potential partial/legacy atmospheres on collapse.
        double preN2 = p.pN2Bar;
        double preO2 = p.pO2Bar;
        double preCO2 = p.pCO2Bar;
        double preH2O = p.pH2OBar;
        double preTotal = p.pressureBar;

        // --- Apply atmosphere transformation ---
        if (isSeeded) {
            p.biosphereProvenance = BiosphereProvenance.SEEDED_RECENT;
            applySeededTerraforming(star, p, rng, teqNoGH);
        } else {
            p.biosphereProvenance = BiosphereProvenance.PRIMORDIAL;
            applyPrimordialBioShift(star, p, rng, teqNoGH);
        }

        // Recompute climate from updated atmosphere BEFORE classification.
        AtmospherePhysics.recomputeDerivedFromAtmosphere(star, p);

        // --- Classify biosphere by post-bio Tmin/Tmax only (no insolation gate now) ---
        classifyByPostBioClimate(star, p, rng);

        // --- Rare dead-biosphere events (10%), with partial legacy atmosphere ---
        boolean activeComplex = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX);
        boolean activeAny = activeComplex || (p.biosphereMicrobial == BiosphereMicrobialStatus.PRESENT)
                || (p.biosphereSubsurface == BiosphereSubsurfaceStatus.PRESENT);

        if (activeAny && rng.chance(DEAD_BIOSPHERE_CHANCE)) {
            applyDeadBiosphereEvent(star, p, rng, preN2, preO2, preCO2, preH2O, preTotal);
            AtmospherePhysics.recomputeDerivedFromAtmosphere(star, p);
        }
    }

    // -------------------- Atmosphere transformations --------------------

    /**
     * Primordial: only "strong oxygenation" if the planet is in the wet-belt by Teq (no greenhouse).
     * Otherwise keep the atmosphere mostly as-is (microbes may exist but won't terraform it strongly here).
     */
    private static void applyPrimordialBioShift(StarModel star, PlanetModel p, DeterministicRng rng, double teqNoGH) {
        // If not in primordial wet-belt, avoid major terraforming effects.
        boolean wetBelt = (teqNoGH >= PRIMORDIAL_TEQ_MIN_K && teqNoGH <= PRIMORDIAL_TEQ_MAX_K);
        if (!wetBelt) return;

        // Target CO2: draw down strongly but not to zero.
        double co2Floor = 0.0002; // bar
        double co2Ceil = 0.05;    // bar
        double targetCO2 = clamp(rng.logNormal(0.002, 0.8), co2Floor, co2Ceil);

        // Oxygen: can be significant, but sinks exist.
        double targetO2 = clamp(rng.logNormal(0.18, 0.40), 0.03, 0.35);

        // Total pressure: keep roughly similar but ensure it doesn't go crazy.
        double total = clamp(p.pressureBar, 0.2, 8.0);

        // Water vapor stays from current, but we don't try to "inject" water here.
        double targetH2O = clamp(p.pH2OBar, 0.0, 200.0);

        double targetN2 = Math.max(0.05, total - targetCO2 - targetO2 - targetH2O);
        if (targetN2 < 0.0) targetN2 = 0.0;

        p.atmCO2Earth = AtmospherePhysics.pressureToMassEarth(p, targetCO2);
        p.atmO2Earth = AtmospherePhysics.pressureToMassEarth(p, targetO2);
        p.atmN2Earth = AtmospherePhysics.pressureToMassEarth(p, targetN2);
        // Keep existing water mass; recomputeDerived will cap it by saturation.
    }

    /**
     * Seeded: controlled CO2 regulation to hit target Tmin/Tmax window.
     * This approximates a multi-stage artificial seeding/terraforming program (~20–30 Myr ago).
     *
     * We adjust CO2 (and O2/N2 balance) iteratively and avoid overshoot iceball in most cases.
     */
    private static void applySeededTerraforming(StarModel star, PlanetModel p, DeterministicRng rng, double teqNoGH) {

        // If the world is truly absurd (super-hot), seeded can fail outright.
        if (teqNoGH > 650.0) return;

        // Compute a CO2 "floor" so the program doesn't accidentally iceball itself.
        // Colder by Teq -> higher floor.
        double cold01 = clamp01((PRIMORDIAL_TEQ_MIN_K - teqNoGH) / 80.0); // 0 near wet-belt, 1 for colder worlds
        double co2Floor = clamp(0.002 + 0.08 * cold01, 0.001, 0.20); // bar

        // If the planet already has huge CO2, floor is a fraction of current to avoid "eat it all".
        co2Floor = Math.max(co2Floor, 0.05 * Math.max(0.0, p.pCO2Bar));

        // Initial guess for CO2: keep current, but clamp to sane range.
        double co2 = clamp(p.pCO2Bar, co2Floor, 80.0);

        // Oxygen target: seeded includes complex life, but oxygen can be limited by sinks.
        // We'll set O2 within 0.05..0.35 bar, then sinks can reduce it later if you add that system.
        double o2Target = clamp(rng.logNormal(0.20, 0.35), 0.05, 0.35);

        // Total pressure target: seeded programs tend to create robust air, but not necessarily 1 bar.
        double totalTarget = clamp(rng.logNormal(1.2, 0.55), 0.6, 6.0);

        // Keep water vapor from current atmosphere; recomputeDerived will cap it.
        double h2o = clamp(p.pH2OBar, 0.0, 300.0);

        // Iteratively adjust CO2 to meet Tmin/Tmax targets.
        // We only push CO2 down if too hot, and allow it to rise if too cold.
        for (int it = 0; it < 10; it++) {
            // Apply current guess
            double n2 = Math.max(0.05, totalTarget - co2 - o2Target - h2o);
            if (n2 < 0.0) n2 = 0.0;

            p.atmCO2Earth = AtmospherePhysics.pressureToMassEarth(p, co2);
            p.atmO2Earth = AtmospherePhysics.pressureToMassEarth(p, o2Target);
            p.atmN2Earth = AtmospherePhysics.pressureToMassEarth(p, n2);
            // leave H2O mass as-is; recomputeDerived handles condensation caps.
            AtmospherePhysics.recomputeDerivedFromAtmosphere(star, p);

            double tMin = safeTemp(p.tMinK, p.tMeanK, teqNoGH);
            double tMax = safeTemp(p.tMaxK, p.tMeanK, teqNoGH);

            boolean okMin = (tMin >= SEEDED_TMIN_TARGET_K);
            boolean okMax = (tMax <= SEEDED_TMAX_TARGET_K);

            if (okMin && okMax) {
                // Success.
                return;
            }

            // Too hot -> reduce CO2 (never below floor)
            if (!okMax) {
                double hot01 = clamp01((tMax - SEEDED_TMAX_TARGET_K) / 80.0);
                double factor = 1.0 - (0.25 + 0.35 * hot01); // reduce 25..60%
                co2 = Math.max(co2Floor, co2 * clamp(factor, 0.40, 0.85));
                continue;
            }

            // Too cold -> increase CO2 (seeded can keep more CO2 to prevent freezing)
            if (!okMin) {
                double coldNeed01 = clamp01((SEEDED_TMIN_TARGET_K - tMin) / 70.0);
                double factor = 1.0 + (0.20 + 0.80 * coldNeed01); // increase 20..100%
                co2 = clamp(co2 * factor, co2Floor, 120.0);
            }
        }

        // If we didn't converge, apply a rare "overshoot iceball" failure mostly on colder worlds.
        // Otherwise keep the best-effort atmosphere (it will classify as microbial/subsurface or sterile).
        boolean veryColdBaseline = (teqNoGH < 215.0);
        if (veryColdBaseline && rng.chance(SEEDED_OVERSHOOT_ICEBALL_CHANCE)) {
            // Overshoot: CO2 dropped too far, planet refroze. Mark as seeded-dormant later by classification.
            // Force CO2 closer to floor and let climate decide.
            double co2Fail = Math.max(co2Floor, 0.008);
            double n2Fail = Math.max(0.05, totalTarget - co2Fail - o2Target - h2o);
            if (n2Fail < 0.0) n2Fail = 0.0;

            p.atmCO2Earth = AtmospherePhysics.pressureToMassEarth(p, co2Fail);
            p.atmO2Earth = AtmospherePhysics.pressureToMassEarth(p, o2Target);
            p.atmN2Earth = AtmospherePhysics.pressureToMassEarth(p, n2Fail);
        }
    }

    // -------------------- Post-bio climate classification --------------------

    private static void classifyByPostBioClimate(StarModel star, PlanetModel p, DeterministicRng rng) {
        double pressure = Math.max(0.0, p.pressureBar);
        boolean pressureOk = (pressure >= P_LIQUID_MIN_BAR && pressure <= P_LIQUID_MAX_BAR);

        double tMin = safeTemp(p.tMinK, p.tMeanK, p.teqK);
        double tMax = safeTemp(p.tMaxK, p.tMeanK, p.teqK);

        boolean liquidWindow = pressureOk && hasLiquidWaterWindowByMinMax(tMin, tMax);

        if (liquidWindow) {
            // All three types present
            p.biosphereSurface = BiosphereSurfaceStatus.COMPLEX;
            p.biosphereMicrobial = BiosphereMicrobialStatus.PRESENT;
            p.biosphereSubsurface = BiosphereSubsurfaceStatus.PRESENT;
            return;
        }

        // No surface liquid water window: decide survival mode by thresholds (as you specified).
        double tMinC = tMin - 273.15;
        double tMaxC = tMax - 273.15;

        // Hot sterilization thresholds
        if (tMinC > 95.0) {
            // Sterilized: no life remains.
            if (p.biosphereProvenance == BiosphereProvenance.SEEDED_RECENT) {
                p.biosphereProvenance = BiosphereProvenance.SEEDED_RECENT_DORMANT;
            } else if (p.biosphereProvenance == BiosphereProvenance.PRIMORDIAL) {
                p.biosphereProvenance = BiosphereProvenance.PRIMORDIAL_DORMANT;
            }
            p.biosphereSurface = BiosphereSurfaceStatus.NONE;
            p.biosphereMicrobial = BiosphereMicrobialStatus.NONE;
            p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
            return;
        }

        if (tMinC > 85.0) {
            // Hot but not fully sterilizing: microbes may survive.
            p.biosphereSurface = BiosphereSurfaceStatus.NONE;
            p.biosphereMicrobial = BiosphereMicrobialStatus.PRESENT;
            p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
            return;
        }

        // Cold worlds: if max < -5, allow microbes + subsurface (ice/ocean), then check deep cold.
        if (tMaxC < -5.0) {
            boolean deepCold = (tMaxC < -50.0);

            boolean heatingOk = false;
            if (!deepCold) {
                heatingOk = true; // not too deep, microbes/subsurface plausible
            } else {
                // Deep cold: require a heat source proxy.
                heatingOk = "STRONG".equals(p.tidalHeatingLevel) || "WEAK".equals(p.tidalHeatingLevel);
            }

            if (!heatingOk) {
                // Sterilized frozen rock
                if (p.biosphereProvenance == BiosphereProvenance.SEEDED_RECENT) {
                    p.biosphereProvenance = BiosphereProvenance.SEEDED_RECENT_DORMANT;
                } else if (p.biosphereProvenance == BiosphereProvenance.PRIMORDIAL) {
                    p.biosphereProvenance = BiosphereProvenance.PRIMORDIAL_DORMANT;
                }
                p.biosphereSurface = BiosphereSurfaceStatus.NONE;
                p.biosphereMicrobial = BiosphereMicrobialStatus.NONE;
                p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
            } else {
                p.biosphereSurface = BiosphereSurfaceStatus.NONE;
                p.biosphereMicrobial = BiosphereMicrobialStatus.PRESENT;
                p.biosphereSubsurface = BiosphereSubsurfaceStatus.PRESENT;
            }
            return;
        }

        // Otherwise: no liquid window but not extreme hot/cold -> microbial only (patchy niches)
        p.biosphereSurface = BiosphereSurfaceStatus.NONE;
        p.biosphereMicrobial = rng.chance(0.55) ? BiosphereMicrobialStatus.PRESENT : BiosphereMicrobialStatus.NONE;
        p.biosphereSubsurface = rng.chance(0.25) ? BiosphereSubsurfaceStatus.PRESENT : BiosphereSubsurfaceStatus.NONE;
    }

    // -------------------- Dead biosphere events (legacy atmosphere) --------------------

    private static void applyDeadBiosphereEvent(
            StarModel star,
            PlanetModel p,
            DeterministicRng rng,
            double preN2, double preO2, double preCO2, double preH2O,
            double preTotal
    ) {
        // Choose collapse severity:
        // - Most common: surface collapse, microbes+subsurface largely persist
        // - Rare: microbial collapse
        // - Very rare: total sterilization
        double roll = rng.nextDouble();

        if (roll < 0.70) {
            // Surface collapse
            p.biosphereSurface = BiosphereSurfaceStatus.DEAD;
            // Microbes often survive as relics; subsurface likely persists
            p.biosphereMicrobial = rng.chance(0.80) ? BiosphereMicrobialStatus.RELIC : BiosphereMicrobialStatus.PRESENT;
            p.biosphereSubsurface = (p.biosphereSubsurface == BiosphereSubsurfaceStatus.PRESENT || rng.chance(0.70))
                    ? BiosphereSubsurfaceStatus.PRESENT
                    : BiosphereSubsurfaceStatus.RELIC;

        } else if (roll < 0.95) {
            // Microbial collapse (surface already dead)
            p.biosphereSurface = BiosphereSurfaceStatus.DEAD;
            p.biosphereMicrobial = rng.chance(0.65) ? BiosphereMicrobialStatus.RELIC : BiosphereMicrobialStatus.NONE;
            // Subsurface harder to kill
            p.biosphereSubsurface = rng.chance(0.80) ? BiosphereSubsurfaceStatus.PRESENT : BiosphereSubsurfaceStatus.RELIC;

        } else {
            // Total sterilization
            p.biosphereSurface = BiosphereSurfaceStatus.NONE;
            p.biosphereMicrobial = BiosphereMicrobialStatus.NONE;
            p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
        }

        // Provenance becomes dormant if it had life.
        if (p.biosphereProvenance == BiosphereProvenance.SEEDED_RECENT) {
            p.biosphereProvenance = BiosphereProvenance.SEEDED_RECENT_DORMANT;
        } else if (p.biosphereProvenance == BiosphereProvenance.PRIMORDIAL) {
            p.biosphereProvenance = BiosphereProvenance.PRIMORDIAL_DORMANT;
        }

        // Partial legacy atmosphere:
        // We assume biosphere had time to change the air, but not necessarily fully.
        // Blend between pre-bio and current post-bio composition.
        double progress = clamp(rng.range(0.25, 0.75), 0.0, 1.0);

        // Current post-bio pressures (after transformations)
        double postN2 = p.pN2Bar;
        double postO2 = p.pO2Bar;
        double postCO2 = p.pCO2Bar;
        double postH2O = p.pH2OBar;

        double n2 = lerp(preN2, postN2, progress);
        double o2 = lerp(preO2, postO2, progress);
        double co2 = lerp(preCO2, postCO2, progress);
        double h2o = lerp(preH2O, postH2O, progress);

        // Keep a realistic-ish total pressure scale
        double total = clamp(lerp(preTotal, p.pressureBar, progress), 0.05, 20.0);

        // Renormalize N2 to fill remaining pressure (do not create negative)
        double fixed = Math.max(0.0, o2 + co2 + h2o);
        double n2Fill = Math.max(0.0, total - fixed);
        n2 = Math.max(n2, n2Fill);

        p.atmN2Earth = AtmospherePhysics.pressureToMassEarth(p, n2);
        p.atmO2Earth = AtmospherePhysics.pressureToMassEarth(p, o2);
        p.atmCO2Earth = AtmospherePhysics.pressureToMassEarth(p, co2);
        // Keep H2O mass derived from pressure; recomputeDerived will re-cap it.
        p.atmH2OEarth = AtmospherePhysics.pressureToMassEarth(p, h2o);

        // Attach an event if missing
        attachExtinctionEventIfMissing(star, p, rng);
    }

    private static void attachExtinctionEventIfMissing(StarModel star, PlanetModel p, DeterministicRng rng) {
        if (p.event != null && !p.event.isBlank()) return;

        String evt;
        // Coarse classification based on obvious stressor
        if (p.tMinK > 380.0) {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_RUNAWAY_GREENHOUSE;
            p.eventAgeMyr = rng.range(0.05, 50.0);
        } else if (p.pressureBar < 0.12 && p.orbitAroundStar != null && p.orbitAroundStar.aAU() < 0.25) {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_ATMOSPHERE_STRIPPING;
            p.eventAgeMyr = rng.range(0.01, 20.0);
        } else if ("STRONG".equals(p.tidalHeatingLevel) && rng.chance(0.55)) {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_SUPERVOLCANISM;
            p.eventAgeMyr = rng.range(0.05, 120.0);
        } else if (p.teqK < 215.0 || p.tMinK < 210.0) {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_SNOWBALL;
            p.eventAgeMyr = rng.range(0.2, 500.0);
        } else if (rng.chance(0.55)) {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_IMPACT_WINTER;
            p.eventAgeMyr = rng.range(0.001, 10.0);
        } else {
            evt = EventCatalog.EVT_BIOSPHERE_COLLAPSE_STELLAR_EVENT;
            p.eventAgeMyr = rng.range(0.01, 200.0);
        }

        p.event = evt;
    }

    // -------------------- Hydrocarbons (unchanged intent) --------------------

    private static void generateHydrocarbons(PlanetModel p, double teq, DeterministicRng rng) {
        p.heavyHydrocarbons = false;
        p.lightHydrocarbons = false;

        boolean bioRelevant = (p.biosphereSurface != BiosphereSurfaceStatus.NONE)
                || (p.biosphereMicrobial != BiosphereMicrobialStatus.NONE)
                || (p.biosphereSubsurface != BiosphereSubsurfaceStatus.NONE);
        if (!bioRelevant) return;

        double pressure = Math.max(0.0, p.pressureBar);
        double pressureFactor = clamp01((pressure - 0.1) / 5.0);
        double warmthFactor = bell01(teq, 250, 320);
        double coldFactor = bell01(teq, 90, 220);

        double tidal = "STRONG".equals(p.tidalHeatingLevel) ? 1.0 : ("WEAK".equals(p.tidalHeatingLevel) ? 0.4 : 0.0);

        double deadBoost = (p.biosphereSurface == BiosphereSurfaceStatus.DEAD) ? 0.25 : 0.0;
        double complexBoost = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX) ? 0.20 : 0.0;
        double relicBoost = (p.biosphereMicrobial == BiosphereMicrobialStatus.RELIC) ? 0.15 : 0.0;

        double heavyBias = (p.biosphereProvenance == BiosphereProvenance.PRIMORDIAL
                || p.biosphereProvenance == BiosphereProvenance.PRIMORDIAL_DORMANT) ? 0.25 : 0.0;
        double lightBias = (p.biosphereProvenance == BiosphereProvenance.SEEDED_RECENT
                || p.biosphereProvenance == BiosphereProvenance.SEEDED_RECENT_DORMANT) ? 0.25 : 0.0;

        double pHeavy = clamp01(
                0.05
                        + 0.45 * pressureFactor * warmthFactor
                        + 0.15 * tidal
                        + deadBoost + complexBoost + relicBoost
                        + heavyBias
        );

        double pLight = clamp01(
                0.08
                        + 0.50 * pressureFactor * coldFactor
                        + 0.10 * tidal
                        + deadBoost + relicBoost
                        + lightBias
        );

        p.heavyHydrocarbons = rng.chance(pHeavy);
        p.lightHydrocarbons = rng.chance(pLight);
    }

    // -------------------- Legacy shift for authored biospheres --------------------

    /**
     * Kept for backwards compatibility: if an authored world already has active life statuses,
     * allow a mild shift in gases similar to the previous implementation.
     */
    private static void applyBioAtmosphereShiftLegacy(StarModel star, PlanetModel p, DeterministicRng rng) {
        boolean active = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX)
                || (p.biosphereMicrobial == BiosphereMicrobialStatus.PRESENT);
        if (!active) return;

        double teq = AtmospherePhysics.equilibriumTemperatureK(star, p, 0.30);
        boolean wetBelt = (teq >= 220.0 && teq <= 310.0);
        if (!wetBelt) return;

        double total = clamp(p.pressureBar, 0.2, 5.0);

        double co2Floor = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX) ? 0.00005 : 0.0002; // bar
        double co2Ceil = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX) ? 0.01 : 0.05;      // bar
        double targetCO2 = clamp(rng.logNormal(0.001, 0.8), co2Floor, co2Ceil);

        double targetO2 = (p.biosphereSurface == BiosphereSurfaceStatus.COMPLEX)
                ? clamp(rng.logNormal(0.21, 0.35), 0.05, 0.35)
                : clamp(rng.logNormal(0.03, 0.6), 0.0, 0.12);

        double targetN2 = Math.max(0.05, total - targetCO2 - targetO2 - p.pH2OBar);
        if (targetN2 < 0.0) targetN2 = 0.0;

        p.atmCO2Earth = AtmospherePhysics.pressureToMassEarth(p, targetCO2);
        p.atmO2Earth = AtmospherePhysics.pressureToMassEarth(p, targetO2);
        p.atmN2Earth = AtmospherePhysics.pressureToMassEarth(p, targetN2);
    }

    // -------------------- Helpers --------------------

    private static void setSterile(PlanetModel p, BiosphereProvenance prov) {
        p.biosphereSurface = BiosphereSurfaceStatus.NONE;
        p.biosphereMicrobial = BiosphereMicrobialStatus.NONE;
        p.biosphereSubsurface = BiosphereSubsurfaceStatus.NONE;
        p.biosphereProvenance = (prov == null) ? BiosphereProvenance.ABIOGENIC : prov;
    }

    private static boolean hasLiquidWaterWindowByMinMax(double tMinK, double tMaxK) {
        if (!Double.isFinite(tMinK) || !Double.isFinite(tMaxK)) return false;
        // Window exists if min is below boiling and max is above freezing.
        return (tMinK < WATER_T_BOIL_K_AT_1BAR) && (tMaxK > WATER_T_FREEZE_K);
    }

    private static double safeTemp(double primary, double fallback, double fallback2) {
        if (Double.isFinite(primary) && primary > 0.0) return primary;
        if (Double.isFinite(fallback) && fallback > 0.0) return fallback;
        return fallback2;
    }

    private static WaterCoverage classifyWaterCoverage(double gelKm) {
        if (gelKm < 0.01) return WaterCoverage.DRY;
        if (gelKm < 0.10) return WaterCoverage.LAKES;
        if (gelKm < 0.50) return WaterCoverage.SEAS;
        if (gelKm < 1.50) return WaterCoverage.OCEAN;
        if (gelKm < 3.50) return WaterCoverage.OCEANS;
        if (gelKm < 8.00) return WaterCoverage.MANY_OCEANS;
        if (gelKm < 20.0) return WaterCoverage.ARCHIPELAGOS;
        return WaterCoverage.OCEAN_PLANET;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    /**
     * Simple bell-shaped 0..1 helper.
     * 1 at the center of [lo..hi], falls to 0 at the edges and beyond.
     */
    private static double bell01(double x, double lo, double hi) {
        if (!Double.isFinite(x)) return 0.0;
        double mid = (lo + hi) * 0.5;
        double half = (hi - lo) * 0.5;
        if (half <= 0.0) return 0.0;
        double t = Math.abs((x - mid) / half);
        return clamp01(1.0 - t);
    }
}
