package org.example.starforge.gui;

import org.example.starforge.core.model.*;

public final class SystemText {

    private SystemText() {}

    public static String format(SystemModel sys) {
        StringBuilder sb = new StringBuilder();

        // ===== STAR =====
        sb.append("STAR\n");
        sb.append("Name: ")
                .append(sys.star.source().proper() != null && !sys.star.source().proper().isBlank()
                        ? sys.star.source().proper()
                        : "(unnamed)")
                .append("\n");

        sb.append("Spectral type: ").append(sys.star.source().spect()).append("\n");
        sb.append("Luminosity: ").append(fmt(sys.star.lumSolar())).append(" L☉\n");
        sb.append("Mass: ").append(fmt(sys.star.massSolar())).append(" M☉\n");
        sb.append("Radius: ").append(fmt(sys.star.radiusSolar())).append(" R☉\n");
        sb.append("Teff: ").append(fmt(sys.star.teffK())).append(" K\n");
        sb.append("Activity: ").append(sys.star.activityLevel()).append("\n");
        sb.append("Evolution stage: ").append(sys.star.evolutionStage()).append("\n");

        sb.append("HZ (optimistic): ")
                .append(fmt(sys.star.hzInnerAU_opt()))
                .append(" – ")
                .append(fmt(sys.star.hzOuterAU_opt()))
                .append(" AU\n");

        sb.append("Snow line: ").append(fmt(sys.star.snowLineAU())).append(" AU\n");
        sb.append("\n");

        // ===== PLANETS =====
        sb.append("PLANETS\n");

        int pIndex = 1;
        for (PlanetModel p : sys.planets) {
            sb.append("--------------------------------------------------\n");
            sb.append("Planet ").append(pIndex++).append(": ").append(p.name).append("\n");

            sb.append("Type: ").append(p.type).append("\n");

            sb.append("Orbit: a=")
                    .append(fmt(p.orbitAroundStar.aAU()))
                    .append(" AU  e=")
                    .append(fmt(p.orbitAroundStar.e()))
                    .append("  i=")
                    .append(fmt(p.orbitAroundStar.iDeg()))
                    .append("°  ν=")
                    .append(fmt(p.orbitAroundStar.trueAnomalyDeg()))
                    .append("°\n");

            sb.append("Mass: ").append(fmtMassEarth(p.massEarth)).append(" M⊕\n");
            if (p.envelopeMassEarth > 0.02) {
                sb.append("Core: ").append(fmtMassEarth(p.coreMassEarth)).append(" M⊕  ");
                sb.append("Envelope: ").append(fmtMassEarth(p.envelopeMassEarth)).append(" M⊕  ");
                sb.append("Zenv: ").append(fmt(p.envelopeZ)).append("\n");
                if (Double.isFinite(p.basePressureBar)) {
                    sb.append("Envelope base P: ").append(fmt(p.basePressureBar)).append(" bar\n");
                }
            }

            sb.append("Radius: ").append(fmt(p.radiusEarth)).append(" R⊕\n");
            sb.append("Surface gravity: ").append(fmt(p.surfaceG)).append(" g\n");

            sb.append("Tidally locked to star: ").append(p.tidallyLockedToStar).append("\n");
            sb.append("Tidal heating: ").append(p.tidalHeatingLevel).append("\n");

            sb.append("Surface pressure: ").append(fmt(p.pressureBar)).append(" bar\n");
           // if (Double.isFinite(p.basePressureBar)) {
           //     sb.append("  Envelope base pressure: ").append(fmt(p.basePressureBar)).append(" bar\n");
         //   }

            // Atmosphere v1 (if computed)
                if (p.atmosphereType != null && p.atmosphereType != AtmosphereType.NONE) {
                    sb.append("Atmosphere: ").append(p.atmosphereType)
                            .append("  N2=").append(fmt(p.pN2Bar))
                            .append("  O2=").append(fmt(p.pO2Bar))
                            .append("  CO2=").append(fmt(p.pCO2Bar))
                            .append("  H2O=").append(fmt(p.pH2OBar))
                            .append(" bar\n");
                    sb.append("Atm mix: N2=").append(fmtPct(gasPct(p.atmosphere, "N2")))
                            .append("  O2=").append(fmtPct(gasPct(p.atmosphere, "O2")))
                            .append("  CO2=").append(fmtPct(gasPct(p.atmosphere, "CO2")))
                            .append(" %\n");

                // Water vapor saturation/debug info
                sb.append("H2O saturation: Psat=").append(fmt(p.h2oPsatBar))
                        .append(" bar  cap=").append(fmt(p.h2oInventoryCapBar))
                        .append(" bar  used=").append(fmt(p.h2oUsedBar))
                        .append(" bar  regime=").append(p.h2oIceRegime ? "ICE" : "LIQUID")
                        .append("  runaway=").append(p.h2oRunaway)
                        .append("\n");
            }

            if (p.teqK > 0) {
                sb.append("Climate: Teq=").append(fmt(p.teqK))
                        .append(" K  dT=").append(fmt(p.greenhouseDeltaK))
                        .append(" K  T=").append(fmt(p.tMeanK))
                        .append(" K  Tmin=").append(fmt(p.tMinK))
                        .append(" K  Tmax=").append(fmt(p.tMaxK))
                        .append(" K\n");
                sb.append("Oasis potential: ").append(p.oasisPotential).append("\n");
            }

            if (p.event != null) {
                sb.append("Event: ").append(p.event)
                        .append("  age=").append(fmt(p.eventAgeMyr)).append(" Myr\n");
            }

            sb.append("Habitable region present: ").append(p.habitableRegionPresent).append("\n");

            // ===== BIOSPHERE =====
            sb.append("Biosphere provenance: ").append(p.biosphereProvenance).append("\n");

            sb.append("Surface biosphere: ").append(p.biosphereSurface).append("\n");
            sb.append("Microbial biosphere: ").append(p.biosphereMicrobial).append("\n");
            sb.append("Subsurface biosphere: ").append(p.biosphereSubsurface).append("\n");
            sb.append("Heavy hydrocarbons: ").append(p.heavyHydrocarbons).append("\n");
            sb.append("Light hydrocarbons: ").append(p.lightHydrocarbons).append("\n");
            sb.append("Water coverage: ").append(p.waterCoverage).append("  GEL=")
                    .append(fmt(p.waterGELkm)).append(" km\n");
            sb.append("Resources (").append(ResourceModelUtil.ORDER).append("): ")
                    .append(ResourceModelUtil.toPRes(ResourceModelUtil.mixForPlanet(p, sys.star)))
                    .append("\n");

            // ===== MOONS =====
            if (p.moons.isEmpty()) {
                sb.append("Moons: none\n");
            } else {
                sb.append("Moons: ").append(p.moons.size()).append("\n");

                int mIndex = 1;
                for (MoonModel m : p.moons) {
                    sb.append("  ----------------------------------------------\n");
                    sb.append("  Moon ").append(mIndex++).append(": ").append(m.name()).append("\n");

                    sb.append("  Orbit: a=")
                            .append(fmt(m.orbitAroundPlanet().aAU()))
                            .append(" AU  e=")
                            .append(fmt(m.orbitAroundPlanet().e()))
                            .append("  i=")
                            .append(fmt(m.orbitAroundPlanet().iDeg()))
                            .append("°  ν=")
                            .append(fmt(m.orbitAroundPlanet().trueAnomalyDeg()))
                            .append("°\n");

                    sb.append("  Mass: ").append(fmtMassEarth(m.massEarth())).append(" M⊕\n");
                    sb.append("  Radius: ").append(fmt(m.radiusEarth())).append(" R⊕\n");
                    sb.append("  Surface gravity: ").append(fmt(m.surfaceG())).append(" g\n");

                    sb.append("  Tidally locked to planet: ").append(m.tidallyLockedToPlanet()).append("\n");
                    sb.append("  Tidal heating: ").append(m.tidalHeatingLevel()).append("\n");

                    sb.append("  Surface pressure: ").append(fmt(m.pressureBar())).append(" bar\n");
                    sb.append("  Atmosphere: ").append(m.atmosphereType() != null ? m.atmosphereType().name() : "NONE").append("\n");
                    sb.append("  Atm mix: N2=").append(fmtPct(gasPct(m.atmosphere(), "N2")))
                            .append("  O2=").append(fmtPct(gasPct(m.atmosphere(), "O2")))
                            .append("  CO2=").append(fmtPct(gasPct(m.atmosphere(), "CO2")))
                            .append(" %\n");
                    if (m.teqK() > 0) {
                        sb.append("  Climate: Teq=").append(fmt(m.teqK()))
                                .append(" K  dT=").append(fmt(m.greenhouseDeltaK()))
                                .append(" K  T=").append(fmt(m.tMeanK()))
                                .append(" K  Tmin=").append(fmt(m.tMinK()))
                                .append(" K  Tmax=").append(fmt(m.tMaxK()))
                                .append(" K\n");
                    } else {
                        sb.append("  Tmin: ").append(fmt(m.tMinK())).append(" K\n");
                    }
                    if (m.envelopeMassEarth() > 0.0) {
                        sb.append("  Envelope: ").append(fmtMassEarth(m.envelopeMassEarth())).append(" M⊕\n");
                    }

                    if (m.event() != null) {
                        sb.append("  Event: ").append(m.event()).append("  age=").append(fmt(m.eventAgeMyr())).append(" Myr\n");
                    }

                    sb.append("  Habitable region present: ").append(m.habitableRegionPresent()).append("\n");

                    sb.append("  Surface biosphere: ").append(m.biosphereSurface()).append("\n");
                    sb.append("  Microbial biosphere: ").append(m.biosphereMicrobial()).append("\n");
                    sb.append("  Subsurface biosphere: ").append(m.biosphereSubsurface()).append("\n");

                    sb.append("  Heavy hydrocarbons: ").append(m.heavyHydrocarbons()).append("\n");
                    sb.append("  Light hydrocarbons: ").append(m.lightHydrocarbons()).append("\n");
                    sb.append("  Water coverage: ").append(m.waterCoverage()).append("  GEL=")
                            .append(fmt(m.waterGELkm())).append(" km\n");
                    sb.append("  Resources (").append(ResourceModelUtil.ORDER).append("): ")
                            .append(ResourceModelUtil.toPRes(ResourceModelUtil.mixForMoon(m, sys.star, p)))
                            .append("\n");
                }
            }

            sb.append("\n");
        }

        // ===== ZONES (once) =====
        sb.append("ZONES\n");
        if (sys.zones != null && !sys.zones.isEmpty()) {
            for (var z : sys.zones) {
                sb.append(" - ").append(z.type())
                        .append("  ").append(fmt(z.aMinAU())).append("..").append(fmt(z.aMaxAU())).append(" AU")
                        .append("  mass=").append(fmtMassEarth(z.massEarth())).append(" M⊕\n");
                if (z.event() != null) {
                    sb.append("   Event: ").append(z.event()).append(" age=").append(fmt(z.eventAgeMyr())).append(" Myr\n");
                }
            }
        }
        sb.append("\n");

        // ===== MASS BUDGET DEBUG =====
        double planetMass = 0.0;
        if (sys.planets != null) {
            for (PlanetModel p : sys.planets) {
                if (Double.isFinite(p.massEarth)) planetMass += p.massEarth;
            }
        }

        double zoneMass = 0.0;
        if (sys.zones != null) {
            for (ZoneModel z : sys.zones) {
                if (Double.isFinite(z.massEarth())) zoneMass += z.massEarth();
            }
        }

        sb.append("MASS BUDGET (debug)\n");
        sb.append(" - Planets total: ").append(fmtMassEarth(planetMass)).append(" M⊕\n");
        sb.append(" - Zones total:   ").append(fmtMassEarth(zoneMass)).append(" M⊕\n");
        sb.append(" - Accounted:     ").append(fmtMassEarth(planetMass + zoneMass)).append(" M⊕\n");

        if (Double.isFinite(sys.diskSolidsEarth)) {
            sb.append(" - Disk solids:   ").append(fmtMassEarth(sys.diskSolidsEarth)).append(" M⊕\n");
        }
        if (Double.isFinite(sys.diskTotalEarth)) {
            sb.append(" - Disk total:    ").append(fmtMassEarth(sys.diskTotalEarth)).append(" M⊕\n");
        }
        if (sys.accretionStats != null) {
            sb.append(" - Solids before: ").append(fmtMassEarth(sys.accretionStats.solidsBeforeEarth())).append(" M⊕\n");
            sb.append(" - Solids after:  ").append(fmtMassEarth(sys.accretionStats.solidsAfterEarth())).append(" M⊕\n");
            sb.append(" - Embryos mass:  ").append(fmtMassEarth(sys.accretionStats.embryoMassAfterEarth())).append(" M⊕\n");
        }
        sb.append("\n");

        // ===== MAJOR COMETS (once) =====
        if (sys.comets != null && !sys.comets.isEmpty()) {
            sb.append("Major comets:\n");
            for (var c : sys.comets) {
                sb.append(" - ").append(c.name())
                        .append("  source=").append(c.sourceZone())
                        .append("  R=").append(fmt(c.radiusKm())).append(" km")
                        .append("  a=").append(fmt(c.orbitAroundStar().aAU())).append(" AU")
                        .append("  e=").append(fmt(c.orbitAroundStar().e())).append("\n");
                if (c.event() != null) {
                    sb.append("   Event: ").append(c.event()).append(" age=").append(fmt(c.eventAgeMyr())).append(" Myr\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String fmt(double v) {
        if (!Double.isFinite(v)) return "NaN";
        return String.format("%.3f", v);
    }

    private static String fmtMassEarth(double v) {
        if (!Double.isFinite(v)) return "NaN";
        double av = Math.abs(v);
        if (av == 0.0) return "0";
        if (av >= 0.01) return String.format("%.3f", v);
        if (av >= 1e-6) return String.format("%.6f", v);
        return String.format("%.3e", v);
    }

    private static String fmtPct(double v) {
        if (!Double.isFinite(v)) return "0.0";
        return String.format("%.1f", v);
    }

    private static double gasPct(java.util.Map<String, Double> atmosphere, String key) {
        if (atmosphere == null || atmosphere.isEmpty() || key == null) return 0.0;
        double total = 0.0;
        for (var e : atmosphere.entrySet()) {
            Double v = e.getValue();
            if (v == null || !Double.isFinite(v) || v <= 0.0) continue;
            total += v;
        }
        if (!(total > 0.0)) return 0.0;
        Double k = atmosphere.get(key);
        if (k == null || !Double.isFinite(k) || k <= 0.0) return 0.0;
        return (k / total) * 100.0;
    }
}
