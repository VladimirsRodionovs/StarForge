package org.example.starforge.core.gen;

import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.random.DeterministicRng;

import java.util.ArrayList;
import java.util.List;

/**
 * Places semi-major axes using approximate mutual Hill spacing.
 * Masses are not fully known yet, so we use an iterative "mass guess" approach.
 */
public final class HillPlacer {

    public List<Double> placeOrbitsAU(
            StarModel star,
            double rInnerAU,
            double rOuterAU,
            double kSpacing,
            DeterministicRng rng
    ) {
        List<Double> aList = new ArrayList<>();

        double a = rInnerAU * rng.range(1.0, 1.4);
        double mStarEarth = star.massSolar() * 332_946.0487;

        // initial guess mass in Earth masses (will be refined later)
        double mPrev = rng.logUniform(0.3, 3.0);

        while (a < rOuterAU) {
            aList.add(a);

            // Next mass guess: gently increases with distance but noisy
            double mThis = rng.logUniform(0.2, 8.0) * Math.pow(a / Math.max(0.2, rInnerAU), rng.range(-0.1, 0.3));
            mThis = Math.max(0.05, mThis);

            // Mutual Hill radius using (mPrev + mThis)
            double mTot = mPrev + mThis;
            double rHillMut = a * Math.cbrt(Math.max(1e-12, mTot) / (3.0 * Math.max(1e-12, mStarEarth)));

            // Apply spacing factor
            double delta = Math.max(0.01, kSpacing * rHillMut);

            // Add some jitter to avoid perfect regularity
            delta *= rng.range(0.90, 1.20);

            a = a + delta;
            mPrev = mThis;
        }

        return aList;
    }
}
