package org.example.starforge.core.gen;

import org.example.starforge.core.model.*;
import org.example.starforge.core.physics.AtmosphereEscapePhysics;
import org.example.starforge.core.physics.AtmospherePhysics;
import org.example.starforge.core.physics.HillSphere;
import org.example.starforge.core.physics.PlanetRadii;
import org.example.starforge.core.physics.Units;
import org.example.starforge.core.random.DeterministicRng;
import org.example.starforge.core.physics.BiospherePhysics;

import java.util.*;

/**
 * Generates moons for a planet and runs the SAME heavy physics pipeline as for planets:
 * AtmospherePhysics -> AtmosphereEscapePhysics -> BiospherePhysics (then recompute).
 *
 * Design:
 * - Regular moons: common for giants, limited by a conservative mass budget.
 * - Captured/impact moons: rare, can be large (including extremely rare "Earth-mass-class" cases).
 * - Orbits: stable prograde zone 0.35 * Hill; inner floor based on planet radius.
 * - Spacing: log/a-ratio constraints between moons.
 * - Tidal heating: heuristic based on (e, aMoon, Mp).
 *
 * Output:
 * - PlanetModel.moons gets populated with MoonModel records.
 */
public final class MoonGenerator {

    /** Generate moons for a single planet (appends into planet.moons). */
    public void generateMoons(StarModel star, PlanetModel host, DeterministicRng rng) {
        if (star == null || host == null || rng == null) return;
        if (host.orbitAroundStar == null) return;

        // Skip trivially small hosts
        if (host.massEarth <= 0.05) return;

        // Hill sphere and stable satellite zone
        double rHillAU = HillSphere.hillRadiusAU(
                host.orbitAroundStar.aAU(),
                host.massEarth,
                star.massSolar()
        );
        double aMoonMax = 0.35 * rHillAU;
        if (!(aMoonMax > 0)) return;

        // Inner orbit floor: ~planet radius in AU with a small minimum
        double aMoonMin = Math.max(
                (Math.max(0.3, host.radiusEarth) * Units.R_EARTH_KM) / Units.KM_PER_AU,
                0.00003
        );

        // If no room, skip
        if (aMoonMax < aMoonMin * 2.0) return;

        HostKind kind = classifyHost(host);

        // Decide counts
        int majorCount;
        int minorCount;

        switch (kind) {
            case GAS_GIANT -> {
                majorCount = rng.rangeInt(2, 6);
                minorCount = rng.rangeInt(4, 18);
            }
            case ICE_GIANT -> {
                majorCount = rng.rangeInt(1, 4);
                minorCount = rng.rangeInt(2, 12);
            }
            case SUB_NEPTUNE -> {
                majorCount = rng.rangeInt(0, 2);
                minorCount = rng.rangeInt(0, 6);
            }
            default -> {
                // Rocky / super-earth
                majorCount = rng.chance(0.40) ? 1 : 0;
                minorCount = rng.chance(0.25) ? rng.rangeInt(1, 3) : 0;
            }
        }

        // Regular moons budget (Earth masses), with gentle tuning knobs.
        // For gas giants, typical total satellite mass is small fraction; we allow a bit more for game feel.
        double regularBudget = 1e-4 * host.massEarth;
        if (kind == HostKind.GAS_GIANT) regularBudget *= rng.range(1.2, 3.0);
        if (kind == HostKind.ICE_GIANT) regularBudget *= rng.range(1.0, 2.2);

        // Special moon channel: capture/impact
        boolean allowSpecial =
                (kind == HostKind.GAS_GIANT || kind == HostKind.ICE_GIANT) ? rng.chance(0.18)
                        : (kind == HostKind.ROCKY ? rng.chance(0.10) : rng.chance(0.08));

        boolean allowHugeSpecial = allowSpecial && rng.chance(0.08);     // very rare
        boolean allowExtremeSpecial = allowHugeSpecial && rng.chance(0.03); // extremely rare: can approach Earth-mass

        List<MoonSpec> specs = new ArrayList<>();

        // One special moon at most (prevents circus)
        if (allowSpecial) {
            MoonOrigin origin = (kind == HostKind.ROCKY) ? MoonOrigin.IMPACT : MoonOrigin.CAPTURED;

            double m;
            if (allowExtremeSpecial) {
                // Up to ~1 Earth mass, but strongly capped by host mass (binary-like outcomes are fine).
                m = rng.range(0.25, 1.00);
                m = Math.min(m, host.massEarth * 0.05); // <= 5% of planet mass
            } else if (allowHugeSpecial) {
                m = rng.range(0.10, 0.45);
                m = Math.min(m, host.massEarth * 0.03);
            } else {
                m = rng.range(0.03, 0.18);
                m = Math.min(m, host.massEarth * 0.02);
            }

            // Ensure not trivial
            if (m > 0.01) {
                specs.add(new MoonSpec(origin, m));
                majorCount = Math.max(0, majorCount - 1);
            }
        }

        // Regular major moons (consume budget)
        for (int i = 0; i < majorCount; i++) {
            if (regularBudget <= 0) break;

            double m = rng.range(0.001, 0.03);
            m = Math.min(m, regularBudget * rng.range(0.35, 0.75));
            if (m < 1e-4) break;

            regularBudget -= m;
            specs.add(new MoonSpec(MoonOrigin.REGULAR, m));
        }

        // Minor moons (tiny)
        for (int i = 0; i < minorCount; i++) {
            double m = rng.range(1e-7, 5e-5);
            specs.add(new MoonSpec(MoonOrigin.MINOR, m));
        }

        // Largest first => best orbits
        specs.sort(Comparator.comparingDouble((MoonSpec s) -> s.massEarth).reversed());

        List<Orbit> assigned = new ArrayList<>();
        int moonIdx = 1;

        for (MoonSpec spec : specs) {
            double aMoon = pickMoonSemiMajorAU(rng, aMoonMin, aMoonMax, assigned);
            if (!Double.isFinite(aMoon)) continue;

            double eMoon;
            double iMoon;

            switch (spec.origin) {
                case CAPTURED -> {
                    eMoon = rng.range(0.05, 0.30);
                    if (rng.chance(0.60)) eMoon *= rng.range(0.30, 0.70); // partial circularization
                    iMoon = rng.range(0.0, 25.0);
                }
                case IMPACT -> {
                    eMoon = rng.range(0.0, 0.08);
                    iMoon = rng.range(0.0, 8.0);
                }
                case REGULAR -> {
                    eMoon = rng.range(0.0, 0.05);
                    iMoon = rng.range(0.0, 2.0);
                }
                default -> {
                    eMoon = rng.range(0.0, 0.20);
                    iMoon = rng.range(0.0, 30.0);
                }
            }

            Orbit orbit = new Orbit(
                    aMoon,
                    eMoon,
                    iMoon,
                    rng.range(0.0, 360.0),
                    rng.range(0.0, 360.0),
                    rng.range(0.0, 360.0)
            );
            assigned.add(orbit);

            String name = host.name + "-M" + (moonIdx++);
            MoonModel moon = buildMoonViaPlanetPipeline(star, host, name, spec, orbit, rng);
            if (moon != null) {
                host.moons.add(moon);
            }
        }
    }

