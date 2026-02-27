package org.example.starforge.core.model;

/**
 * Derives a simple, deterministic resource mix from body composition + temperature.
 * Order: metal, silicates, water_ice, methane_ice, ammonia_ice, organics
 */
public final class ResourceModelUtil {

    private ResourceModelUtil() {}

    public static final String ORDER =
            "metal,silicates,water_ice,methane_ice,ammonia_ice,organics";

    public static double[] mixForPlanet(PlanetModel p, StarModel star) {
        if (p == null) return zeros();
        double tempK = pickTempK(p.tMinK, p.teqK);
        double aAU = (p.orbitAroundStar != null) ? p.orbitAroundStar.aAU() : Double.NaN;
        double snowAU = (star != null) ? star.snowLineAU() : Double.NaN;
        double lum = (star != null) ? star.lumSolar() : Double.NaN;
        String activity = (star != null) ? star.activityLevel() : null;
        return mixFromFractions(
                p.fracIron, p.fracRock, p.fracIce,
                tempK, aAU, snowAU, lum, activity,
                p.massEarth, p.envelopeMassEarth
        );
    }

    public static double[] mixForMoon(MoonModel m, StarModel star, PlanetModel host) {
        if (m == null) return zeros();
        double tempK = pickTempK(m.tMinK(), m.teqK());
        double aAU = (host != null && host.orbitAroundStar != null) ? host.orbitAroundStar.aAU() : Double.NaN;
        double snowAU = (star != null) ? star.snowLineAU() : Double.NaN;
        double lum = (star != null) ? star.lumSolar() : Double.NaN;
        String activity = (star != null) ? star.activityLevel() : null;
        return mixFromFractions(
                m.fracIron(), m.fracRock(), m.fracIce(),
                tempK, aAU, snowAU, lum, activity,
                m.massEarth(), m.envelopeMassEarth()
        );
    }

    public static String toPRes(double[] mix) {
        if (mix == null || mix.length != 6) return "0,0,0,0,0,0";
        return String.format(
                "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                mix[0], mix[1], mix[2], mix[3], mix[4], mix[5]
        );
    }

    private static double pickTempK(double tMinK, double teqK) {
        if (Double.isFinite(tMinK) && tMinK > 0.0) return tMinK;
        if (Double.isFinite(teqK) && teqK > 0.0) return teqK;
        return 200.0;
    }

    private static double[] mixFromFractions(
            double fe,
            double rock,
            double ice,
            double tempK,
            double aAU,
            double snowAU,
            double lumSolar,
            String activityLevel,
            double massEarth,
            double envelopeMassEarth
    ) {
        double fFe = clamp01(fe);
        double fRock = clamp01(rock);
        double fIce = clamp01(ice);

        // Gentle snow-line adjustment: push ice outward, reduce ice inward.
        if (Double.isFinite(aAU) && Double.isFinite(snowAU) && snowAU > 0.0) {
            double ratio = aAU / snowAU;
            double shift = 0.0;
            if (ratio < 0.85) shift = -0.15;
            else if (ratio < 1.0) shift = -0.07;
            else if (ratio > 1.15) shift = 0.15;
            else if (ratio > 1.0) shift = 0.07;

            if (shift != 0.0) {
                double newIce = clamp01(fIce + shift);
                double delta = newIce - fIce;
                fIce = newIce;
                fRock = clamp01(fRock - delta);
            }
        }

        // Star luminosity tweak: brighter stars reduce retained volatiles; dimmer stars retain more.
        if (Double.isFinite(lumSolar) && lumSolar > 0.0) {
            double lumFactor = clamp(Math.log10(lumSolar), -1.0, 1.0); // 0.1..10 Lsun -> [-1..1]
            double shift = -0.08 * lumFactor; // brighter -> negative (less ice), dimmer -> positive
            if (shift != 0.0) {
                double newIce = clamp01(fIce + shift);
                double delta = newIce - fIce;
                fIce = newIce;
                fRock = clamp01(fRock - delta);
            }
        }

        // Activity tweak: high activity strips volatiles, low activity preserves them.
        if (activityLevel != null && !activityLevel.isBlank()) {
            String a = activityLevel.trim().toUpperCase();
            double shift = 0.0;
            if (a.contains("HIGH") || a.contains("VERY") || a.contains("EXTREME")) shift = -0.06;
            else if (a.contains("LOW") || a.contains("QUIET")) shift = 0.04;
            if (shift != 0.0) {
                double newIce = clamp01(fIce + shift);
                double delta = newIce - fIce;
                fIce = newIce;
                fRock = clamp01(fRock - delta);
            }
        }

        double sum = fFe + fRock + fIce;
        if (sum <= 0.0) {
            fFe = 0.30;
            fRock = 0.65;
            fIce = 0.05;
        } else {
            fFe /= sum;
            fRock /= sum;
            fIce /= sum;
        }

        double coldness = clamp01((200.0 - tempK) / 200.0);
        double orgFactor = 0.05 + 0.25 * coldness; // 0.05..0.30 of ice
        double organics = fIce * orgFactor;
        double iceAvail = Math.max(0.0, fIce - organics);

        double methaneRatio;
        double ammoniaRatio;
        if (tempK < 70.0) {
            methaneRatio = 0.45;
            ammoniaRatio = 0.25;
        } else if (tempK < 110.0) {
            methaneRatio = 0.35;
            ammoniaRatio = 0.20;
        } else if (tempK < 150.0) {
            methaneRatio = 0.20;
            ammoniaRatio = 0.10;
        } else if (tempK < 200.0) {
            methaneRatio = 0.10;
            ammoniaRatio = 0.05;
        } else {
            methaneRatio = 0.03;
            ammoniaRatio = 0.01;
        }

        double waterRatio = Math.max(0.0, 1.0 - methaneRatio - ammoniaRatio);

        double waterIce = iceAvail * waterRatio;
        double methaneIce = iceAvail * methaneRatio;
        double ammoniaIce = iceAvail * ammoniaRatio;

        double metal = fFe;
        double silicates = fRock;

        // Envelope suppression: reduce surface-accessible solids if thick H/He envelope exists.
        if (Double.isFinite(envelopeMassEarth) && envelopeMassEarth > 0.02) {
            double envFrac = (Double.isFinite(massEarth) && massEarth > 0.0)
                    ? clamp01(envelopeMassEarth / massEarth)
                    : 0.25;
            double solidScale = clamp01(1.0 - 0.7 * Math.min(1.0, envFrac / 0.3));
            metal *= solidScale;
            silicates *= solidScale;
        }

        // Mass differentiation: larger bodies bury more metal under deep mantles/cores.
        if (Double.isFinite(massEarth) && massEarth > 0.0) {
            double bury = 0.0;
            if (massEarth > 0.3) bury = 0.05;
            if (massEarth > 1.0) bury = 0.10;
            if (massEarth > 3.0) bury = 0.15;
            if (massEarth > 10.0) bury = 0.20;
            double scale = clamp01(1.0 - bury);
            metal *= scale;
        }

        double total = metal + silicates + waterIce + methaneIce + ammoniaIce + organics;
        if (total <= 0.0) return zeros();

        return new double[] {
                metal / total,
                silicates / total,
                waterIce / total,
                methaneIce / total,
                ammoniaIce / total,
                organics / total
        };
    }

    private static double[] zeros() {
        return new double[] {0, 0, 0, 0, 0, 0};
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
