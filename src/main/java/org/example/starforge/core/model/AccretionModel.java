package org.example.starforge.core.model;

public final class AccretionModel {

    public AccretionModel() {}

    /**
     * Normalization sigma0 (Earth masses per AU^2 at 1 AU) for SOLIDS surface density:
     *   sigma(r) = sigma0 * r^-p
     * with iceBoost applied for r >= snowLineAU.
     *
     * Solves sigma0 so that total solids mass in [rInnerAU, rSolidsOuterAU] equals disk.mSolidsEarth.
     */
    public double sigma0EarthPerAu2(DiskModel disk, StarModel star) {
        double p = disk.pSigma;
        double r0 = Math.max(1e-6, disk.rInnerAU);
        double r1 = Math.max(r0 * 1.000001, disk.rSolidsOuterAU);
        double snow = Math.max(r0, star.snowLineAU());
        double boost = Math.max(1.0, disk.iceBoost);

        // Total mass = 2π * sigma0 * [ ∫ r^{1-p} dr (inside snow) + boost * ∫ r^{1-p} dr (outside snow) ]
        double insideA = r0;
        double insideB = Math.min(r1, snow);

        double outsideA = Math.max(r0, snow);
        double outsideB = r1;

        double I = 0.0;
        if (insideB > insideA) I += integral_r_1_minus_p(insideA, insideB, p);
        if (outsideB > outsideA) I += boost * integral_r_1_minus_p(outsideA, outsideB, p);

        // 2π factor
        double denom = 2.0 * Math.PI * Math.max(I, 1e-18);

        return disk.mSolidsEarth / denom;
    }

    /**
     * Solids mass (Earth masses) in an annulus [a1, a2] using the sigma0 computed above.
     * Applies iceBoost beyond snowLineAU.
     */
    public double ringSolidsMassEarth(StarModel star, DiskModel disk, double sigma0, double a1, double a2) {
        double p = disk.pSigma;
        double r0 = Math.max(disk.rInnerAU, Math.min(a1, a2));
        double r1 = Math.min(disk.rSolidsOuterAU, Math.max(a1, a2));
        if (r1 <= r0) return 0.0;

        double snow = Math.max(disk.rInnerAU, star.snowLineAU());
        double boost = Math.max(1.0, disk.iceBoost);

        double insideA = r0;
        double insideB = Math.min(r1, snow);

        double outsideA = Math.max(r0, snow);
        double outsideB = r1;

        double I = 0.0;
        if (insideB > insideA) I += integral_r_1_minus_p(insideA, insideB, p);
        if (outsideB > outsideA) I += boost * integral_r_1_minus_p(outsideA, outsideB, p);

        return 2.0 * Math.PI * sigma0 * I;
    }

    /**
     * ∫ r^{1-p} dr from a to b.
     */
    private static double integral_r_1_minus_p(double a, double b, double p) {
        // exponent n = 2 - p
        double n = 2.0 - p;
        if (Math.abs(n) < 1e-9) {
            // p ~ 2 => integral r^{-1} dr = ln(b/a)
            return Math.log(b / a);
        }
        return (Math.pow(b, n) - Math.pow(a, n)) / n;
    }
}