    // -------------------- Build MoonModel using real planet pipeline --------------------

    private MoonModel buildMoonViaPlanetPipeline(
            StarModel star,
            PlanetModel host,
            String moonName,
            MoonSpec spec,
            Orbit orbitAroundPlanet,
            DeterministicRng rng
    ) {
        double m = spec.massEarth;

        // Choose bulk composition for the moon
        Composition comp = pickMoonComposition(host, spec, rng);

        // Create a temporary PlanetModel for running existing physics
        PlanetModel pm = new PlanetModel(moonName);
        pm.massEarth = m;
        pm.coreMassEarth = m;
        pm.envelopeMassEarth = 0.0;
        pm.envelopeZ = 0.0;

        pm.fracIron = comp.fracIron;
        pm.fracRock = comp.fracRock;
        pm.fracIce = comp.fracIce;

        // Water proxy for AtmospherePhysics inventories
        pm.waterMassFrac = clamp01(0.05 + 0.75 * pm.fracIce);

        // Insolation: use host planet's star-orbit as proxy
        pm.orbitAroundStar = host.orbitAroundStar;

        // Radius + gravity (solid relation)
        pm.radiusEarth = PlanetRadii.solidRadiusEarth(pm.massEarth, pm.fracIron, pm.fracRock, pm.fracIce);
        pm.surfaceG = pm.massEarth / Math.max(1e-9, (pm.radiusEarth * pm.radiusEarth));

        // Moon isn't star-locked; AtmospherePhysics would set this for close-in bodies, so we override later.
        pm.tidallyLockedToStar = false;

        // Tidal heating level for deep-cold biosphere rules
        pm.tidalHeatingLevel = tidalHeatingLevel(host, orbitAroundPlanet);

        // Type label (UI only)
        pm.type = classifyMoonType(pm.massEarth, pm.fracIce, spec.origin);

        // --- Run the actual pipeline ---
        AtmospherePhysics.computeForPlanet(star, pm, rng);

        // Override: moons should not be star-locked in our model, then recompute climate with that assumption
        pm.tidallyLockedToStar = false;
        AtmospherePhysics.recomputeDerivedFromAtmosphere(star, pm);

        AtmosphereEscapePhysics.apply(star, pm);
        AtmospherePhysics.recomputeDerivedFromAtmosphere(star, pm);

        BiospherePhysics.applyForPlanet(star, pm, rng);
        AtmospherePhysics.recomputeDerivedFromAtmosphere(star, pm);

        // --- FIX: ensure moon radius/gravity are always computed (some passes may leave radius unset) ---
        pm.radiusEarth = PlanetRadii.solidRadiusEarth(pm.massEarth, pm.fracIron, pm.fracRock, pm.fracIce);
        pm.surfaceG = pm.massEarth / Math.max(1e-9, (pm.radiusEarth * pm.radiusEarth));


        // Locking to planet (expected for most "real" moons)
        boolean lockedToPlanet = (pm.massEarth >= 1e-5);

        // Convert to MoonModel
        Map<String, Double> atm = new LinkedHashMap<>(pm.atmosphere);

        return new MoonModel(
                moonName,
                pm.massEarth,
                pm.radiusEarth,
                pm.surfaceG,
                orbitAroundPlanet,
                lockedToPlanet,
                pm.tidalHeatingLevel,
                pm.habitableRegionPresent,
                pm.pressureBar,
                pm.atmosphereType,
                pm.envelopeMassEarth,
                pm.type,
                pm.basePressureBar,
                pm.fracIron,
                pm.fracRock,
                pm.fracIce,
                pm.waterMassFrac,
                pm.teqK,
                pm.greenhouseDeltaK,
                pm.tMeanK,
                pm.tMinK,
                pm.tMaxK,
                pm.waterCoverage,
                pm.waterGELkm,
                atm,
                pm.biosphereSurface,
                pm.biosphereMicrobial,
                pm.biosphereSubsurface,
                pm.biosphereProvenance,
                pm.heavyHydrocarbons,
                pm.lightHydrocarbons,
                pm.event,
                pm.eventAgeMyr
        );
    }

