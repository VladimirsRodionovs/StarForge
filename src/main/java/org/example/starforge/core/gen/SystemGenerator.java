package org.example.starforge.core.gen;

import org.example.starforge.core.model.*;
import org.example.starforge.core.random.DeterministicRng;
import org.example.starforge.core.physics.AtmospherePhysics;
import org.example.starforge.core.physics.AtmosphereEscapePhysics;
import org.example.starforge.core.physics.BiospherePhysics;
import org.example.starforge.core.physics.GasAccretionPhysics;
import org.example.starforge.core.physics.GiantStructurePhysics;
import org.example.starforge.core.gen.OuterRingGenerator;
import org.example.starforge.core.gen.MoonGenerator;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SystemGenerator {

    private final long seed;
    private final CompanionIndex companionIndex;

    public SystemGenerator(long seed) {
        this(seed, null);
    }

    public SystemGenerator(long seed, CompanionIndex companionIndex) {
        this.seed = seed;
        this.companionIndex = companionIndex;
    }

    public SystemModel generate(StarRecord rec) {
        long perStarSeed = mixSeed(seed, rec.id());
        DeterministicRng rng = new DeterministicRng(perStarSeed);

        StarModel star = new StarParamEstimator().estimate(rec);
        DiskModel disk = DiskGenerator.generate(star, rec, rng);
        double capAU = (companionIndex != null) ? companionIndex.capAU(rec) : Double.NaN;
        if (Double.isFinite(capAU) && capAU > 0.0) {
            double newSolidsOuter = Math.min(disk.rSolidsOuterAU, capAU);
            double newOuter = Math.min(disk.rOuterAU, capAU * 2.5);
            disk = disk.withOuterCaps(newSolidsOuter, newOuter);
        }

        SystemModel sys = new SystemModel(star);
        sys.diskTotalEarth = disk.mDiskEarth;
        sys.diskSolidsEarth = disk.mSolidsEarth;

        GenerationResult gen = generateSystem(star, disk, rng, capAU);
        sys.planets.addAll(gen.planets);
        sys.zones.addAll(gen.zones);
        sys.accretionStats = gen.accretionStats;

        return sys;
    }

    public static final class GenerationResult {
        public final List<PlanetModel> planets;
        public final List<ZoneModel> zones;
        public final AccretionSimulator.AccretionStats accretionStats;

        public GenerationResult(List<PlanetModel> planets, List<ZoneModel> zones, AccretionSimulator.AccretionStats stats) {
            this.planets = planets;
            this.zones = zones;
            this.accretionStats = stats;
        }
    }

    public GenerationResult generateSystem(StarModel star, DiskModel disk, DeterministicRng rng, double capAU) {
        DiskProfile profile = new DiskProfile(
                500.0,   // gas Σ0 (placeholder)
                10.0,    // solid Σ0 (placeholder)
                1.0,     // pGas
                1.1      // pSolid
        );

        EmbryoGenerator embryoGen = new EmbryoGenerator();
        List<EmbryoModel> embryos = embryoGen.generate(star, disk, profile, rng, false, capAU);

        double tGas = 3.5;

        // Accretion + stats
        var ar = AccretionSimulator.evolveSolidsWithStats(star, disk, embryos, rng, tGas);
        embryos = ar.embryos();
        SolidsDiskState solids = ar.solids();

        // Late-stage consolidation (inner system)
        embryos = LateStageConsolidation.consolidateInnerByCount(
                embryos, rng,
                1.0,   // innerAU
                2,     // minKeep
                5,     // maxKeep
                0.02   // impactLossFrac
        );

        // Cleanup the persistent tiny innermost artifact
        embryos = LateStageConsolidation.cleanupTinyInnermost(
                star, embryos, rng,
                0.12, // aCutCoeff: aCut = 0.12*sqrt(L)
                0.05, // massTiny
                0.90, // mergeProb
                0.02  // impactLossFrac
        );

        
        // --- Outer-stage accretion (separate pass) ---
        // Rationale: the current pipeline is tuned for the inner system. To grow ice worlds / neptunes / giant cores
        // without destabilizing the inner tuning, we run a second embryo+growth pass only beyond the snow line,
        // drawing from the remaining solids reservoir.
        {
            double snow = Math.max(disk.rInnerAU + 0.05, star.snowLineAU());
            double aSplit = Math.max(snow * 1.05, 1.2); // stable minimum beyond snow
            double outerSolids = solids.massIn(aSplit, disk.rSolidsOuterAU);

            if (outerSolids >= 0.20) {
                DiskModel outerDisk = disk.withSolidsSubset(
                        aSplit,
                        disk.rSolidsOuterAU,
                        outerSolids,
                        disk.kSpacing * 1.80
                );

                List<EmbryoModel> outerEmbryos = embryoGen.generate(star, outerDisk, profile, rng, true, capAU);

                // Pay seed masses from the same remaining reservoir so bookkeeping closes.
                AccretionSimulator.paySeedMass(solids, outerEmbryos);

                // Allow a longer effective growth time in the outer system.
                double tGasOuter = 10.5;

                outerEmbryos = AccretionSimulator.evolveSolidsFromState(star, outerDisk, outerEmbryos, rng, tGasOuter, solids);

                // Consolidate within a broad outer band, but allow the kept-count to scale with available outer solids.
// This produces more diverse outer architectures (0..9 bodies) instead of a near-fixed 1–3.
                int minKeepOuter;
                int maxKeepOuter;
                if (outerSolids < 0.70) {
                    // Often just a belt / one dwarf.
                    minKeepOuter = rng.chance(0.40) ? 0 : 1;
                    maxKeepOuter = rng.chance(0.25) ? 2 : 1;
                } else if (outerSolids < 3.0) {
                    minKeepOuter = 1;
                    maxKeepOuter = 2;
                } else if (outerSolids < 10.0) {
                    minKeepOuter = 1;
                    maxKeepOuter = rng.chance(0.40) ? 3 : 2;
                } else if (outerSolids < 25.0) {
                    minKeepOuter = 2;
                    maxKeepOuter = rng.rangeInt(3, 4);
                } else if (outerSolids < 60.0) {
                    minKeepOuter = 2;
                    maxKeepOuter = rng.rangeInt(4, 6);
                } else if (outerSolids < 120.0) {
                    minKeepOuter = 3;
                    maxKeepOuter = rng.rangeInt(5, 7);
                } else {
                    minKeepOuter = 4;
                    maxKeepOuter = rng.rangeInt(6, 9);
                }
                if (rng.chance(0.15)) maxKeepOuter += 1; // rare extra survivor

                outerEmbryos = LateStageConsolidation.consolidateRangeByCount(
                        outerEmbryos, rng,
                        aSplit,
                        disk.rSolidsOuterAU,
                        minKeepOuter,
                        maxKeepOuter,
                        0.02
                );

                // Merge back into the main embryo list.
                embryos.addAll(outerEmbryos);
            }
        }

        
        // --- Long-term stability pass (pre-gas): merge bodies that are unrealistically close in mutual Hill radii.
        // This both fixes near-overlapping orbits and helps a few dominant cores emerge (supporting gas-giant formation).
        // Use a slightly stricter spacing pass to avoid "near-duplicate" orbits in the final output.
        embryos = LateStageConsolidation.enforceMutualHillSpacing(embryos, star, rng, 12.0, 0.01, false);

// --- Gas accretion pass (H/He envelopes from disk gas budget) ---
        // This should happen after solid growth and consolidation, so only sufficiently large cores can accrete gas.
        GasAccretionPhysics.apply(star, disk, embryos, rng);

        // Post-gas stability merge: keep spacing clean after envelopes are assigned.
        embryos = LateStageConsolidation.enforceMutualHillSpacing(embryos, star, rng, 10.0, 0.005, true);

// Step #1: outer pruning into zones (convert leftover small embryos into belts/disks)
        var pr = SmallBodyPostProcessor.process(star, disk, embryos, rng);
        embryos = pr.survivors();

        // Step #2: orbital clearing + sweep from the remaining solids reservoir.
        //  - inside: clear ~95% (clean inner system)
        //  - outside: clear 40..70% (leave belts/disks and variety)
        applyClearingAndSweep(star, disk, embryos, solids, rng);

        // Step #2b: long-term "vacuum" of inner residual solids.
        // Rationale (game-friendly): the region inside the inner system (and between its planets)
        // tends to be cleared over long timescales; most of that mass ends up accreted by the
        // neighboring planets or lost onto the star, rather than persisting as a massive inner belt.
        vacuumInnerIntervals(star, disk, embryos, solids, rng);

        // Step #3: convert remaining solids reservoir into zones (variant 2: leave belts/disks)
        List<ZoneModel> zones = new ArrayList<>(pr.zones());
        addResidualSolidsZones(star, disk, solids, rng, zones);

        // Build PlanetModel list
        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        List<PlanetModel> planets = new ArrayList<>();
        int idx = 1;

        for (EmbryoModel e : embryos) {
            if (e.massEarth < 0.01) continue;

            PlanetModel p = PlanetModel.fromEmbryo(star, e, rng);
            p.name = "P" + (idx++);
            planets.add(p);
        }

        // Assign eccentricities AFTER we have the full planet list, enforcing non-overlapping orbits.
        // This prevents "two planets almost on the same orbit" artifacts caused by per-planet random e.
        org.example.starforge.core.physics.OrbitStabilityPhysics.assignEccentricitiesAndAngles(star, planets, rng);

// --- Step #4: heuristic far-outer ring (small rocky/icy bodies) + optional belt record ---
        OuterRingGenerator.Result outer = OuterRingGenerator.generate(star, disk, null, planets, rng);

// Переименуем новые тела в продолжение P-серии, чтобы не было O1/O2 вперемешку с P-ами
        for (PlanetModel op : outer.planets) {
            op.name = "P" + (idx++);
            planets.add(op);
        }

// Пояс пока "строкой". В проекте сейчас нет поля под записи,
// поэтому самый безопасный вариант — добавить как ZoneModel (см. ниже),
// либо хотя бы вывести в лог.
        if (outer.beltRecord != null) {
            System.out.println(outer.beltRecord);
        }



        // Now run heavy per-planet physics passes.
        for (PlanetModel p : planets) {
            if (p.envelopeMassEarth > 0.02) {
                GiantStructurePhysics.computeForPlanet(star, p);
            } else {
                AtmospherePhysics.computeForPlanet(star, p, rng);
                AtmosphereEscapePhysics.apply(star, p);
                AtmospherePhysics.recomputeDerivedFromAtmosphere(star, p);
                BiospherePhysics.applyForPlanet(star, p, rng);
                AtmospherePhysics.recomputeDerivedFromAtmosphere(star, p);
            }
        }


        // --- Moons pass (after planets are fully built) ---
        MoonGenerator moonGen = new MoonGenerator();
        for (PlanetModel p : planets) {
            moonGen.generateMoons(star, p, rng);
        }


        if (Double.isFinite(capAU) && capAU > 0.0) {
            planets.removeIf(p -> p == null || p.orbitAroundStar == null || p.orbitAroundStar.aAU() > capAU);
            for (int i = 0; i < zones.size(); i++) {
                ZoneModel z = zones.get(i);
                if (z == null) {
                    zones.remove(i--);
                    continue;
                }
                if (z.aMinAU() > capAU) {
                    zones.remove(i--);
                    continue;
                }
                if (z.aMaxAU() > capAU) {
                    zones.set(i, new ZoneModel(
                            z.type(),
                            z.aMinAU(),
                            capAU,
                            z.massEarth(),
                            z.composition(),
                            z.population(),
                            z.event(),
                            z.eventAgeMyr()
                    ));
                }
            }
        }

        return new GenerationResult(planets, zones, ar.stats());
    }

    // -------------------- residual solids -> zones + inner clearing --------------------

    /**
     * After late-stage consolidation, let the remaining inner bodies "sweep" part of the leftover solids reservoir.
     *
     * Design goal:
     *  - inner system becomes relatively clean and planets grow more naturally,
     *  - but we still keep variant-2 belts/disks (we do NOT eat everything).
     */
    private static void applyClearingAndSweep(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            SolidsDiskState solids,
            DeterministicRng rng
    ) {
        if (embryos == null || embryos.isEmpty()) return;
        if (solids == null) return;

        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        double snow = Math.max(0.01, star.snowLineAU());

        // "Inner" boundary for very strong clearing. We keep a floor for late M dwarfs.
        double innerStrongClearAU = clamp(0.70 * snow, 0.35, 1.20);

        for (EmbryoModel e : embryos) {
            double a = e.aAU;
            if (a <= 0) continue;

            double hill = org.example.starforge.core.physics.HillSphere.hillRadiusAU(
                    a,
                    Math.max(1e-9, e.massEarth),
                    star.massSolar()
            );

            // Clearing width: wide enough to open a noticeable gap (prevents belts overlapping planets).
            // We do this in TWO layers:
            //  1) a deep core gap right at the orbit (always ~95% cleared)
            //  2) a broader, shallower clearing halo (inside: ~95%, outside: 40..70%)

            // 1) Core gap (deep)
            double daCore = Math.max(4.0 * hill, 0.012 * a);
            daCore = Math.max(daCore, 0.006); // AU floor

            double c1 = Math.max(disk.rInnerAU, a - daCore);
            double c2 = Math.min(disk.rSolidsOuterAU, a + daCore);
            if (c2 > c1) {
                double coreAvail = solids.massIn(c1, c2);
                if (coreAvail > 0) {
                    double coreRemoved = solids.removeMass(c1, c2, coreAvail * 0.95);
                    if (coreRemoved > 0) {
                        double coreAcc = (a <= innerStrongClearAU) ? rng.range(0.70, 0.90) : rng.range(0.25, 0.45);
                        double loss = clamp(0.01 + 0.02 * rng.nextDouble(), 0.0, 0.08);
                        e.massEarth += (coreRemoved * coreAcc) * (1.0 - loss);
                    }
                }
            }

            // 2) Clearing halo (shallower)
            double k = (a <= innerStrongClearAU) ? 18.0 : 10.0;
            double da = Math.max(k * hill, 0.035 * a);
            da = Math.max(da, 0.010); // AU floor

            double r1 = Math.max(disk.rInnerAU, a - da);
            double r2 = Math.min(disk.rSolidsOuterAU, a + da);
            if (r2 <= r1) continue;

            double available = solids.massIn(r1, r2);
            if (available <= 0) continue;

            // Clearing fraction policy (your spec):
            //  - ~95% inside
            //  - 40..70% outside
            double clearFrac = (a <= innerStrongClearAU)
                    ? 0.95
                    : rng.range(0.40, 0.70);

            // Slight mass dependence: big bodies clear more efficiently outside, too.
            if (a > innerStrongClearAU) {
                if (e.massEarth >= 2.0) clearFrac = Math.min(0.80, clearFrac + 0.10);
                else if (e.massEarth >= 0.5) clearFrac = Math.min(0.78, clearFrac + 0.06);
                else if (e.massEarth >= 0.2) clearFrac = Math.min(0.74, clearFrac + 0.03);
            }

            double targetRemove = available * clearFrac;
            double removed = solids.removeMass(r1, r2, targetRemove);
            if (removed <= 0) continue;

            // Only a fraction of the cleared mass is actually accreted; the rest is scattered/collisional debris.
            double accreteFrac = (a <= innerStrongClearAU)
                    ? rng.range(0.70, 0.90)
                    : rng.range(0.30, 0.55);

            double gained = removed * accreteFrac;

            // Erosion/impact loss on the gained part.
            double loss = clamp(0.01 + 0.02 * rng.nextDouble(), 0.0, 0.08);
            e.massEarth += gained * (1.0 - loss);
        }
    }

    /**
     * Convert the remaining solids reservoir into belts/disks.
     *
     * This is critical for mass accounting and for "variant 2" realism: leftover solids are not invisible.
     */
    private static void addResidualSolidsZones(
            StarModel star,
            DiskModel disk,
            SolidsDiskState solids,
            DeterministicRng rng,
            List<ZoneModel> outZones
    ) {
        if (solids == null) return;

        double snow = Math.max(0.01, star.snowLineAU());

        // Same belt bounds as the small-body post-processor (clamped for tiny snow lines).
        double aAstMin = Math.max(0.10, 0.65 * snow);
        double aAstMax = Math.max(0.20, 1.40 * snow);

        double aOuterMin = aAstMax;
        double aOuterMax = Math.max(aOuterMin + 0.2, disk.rSolidsOuterAU);

        // Instead of creating one huge belt, scan the residual solids bins and create zones from contiguous segments.
        // We also split segments at the snow-line belt boundaries so OUTER_DISK doesn't start inside the belt.

        double minZoneMass = 0.02;
        double minBinMass = 1e-4; // prevents thousands of micro-zones

        int n = solids.bins();
        int i = 0;
        while (i < n) {
            // Skip tiny bins
            while (i < n && solids.binMassEarth(i) < minBinMass) i++;
            if (i >= n) break;

            int start = i;
            double mass = 0.0;

            // Lock the region classification for this segment (belt vs outer). Anything inside aAstMin
            // should have been vacuumed already; we simply skip it if it still exists.
            double startMid = 0.5 * (solids.binInnerAU(i) + solids.binOuterAU(i));
            int region = regionOf(startMid, aAstMin, aOuterMin); // 0=inner,1=belt,2=outer

            while (i < n && solids.binMassEarth(i) >= minBinMass) {
                double mid = 0.5 * (solids.binInnerAU(i) + solids.binOuterAU(i));
                int r = regionOf(mid, aAstMin, aOuterMin);
                if (r != region) break;
                mass += solids.binMassEarth(i);
                i++;
            }
            int end = i - 1;

            if (mass < minZoneMass) continue;

            double segMin = solids.binInnerAU(start);
            double segMax = solids.binOuterAU(end);
            double mid = 0.5 * (segMin + segMax);

            // Ignore inner residuals (should be nearly empty after vacuumInnerIntervals).
            if (region == 0) continue;

            ZoneType type = (region == 2) ? ZoneType.OUTER_DISK : ZoneType.ASTEROID_BELT;

            mergeOrAddZone(outZones, new ZoneModel(
                    type,
                    segMin, segMax,
                    mass,
                    SmallBodyPostProcessor.compositionFor(star, mid),
                    SmallBodyPostProcessor.populationFromMass(mass, rng),
                    null, 0.0
            ));
        }
    }

    /**
     * Clears residual solids inside the "inner system" boundary by funneling it into nearby planets (or onto the star).
     *
     * We apply this after late-stage consolidation + gap carving. It prevents massive inner belts from appearing.
     */
    private static void vacuumInnerIntervals(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            SolidsDiskState solids,
            DeterministicRng rng
    ) {
        if (solids == null || embryos == null || embryos.isEmpty()) return;

        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        double snow = Math.max(0.01, star.snowLineAU());
        double innerBoundaryAU = clamp(0.70 * snow, 0.35, 1.20);

        // Collect inner-system bodies
        List<EmbryoModel> inner = new ArrayList<>();
        for (EmbryoModel e : embryos) {
            if (e.aAU > 0 && e.aAU <= innerBoundaryAU) inner.add(e);
        }
        if (inner.isEmpty()) return;

        // Helper for a local gap width around a body (must be consistent with clearing/sweep scales)
        java.util.function.Function<EmbryoModel, Double> gapAU = (EmbryoModel e) -> {
            double a = e.aAU;
            double hill = org.example.starforge.core.physics.HillSphere.hillRadiusAU(
                    a,
                    Math.max(1e-9, e.massEarth),
                    star.massSolar()
            );
            double g = Math.max(6.0 * hill, 0.020 * a);
            return Math.max(g, 0.008);
        };

        // Vacuum policy: move almost all residual inner solids into planets; a small remainder is lost onto the star.
        final double accreteFrac = 0.95; // your spec for "inside"
        final double maxLoss = 0.06;

        // Interval inside the first inner body
        EmbryoModel first = inner.get(0);
        double left = disk.rInnerAU;
        double right = Math.max(disk.rInnerAU, first.aAU - gapAU.apply(first));
        vacuumIntervalToOne(solids, left, right, first, accreteFrac, maxLoss, rng);

        // Intervals between inner bodies
        for (int k = 0; k < inner.size() - 1; k++) {
            EmbryoModel a = inner.get(k);
            EmbryoModel b = inner.get(k + 1);

            double l = Math.min(disk.rSolidsOuterAU, a.aAU + gapAU.apply(a));
            double r = Math.max(disk.rInnerAU, b.aAU - gapAU.apply(b));
            if (r <= l) continue;

            double available = solids.massIn(l, r);
            if (available <= 0) continue;

            // Remove everything from the interval (we don't want persistent massive inner belts).
            double removedAll = solids.removeMass(l, r, available);
            if (removedAll <= 0) continue;

            // Of what was removed, accreteFrac ends up on planets; the rest is lost.
            double toPlanets = removedAll * accreteFrac;
            double loss = clamp(rng.range(0.0, maxLoss), 0.0, maxLoss);
            toPlanets *= (1.0 - loss);

            // Split between neighbors (mass-weighted).
            double wa = Math.max(1e-6, a.massEarth);
            double wb = Math.max(1e-6, b.massEarth);
            double sum = wa + wb;
            double fa = wa / sum;
            double fb = 1.0 - fa;

            // Inner residuals are mostly dry/refractory. Use local composition heuristic.
            double mid = 0.5 * (a.aAU + b.aAU);
            double[] comp = solidsCompositionAt(star, mid);

            double da = toPlanets * fa;
            double db = toPlanets * fb;
            org.example.starforge.core.model.CompositionUtil.mixAddSolids(a, da, comp[0], comp[1], comp[2]);
            org.example.starforge.core.model.CompositionUtil.mixAddSolids(b, db, comp[0], comp[1], comp[2]);
            a.massEarth += da;
            b.massEarth += db;
        }
    }

    private static void vacuumIntervalToOne(
            SolidsDiskState solids,
            double l,
            double r,
            EmbryoModel target,
            double accreteFrac,
            double maxLoss,
            DeterministicRng rng
    ) {
        if (r <= l) return;
        double available = solids.massIn(l, r);
        if (available <= 0) return;

        double removedAll = solids.removeMass(l, r, available);
        if (removedAll <= 0) return;

        double gain = removedAll * accreteFrac;
        double loss = clamp(rng.range(0.0, maxLoss), 0.0, maxLoss);
        gain *= (1.0 - loss);

        // Because this is always inside the inner boundary, we treat it as dry.
        org.example.starforge.core.model.CompositionUtil.mixAddSolids(target, gain, 0.30, 0.70, 0.0);
        target.massEarth += gain;
    }

    /** Returns {fracIron, fracRock, fracIce} for newly accreted solids at aAU. */
    private static double[] solidsCompositionAt(StarModel star, double aAU) {
        double snow = 1.0;
        if (star != null) snow = Math.max(1e-4, star.snowLineAU());
        double in = 0.85 * snow;
        double out = 1.15 * snow;
        double ice;
        if (aAU <= in) ice = 0.0;
        else if (aAU >= out) ice = 1.0;
        else ice = (aAU - in) / (out - in);

        double feShare = 0.30 + (0.22 - 0.30) * ice;
        double refr = Math.max(0.0, 1.0 - ice);
        double fe = refr * feShare;
        double rock = refr * (1.0 - feShare);
        return new double[]{fe, rock, ice};
    }

    private static int regionOf(double aAU, double aAstMin, double aOuterMin) {
        if (aAU < aAstMin) return 0;
        if (aAU < aOuterMin) return 1;
        return 2;
    }

    /**
     * Merge zones of the same type if they overlap substantially; otherwise append.
     * Prevents duplicated ASTEROID_BELT/OUTER_DISK lines when zones come from both embryos and leftover solids.
     */
    private static void mergeOrAddZone(List<ZoneModel> zones, ZoneModel add) {
        for (int i = 0; i < zones.size(); i++) {
            ZoneModel z = zones.get(i);
            if (z.type() != add.type()) continue;

            double lo = Math.max(z.aMinAU(), add.aMinAU());
            double hi = Math.min(z.aMaxAU(), add.aMaxAU());
            double overlap = Math.max(0.0, hi - lo);

            double span = Math.max(z.aMaxAU(), add.aMaxAU()) - Math.min(z.aMinAU(), add.aMinAU());
            double overlapFrac = (span > 0) ? (overlap / span) : 0.0;

            // If overlaps at least 40%, treat as the same belt and merge.
            if (overlapFrac >= 0.40) {
                double newMin = Math.min(z.aMinAU(), add.aMinAU());
                double newMax = Math.max(z.aMaxAU(), add.aMaxAU());
                double newMass = Math.max(0.0, z.massEarth()) + Math.max(0.0, add.massEarth());

                zones.set(i, new ZoneModel(
                        z.type(),
                        newMin, newMax,
                        newMass,
                        z.composition(), // keep existing composition
                        z.population(),  // keep existing population
                        z.event(),
                        z.eventAgeMyr()
                ));
                return;
            }
        }
        zones.add(add);
    }

    // -------------------- helpers --------------------

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long mixSeed(long a, long b) {
        long x = a ^ (b + 0x9E3779B97F4A7C15L);
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
