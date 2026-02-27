package org.example.starforge.core.gen;

import org.example.starforge.core.model.DiskModel;
import org.example.starforge.core.model.DiskProfile;
import org.example.starforge.core.model.EmbryoModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.model.CrustType;
import org.example.starforge.core.random.DeterministicRng;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates initial planetary embryos (oligarchic seeds).
 * Goal: avoid "hundreds of tiny dwarfs near the star" and produce a plausible spacing + mass spectrum.
 *
 * IMPORTANT:
 *  - Do NOT use logNormal with median=0.0 for multipliers (it zeroes everything).
 *  - Avoid fixed 400 log-steps: that creates too many embryos.
 */
public final class EmbryoGenerator {

        public List<EmbryoModel> generate(
            StarModel star,
            DiskModel disk,
            DiskProfile profile,
            DeterministicRng rng
    ) {
        return generate(star, disk, profile, rng, false);
    }

    /**
     * @param outerStage If true, do not push rMin far away from disk.rInnerAU.
     *                   Used for the second-pass growth beyond the snow line, where disk.rInnerAU is already set to aSplit.
     */
    public List<EmbryoModel> generate(
            StarModel star,
            DiskModel disk,
            DiskProfile profile,
            DeterministicRng rng,
            boolean outerStage
    ) {
        return generate(star, disk, profile, rng, outerStage, Double.NaN);
    }