    // -------------------- Host classification --------------------

    private enum HostKind { GAS_GIANT, ICE_GIANT, SUB_NEPTUNE, ROCKY }

    private HostKind classifyHost(PlanetModel p) {
        String t = (p.type == null) ? "" : p.type.trim().toLowerCase(Locale.ROOT);

        if (t.contains("gas giant")) return HostKind.GAS_GIANT;
        if (t.contains("ice giant")) return HostKind.ICE_GIANT;
        if (t.contains("sub-neptune") || t.contains("sub neptune") || t.contains("neptune")) return HostKind.SUB_NEPTUNE;

        // fallback by mass/envelope (robust to naming)
        double envFrac = (p.massEarth > 0) ? (Math.max(0.0, p.envelopeMassEarth) / p.massEarth) : 0.0;
        if (p.massEarth >= 30.0 && envFrac >= 0.35) return HostKind.GAS_GIANT;
        if (p.massEarth >= 10.0 && envFrac >= 0.10) return HostKind.ICE_GIANT;

        return HostKind.ROCKY;
    }

    // -------------------- Moon composition / type --------------------

    private static final class Composition {
        final double fracIron, fracRock, fracIce;
        Composition(double fracIron, double fracRock, double fracIce) {
            this.fracIron = fracIron;
            this.fracRock = fracRock;
            this.fracIce = fracIce;
        }
    }

