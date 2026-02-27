package org.example.starforge.core.model;

import org.example.starforge.core.random.DeterministicRng;

/**
 * Mutable solids reservoir, discretized into radial bins (annuli).
 * Stores remaining solids mass in each annulus (Earth masses).
 */
public final class SolidsDiskState {

    private final double[] edgesAU;   // size = bins + 1
    private final double[] massEarth; // size = bins

    private final double rInnerAU;
    private final double rOuterAU;

    public SolidsDiskState(DiskModel disk, StarModel star, int bins) {
        this(disk, star, null, bins);
    }

    public SolidsDiskState(DiskModel disk, StarModel star, DeterministicRng rng, int bins) {
        this.rInnerAU = disk.rInnerAU;
        this.rOuterAU = disk.rSolidsOuterAU;

        this.edgesAU = new double[bins + 1];
        this.massEarth = new double[bins];

        // Log-spaced bins
        double logMin = Math.log(rInnerAU);
        double logMax = Math.log(rOuterAU);
        for (int i = 0; i <= bins; i++) {
            double t = (double) i / (double) bins;
            edgesAU[i] = Math.exp(logMin + (logMax - logMin) * t);
        }

        // Populate initial solids mass per annulus using AccretionModel normalization
        AccretionModel acc = new AccretionModel();
        double sigma0 = acc.sigma0EarthPerAu2(disk, star);

        for (int i = 0; i < bins; i++) {
            double a1 = edgesAU[i];
            double a2 = edgesAU[i + 1];
            massEarth[i] = acc.ringSolidsMassEarth(star, disk, sigma0, a1, a2);
        }

        // Optional: "pebble drift pile-up" proxy near inner edge
        if (rng != null) {
            applyInnerPebblePileup(disk, star, rng);
        }
    }

    /** Total remaining solids mass (Earth masses). */
    public double totalMassEarth() {
        double s = 0.0;
        for (double m : massEarth) s += m;
        return s;
    }

    // -------------------- bin accessors (for post-processing / zone building) --------------------

    /** Number of radial bins (annuli). */
    public int bins() {
        return massEarth.length;
    }

    /** Remaining solids mass in bin i (Earth masses). */
    public double binMassEarth(int i) {
        return massEarth[i];
    }

    /** Inner edge of bin i (AU). */
    public double binInnerAU(int i) {
        return edgesAU[i];
    }

    /** Outer edge of bin i (AU). */
    public double binOuterAU(int i) {
        return edgesAU[i + 1];
    }

    /**
     * Remove mass proportionally from ALL bins (global bookkeeping).
     * Useful to "pay" for initial embryo seed mass so mass budget closes.
     *
     * @return actually removed (Earth masses)
     */
    public double removeMassGlobal(double amountEarth) {
        if (!Double.isFinite(amountEarth) || amountEarth <= 0) return 0.0;

        double total = totalMassEarth();
        if (total <= 0) return 0.0;

        double take = Math.min(amountEarth, total);
        double scale = (total - take) / total; // multiply each bin by this

        for (int i = 0; i < massEarth.length; i++) {
            massEarth[i] *= scale;
        }

        return take;
    }

    /** Mass remaining in [aMin, aMax] AU (Earth masses), proportional overlap with bins. */
    public double massIn(double aMin, double aMax) {
        aMin = Math.max(rInnerAU, aMin);
        aMax = Math.min(rOuterAU, aMax);
        if (aMax <= aMin) return 0.0;

        double sum = 0.0;

        for (int i = 0; i < massEarth.length; i++) {
            double b1 = edgesAU[i];
            double b2 = edgesAU[i + 1];
            if (b2 <= aMin || b1 >= aMax) continue;

            double overlap1 = Math.max(b1, aMin);
            double overlap2 = Math.min(b2, aMax);
            double frac = (overlap2 - overlap1) / (b2 - b1);
            if (frac > 0) sum += massEarth[i] * frac;
        }
        return Math.max(0.0, sum);
    }