    /**
     * @param hardMaxAU If finite, embryo generation hard-caps at this semi-major axis.
     */
    public List<EmbryoModel> generate(
            StarModel star,
            DiskModel disk,
            DiskProfile profile,
            DeterministicRng rng,
            boolean outerStage,
            double hardMaxAU
    ) {

        List<EmbryoModel> embryos = new ArrayList<>();

        // We keep it aligned with current SystemGenerator usage (it later uses ~4 Myr).
        final double tGasMyr = 4.0;

        // --- Inner edge: take dust sublimation edge + extra safety ---
        // DiskGenerator already computes rInnerAU, but it's often too "optimistic".
        // This pushes the first embryo outward a bit to avoid 0.0x AU spam.
        double rMin;
        if (outerStage) {
            // For outer-stage generation, disk.rInnerAU is already aSplit (just beyond snow line).
            // Do not push rMin far outward; we want embryos to populate the rich region right after the snow line.
            rMin = Math.max(0.05, disk.rInnerAU) * rng.range(0.95, 1.15);
        } else {
            // Inner-stage: keep away from the sublimation zone and add variability.
            double rDust = 0.07 * Math.sqrt(Math.max(1e-6, star.lumSolar())); // simple dust line proxy
            rMin = Math.max(disk.rInnerAU, rDust);
            rMin *= rng.range(1.1, 1.8); // keep variability, but prevent "right next to star"
        }

        // outer solids region (growth region)
        double rMax = Math.max(rMin * 3.0, disk.rSolidsOuterAU);
        if (Double.isFinite(hardMaxAU) && hardMaxAU > 0.0) {
            rMax = Math.min(rMax, hardMaxAU);
        }

        // --- Decide how many embryos we roughly want ---
// More solids -> more embryos, but never hundreds.
// For outer-stage we intentionally keep counts lower so the same reservoir builds fewer, larger cores.
double mSol = Math.max(0.0, disk.mSolidsEarth);
int softMax;
if (outerStage) {
    softMax = (int) clamp(10 + 16 * Math.log10(1.0 + mSol), 8, 70);
} else {
    softMax = (int) clamp(20 + 25 * Math.log10(1.0 + mSol), 18, 120);
}
// Start from rMin and step outward using a mix of geometric ratio + Hill spacing.
        double a = rMin;

        // We allow a rare "hot chain" systems, but not every system.
        boolean innerChain = rng.chance(0.12); // 12% chance: compact inner systems

        int guard = 0;
        while (a < rMax && embryos.size() < softMax && guard++ < 2000) {
            // Local solids surface density (Earth masses per AU^2)
            double sigma = Math.max(1e-9, profile.sigmaSolid(a));

            // --- Seed mass heuristic ---
            // Use a crude "fraction of isolation mass" style scaling.
            // This gives much larger seeds where sigma is high, and prevents uniform tiny masses.
            //
            // m0 ~ C * (sigma/10)^(1.5) * a^3 * M*^(-0.5)  [Earth masses]
            double m0 = 0.020
                    * Math.pow(sigma / 10.0, 1.5)
                    * Math.pow(a, 3.0)
                    * Math.pow(Math.max(0.2, star.massSolar()), -0.5);

            // Add lognormal scatter (median=1.0 !!!)
            m0 *= rng.logNormal(1.0, 0.55);

            // Clamp: we want embryos to be "seeds", not finished planets.
            // But also not microscopic dust.
            m0 = clamp(m0, 0.003, outerStage ? 0.80 : (innerChain ? 0.35 : 0.20));

            // --- Growth time heuristic (Myr) ---
            // Faster inside, slower outside; larger sigma -> faster.
            // This decides whether embryo exists during gas (for later gas accretion logic).
            double tGrow = 0.25 * Math.pow(a, 1.6) / Math.pow(sigma / 10.0, 0.9);
            tGrow *= rng.logNormal(1.0, 0.45);
            tGrow = clamp(tGrow, 0.02, 12.0);

            boolean gasPhase = tGrow < tGasMyr;
            double birthTime = Math.min(tGrow, tGasMyr);

            EmbryoModel e = new EmbryoModel(a, m0, birthTime, gasPhase);

            // --- Initial bulk composition heuristic ---
            // We treat "water" as solid ices that become available beyond the snow line.
            double snow = star.snowLineAU();
            double iceFrac = iceFractionHeuristic(a, snow);

            // Within the refractory fraction, split into iron vs rock.
            double feShare = lerp(0.30, 0.22, iceFrac); // slightly less iron in volatile-rich region
            double refr = Math.max(0.0, 1.0 - iceFrac);
            e.fracIce = iceFrac;
            e.fracIron = refr * feShare;
            e.fracRock = refr * (1.0 - feShare);
            e.waterMassFrac = e.fracIce;
            e.crustType = (iceFrac > 0.25) ? CrustType.ICY : CrustType.BASALTIC;
            e.renormalizeComposition();

            embryos.add(e);

            // --- Next semi-major axis ---
            // Base step: geometric ratio (compact chain -> smaller ratios)
            double ratio = innerChain
                    ? rng.range(1.08, 1.22)
                    : rng.range(1.15, 1.45);

            double aNext = a * ratio;

            // Enforce Hill spacing: delta_a >= k * R_H
            // R_H = a * (m / (3*M*))^(1/3), with m in Earth masses -> convert to Solar masses.
            double mSolar = (m0 / 332946.0); // 1 Msun ~= 332,946 Mearth
            double hill = a * Math.cbrt(mSolar / (3.0 * Math.max(0.08, star.massSolar())));
            double minSpacing = disk.kSpacing * hill;

            if (aNext < a + minSpacing) {
                aNext = a + minSpacing;
            }

            // Also keep a minimum absolute step so we don't get stuck at tiny increments
            double absMin = innerChain ? 0.003 : 0.006;
            if (aNext < a + absMin) aNext = a + absMin;

            a = aNext;
        }

        return embryos;
    }

    private static double iceFractionHeuristic(double aAU, double snowLineAU) {
        double snow = Math.max(1e-6, snowLineAU);
        // Smooth step: 0 inside ~0.85 snow, ~1 outside ~1.15 snow.
        double x0 = 0.85 * snow;
        double x1 = 1.15 * snow;
        if (aAU <= x0) return 0.02; // small residual volatiles/hydrates
        if (aAU >= x1) return 0.30; // volatile-rich solids (tunable)
        double t = (aAU - x0) / (x1 - x0);
        t = clamp(t, 0.0, 1.0);
        return lerp(0.02, 0.30, t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