    private Composition pickMoonComposition(PlanetModel host, MoonSpec spec, DeterministicRng rng) {
        // Heuristic:
        // - Regular moons around giants: mostly icy/rocky
        // - Impact moons around rocky planets: similar to host, slightly depleted in volatiles
        // - Captured moons: vary widely; in outer systems more icy
        HostKind kind = classifyHost(host);

        if (spec.origin == MoonOrigin.IMPACT) {
            // Similar to host, but less ice on average
            double ice = clamp(host.fracIce * rng.range(0.30, 0.80), 0.00, 0.35);
            double iron = clamp(host.fracIron * rng.range(0.85, 1.15), 0.10, 0.45);
            double rock = Math.max(0.0, 1.0 - ice - iron);
            return norm(iron, rock, ice);
        }

        if (kind == HostKind.GAS_GIANT || kind == HostKind.ICE_GIANT) {
            double ice = rng.range(0.35, 0.80);
            if (spec.origin == MoonOrigin.REGULAR) ice = rng.range(0.45, 0.85);
            if (spec.origin == MoonOrigin.MINOR) ice = rng.range(0.10, 0.70);

            double iron = (1.0 - ice) * rng.range(0.12, 0.30);
            double rock = Math.max(0.0, 1.0 - ice - iron);
            return norm(iron, rock, ice);
        }

        // Rocky host: captured moon could be icy if far from star; we approximate with host ice + noise.
        double ice = clamp(host.fracIce + rng.range(-0.10, 0.35), 0.0, 0.70);
        double iron = clamp(host.fracIron * rng.range(0.90, 1.20), 0.10, 0.50);
        double rock = Math.max(0.0, 1.0 - ice - iron);
        return norm(iron, rock, ice);
    }

    private static Composition norm(double iron, double rock, double ice) {
        double s = iron + rock + ice;
        if (!(s > 0)) return new Composition(0.25, 0.70, 0.05);
        return new Composition(iron / s, rock / s, ice / s);
    }

    private static String classifyMoonType(double mEarth, double fracIce, MoonOrigin origin) {
        // UI only
        if (mEarth < 0.0002) return "Moonlet";
        if (mEarth < 0.02) return (fracIce >= 0.40) ? "Icy Moon" : "Rocky Moon";
        if (origin == MoonOrigin.CAPTURED && mEarth > 0.2) return "Captured Mega-Moon";
        if (origin == MoonOrigin.IMPACT && mEarth > 0.08) return "Impact Moon";
        return (fracIce >= 0.35) ? "Icy Moon" : "Rocky Moon";
    }

    // -------------------- Orbit assignment --------------------

    private static double pickMoonSemiMajorAU(
            DeterministicRng rng,
            double aMin,
            double aMax,
            List<Orbit> assigned
    ) {
        // spacing constraints in a-space
        final double ratioMin = 1.30;
        final double logMin = 0.26;

        for (int attempt = 0; attempt < 200; attempt++) {
            double a = sampleLogUniform(aMin, aMax, rng);
            if (!Double.isFinite(a)) continue;

            boolean ok = true;
            for (Orbit o : assigned) {
                double a2 = Math.max(1e-12, o.aAU());
                double ratio = (a > a2) ? (a / a2) : (a2 / a);
                if (ratio < ratioMin) { ok = false; break; }
                double log = Math.abs(Math.log(a / a2));
                if (log < logMin) { ok = false; break; }
            }
            if (ok) return a;
        }
        return Double.NaN;
    }

    private static double sampleLogUniform(double lo, double hi, DeterministicRng rng) {
        lo = Math.max(lo, 1e-9);
        hi = Math.max(hi, lo * 1.0001);
        double u = rng.nextDouble();
        return Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * u);
    }

    // -------------------- Tidal heating --------------------

    private static String tidalHeatingLevel(PlanetModel host, Orbit moonOrbit) {
        double a = moonOrbit.aAU();
        double e = moonOrbit.e();
        if (e < 0.01) return "NONE";

        // More massive host -> stronger tides
        double massFactor = Math.min(4.0, host.massEarth / 50.0);

        // Heuristic: closer + more eccentric => more heating
        double score = (e * 100.0) * (1.0 / Math.max(a, 1e-9)) * massFactor;

        if (score > 3000) return "STRONG";
        if (score > 900) return "WEAK";
        return "NONE";
    }

    // -------------------- Specs --------------------

    private enum MoonOrigin { REGULAR, CAPTURED, IMPACT, MINOR }

    private static final class MoonSpec {
        final MoonOrigin origin;
        final double massEarth;
        MoonSpec(MoonOrigin origin, double massEarth) {
            this.origin = origin;
            this.massEarth = Math.max(0.0, massEarth);
        }
    }

    // -------------------- helpers --------------------

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
