package org.example.starforge.core.gen;

import org.example.starforge.core.model.DiskModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.model.StarRecord;
import org.example.starforge.core.physics.Units;
import org.example.starforge.core.random.DeterministicRng;

public final class DiskGenerator {

    private DiskGenerator() {
        // utility class
    }

    public static DiskModel generate(StarModel star, StarRecord rec, DeterministicRng rng) {

        // Disk mass fraction: median ~1%, wide tails
        double diskMassFrac = rng.logUniform(0.003, 0.08);

        String sp = (rec.spect() == null) ? "" : rec.spect().trim().toUpperCase();

        // Correction for small stars. Planets quantity enrichement
        double classBoost = 1.0;
        if (!sp.isEmpty()) {
            char c = sp.charAt(0);
            if (c == 'M') classBoost = rng.range(1.6, 2.4);  // заметно больше для M
            else if (c == 'K') classBoost = rng.range(1.15, 1.35); // немного больше для K
            else if (c == 'G') classBoost = rng.range(1.05, 1.15); // совсем чуть-чуть для G
        }

        diskMassFrac *= classBoost;


        // Metallicity scaling
        double metallicityZ = rng.logUniform(0.4, 2.5);

        boolean isMultiple = rec.base() != null && !rec.base().isBlank();
        double multiplicityPenalty = isMultiple ? rng.range(0.4, 0.8) : 1.0;

        double mDiskEarth =
                star.massSolar() * Units.M_EARTH_PER_M_SUN
                        * diskMassFrac
                        * multiplicityPenalty;

        // Solids fraction (not fixed 1%)
        double solidsFrac = rng.range(0.007, 0.02);
        double mSolidsEarth = mDiskEarth * solidsFrac * metallicityZ;

        // Inner edge (dust sublimation)
        double rInnerAU = Math.max(
                0.03,
                0.04 * Math.sqrt(Math.max(1e-6, star.lumSolar()))
        );
        rInnerAU *= rng.range(0.9, 1.4);

        // Solids formation region (where planets actually grow)
        double rSolidsOuterAU;
        if (star.massSolar() < 0.5) {
            rSolidsOuterAU = rng.logUniform(15.0, 40.0);
        } else if (star.massSolar() < 1.1) {
            rSolidsOuterAU = rng.logUniform(20.0, 60.0);
        } else {
            rSolidsOuterAU = rng.logUniform(30.0, 100.0);
        }

        // Physical outer disk (reservoirs, comets, Oort)
        double rOuterAU = rng.logUniform(100.0, 600.0);
        if (rng.chance(0.05)) rOuterAU *= rng.range(2.0, 6.0);
        if (rng.chance(0.01)) rOuterAU *= rng.range(2.0, 4.0);

        if (isMultiple) {
            rSolidsOuterAU *= rng.range(0.4, 0.8);
            rOuterAU *= rng.range(0.3, 0.6);
        }

        rSolidsOuterAU = Math.max(rSolidsOuterAU, rInnerAU * 5.0);
        rOuterAU = Math.max(rOuterAU, rSolidsOuterAU * 1.5);

        double pSigma = rng.range(0.9, 1.4);
        double iceBoost = rng.range(2.0, 4.0);
        double kSpacing = rng.range(8.0, 12.0);

        // Gas budget (H/He dominated) as the remaining disk mass after solids.
        // Split into inner/outer pools around the snow line for later gas accretion heuristics.
        double mGasEarth = Math.max(0.0, mDiskEarth - mSolidsEarth);

        double snow = Math.max(rInnerAU + 0.05, star.snowLineAU());
        double pGas = Math.max(0.2, Math.min(1.8, pSigma)); // reuse solids slope as a stable proxy

        double fracInnerGas;
        {
            double expo = 2.0 - pGas;
            double r0 = rInnerAU;
            double r1 = Math.max(r0 + 0.05, Math.min(snow, rOuterAU));
            double r2 = rOuterAU;

            double num = Math.pow(r1, expo) - Math.pow(r0, expo);
            double den = Math.pow(r2, expo) - Math.pow(r0, expo);
            fracInnerGas = (den <= 0.0) ? 0.5 : clamp(num / den, 0.05, 0.95);
        }

        double mGasInnerEarth = mGasEarth * fracInnerGas;
        double mGasOuterEarth = mGasEarth - mGasInnerEarth;

return new DiskModel(
                diskMassFrac,
                metallicityZ,
                mDiskEarth,
                mSolidsEarth,
                mGasEarth,
                mGasInnerEarth,
                mGasOuterEarth,
                rInnerAU,
                rSolidsOuterAU,
                rOuterAU,
                pSigma,
                iceBoost,
                kSpacing
        );
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