    /**
     * Remove 'amountEarth' solids from [aMin, aMax] AU, proportionally to what's there.
     * Returns actually removed (Earth masses).
     */
    public double removeMass(double aMin, double aMax, double amountEarth) {
        if (!Double.isFinite(amountEarth) || amountEarth <= 0) return 0.0;

        aMin = Math.max(rInnerAU, aMin);
        aMax = Math.min(rOuterAU, aMax);
        if (aMax <= aMin) return 0.0;

        double available = massIn(aMin, aMax);
        if (available <= 0) return 0.0;

        double take = Math.min(amountEarth, available);
        double removed = 0.0;

        for (int i = 0; i < massEarth.length; i++) {
            double b1 = edgesAU[i];
            double b2 = edgesAU[i + 1];
            if (b2 <= aMin || b1 >= aMax) continue;

            double overlap1 = Math.max(b1, aMin);
            double overlap2 = Math.min(b2, aMax);
            double frac = (overlap2 - overlap1) / (b2 - b1);
            if (frac <= 0) continue;

            double binAvail = massEarth[i] * frac;
            if (binAvail <= 0) continue;

            double binTake = take * (binAvail / available);

            // Convert overlap-take into whole-bin mass to subtract
            double wholeBinTake = binTake / frac;
            wholeBinTake = Math.min(wholeBinTake, massEarth[i]);

            massEarth[i] -= wholeBinTake;
            removed += wholeBinTake * frac;
        }

        return Math.max(0.0, removed);
    }

    // -------------------- Inner pebble pile-up proxy --------------------

    private void applyInnerPebblePileup(DiskModel disk, StarModel star, DeterministicRng rng) {
        // Not every system: keep variety.
        double p = 0.35;

        // Mild metallicity influence
        double z = disk.metallicityZ;
        if (z > 1.5) p += 0.10;
        if (z < 0.7) p -= 0.10;
        p = clamp(p, 0.10, 0.60);

        if (!rng.chance(p)) return;

        // Fraction of total solids moved inward
        double frac = rng.range(0.08, 0.30);
        if (rng.chance(0.08)) frac *= rng.range(1.2, 1.8); // rare strong pile-up
        frac = clamp(frac, 0.05, 0.45);

        double total = totalMassEarth();
        if (total <= 0) return;

        double targetMove = total * frac;

        // Belt: rInner..rPile (cap at ~0.35 AU to avoid making ALL systems ultra-compact)
        double r0 = rInnerAU;
        double rPile = Math.min(0.35, r0 * rng.range(6.0, 10.0));
        rPile = Math.max(rPile, r0 * 2.0);

        int beltLast = -1;
        for (int i = 0; i < massEarth.length; i++) {
            double a1 = edgesAU[i];
            double a2 = edgesAU[i + 1];
            double mid = 0.5 * (a1 + a2);
            if (mid <= rPile) beltLast = i;
            else break;
        }
        if (beltLast < 0) return;

        int donorFirst = beltLast + 1;
        if (donorFirst >= massEarth.length) return;

        double donorMass = 0.0;
        for (int i = donorFirst; i < massEarth.length; i++) donorMass += massEarth[i];
        if (donorMass <= 1e-12) return;

        double move = Math.min(targetMove, donorMass * 0.85); // don't drain donors completely
        if (move <= 0) return;

        // Remove proportionally from donor bins
        double scale = (donorMass - move) / donorMass;
        for (int i = donorFirst; i < massEarth.length; i++) {
            massEarth[i] *= scale;
        }

        // Add into belt bins with a bias toward the inner edge: weight ~ 1/sqrt(r)
        double wSum = 0.0;
        double[] w = new double[beltLast + 1];
        for (int i = 0; i <= beltLast; i++) {
            double a1 = edgesAU[i];
            double a2 = edgesAU[i + 1];
            double mid = 0.5 * (a1 + a2);
            double wi = 1.0 / Math.sqrt(Math.max(1e-6, mid));
            w[i] = wi;
            wSum += wi;
        }
        if (wSum <= 0) return;

        for (int i = 0; i <= beltLast; i++) {
            massEarth[i] += move * (w[i] / wSum);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public double rInnerAU() {
        return rInnerAU;
    }

    public double rOuterAU() {
        return rOuterAU;
    }
}
