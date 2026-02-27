package org.example.starforge.core.model;

import org.example.starforge.core.model.*;

public class GrowthModel {

    public void growEmbryo(
            EmbryoModel e,
            DiskProfile profile,
            double tGas
    ) {
        if (!e.gasPhase) return;

        // runaway + oligarchic saturation
        double sigma = profile.sigmaSolid(e.aAU);

        double m = e.massEarth;
        double dt = tGas - e.birthTime;

        // runaway growth (аналитически)
        double runawayFactor = 1.0 + sigma * dt;
        m *= runawayFactor * runawayFactor;

        // saturation (олигархический предел)
        double mIso = isolationMass(e.aAU, sigma);
        if (m > mIso) m = mIso;

        e.massEarth = m;
    }

    private double isolationMass(double rAU, double sigma) {
        // грубая, но рабочая оценка
        return 0.16 * Math.pow(sigma, 1.5) * Math.pow(rAU, 0.75);
    }
}
