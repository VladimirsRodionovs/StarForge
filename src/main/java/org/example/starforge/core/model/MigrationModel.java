package org.example.starforge.core.model;

import org.example.starforge.core.model.*;

public class MigrationModel {

    public void migrate(
            EmbryoModel e,
            DiskProfile profile,
            double starMassSolar,
            double rInnerTrapAU
    ) {
        if (!e.gasPhase) return;

        double sigmaGas = profile.sigmaGas(e.aAU);
        double m = e.massEarth;

        double tau = (starMassSolar / m) / (sigmaGas * e.aAU * e.aAU);
        tau *= 0.4; // умеренная миграция

        double da = -e.aAU / tau;

        e.aAU += da;
        if (e.aAU < rInnerTrapAU) {
            e.aAU = rInnerTrapAU;
        }
    }
}
