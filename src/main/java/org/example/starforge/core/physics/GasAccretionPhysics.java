package org.example.starforge.core.physics;

import org.example.starforge.core.model.DiskModel;
import org.example.starforge.core.model.EmbryoModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.random.DeterministicRng;

import java.util.Comparator;
import java.util.List;

/**
 * v1 gas accretion model: allocates H/He envelopes from a finite disk gas budget.
 *
 * Inventory-style: gas is a limited resource and is consumed by accreting cores.
 * This is a game-friendly heuristic model (not a full thermodynamic structure solver).
 */
public final class GasAccretionPhysics {

    private GasAccretionPhysics() {}

    public record GasBudgetRemaining(double innerEarth, double outerEarth) {}

    /**
     * Mutates embryos: sets coreMassEarth / envelopeMassEarth / envelopeZ and increases total massEarth.
     * Returns remaining inner/outer gas budgets in Earth masses.
     */
    public static GasBudgetRemaining apply(
            StarModel star,
            DiskModel disk,
            List<EmbryoModel> embryos,
            DeterministicRng rng
    ) {
        if (embryos == null || embryos.isEmpty()) {
            return new GasBudgetRemaining(disk.mGasInnerEarth, disk.mGasOuterEarth);
        }

        double snow = Math.max(disk.rInnerAU + 0.05, star.snowLineAU());

        // Consume outer gas first: in core-accretion scenarios, giant cores typically form beyond the snow line.
        embryos.sort(Comparator.comparingDouble((EmbryoModel e) -> e.aAU).reversed());

        double gasInner = Math.max(0.0, disk.mGasInnerEarth);
        double gasOuter = Math.max(0.0, disk.mGasOuterEarth);

        for (EmbryoModel e : embryos) {
            if (e == null) continue;

            // Work with a stable "solid core" view.
            double core = Math.max(0.0, e.coreMassEarth > 0 ? e.coreMassEarth : e.massEarth);
            if (core <= 0) continue;

            // Only cores above ~1.5 M⊕ can hold meaningful H/He in this simplified model.
            if (core < 1.5) {
                e.coreMassEarth = core;
                e.envelopeMassEarth = 0.0;
                e.envelopeZ = 0.0;
                e.massEarth = core;
                continue;
            }

            double a = Math.max(0.02, e.aAU);
            double teq = 278.0 * Math.pow(Math.max(1e-6, star.lumSolar()), 0.25) / Math.sqrt(a);

            boolean inner = a < snow;
            double retention;
            if (inner) {
                // Inner system: strong irradiation strips envelopes; allow rare survivors.
                double x = a / snow;
                retention = clamp(0.06 + 0.30 * Math.pow(x, 1.6), 0.05, 0.35);
                // Hotter = worse
                retention *= clamp(900.0 / Math.max(200.0, teq), 0.25, 1.0);
            } else {
                retention = 1.0;
                retention *= clamp(700.0 / Math.max(250.0, teq), 0.55, 1.0);
            }

            // Decide desired envelope fraction (of total mass) from core mass.
            double desiredEnvFrac;

            // "Coldness" proxy: 0 at snow line, ~1 by ~4× snow line.
            double cold01 = clamp((e.aAU / Math.max(0.10, snow) - 1.0) / 3.0, 0.0, 1.0);

            // Runaway threshold is easier in the cold outer disk.
            // Make runaway slightly easier in the cold outer disk so true gas giants appear more often.
            // Near snow line: ~11.5 Mearth, far out: ~8.0–9.0 Mearth.
            double runawayCore = inner ? 18.0 : (11.5 - 3.5 * cold01);
            runawayCore = clamp(runawayCore, 8.5, 18.0);

            if (core < 3.0) {
                desiredEnvFrac = rng.range(0.005, 0.03);
            } else if (core < 5.0) {
                desiredEnvFrac = rng.range(0.01, 0.08);
            } else if (core < 10.0) {
                desiredEnvFrac = rng.range(0.03, 0.25);
            } else if (core < runawayCore) {
                // Neptune / sub-Neptune: intentionally wide variety
                if (rng.chance(0.30)) desiredEnvFrac = rng.range(0.04, 0.18);
                else if (rng.chance(0.18)) desiredEnvFrac = rng.range(0.45, 0.80); // puffy / transition objects
                else desiredEnvFrac = rng.range(0.12, 0.55);
            } else {
                // Runaway candidate (true gas giant)
                double pRun = inner ? 0.03 : (0.45 + 0.40 * cold01); // 0.45..0.85 in outer disk
                if (rng.chance(pRun)) desiredEnvFrac = rng.range(0.85, 0.995);
                else desiredEnvFrac = rng.range(0.15, 0.65);
            }

// Convert fraction-of-total to envelope mass (cap for stability).
            desiredEnvFrac = clamp(desiredEnvFrac, 0.0, 0.995);
            double desiredEnvMass = (desiredEnvFrac <= 0.0) ? 0.0 : core * (desiredEnvFrac / (1.0 - desiredEnvFrac));
            desiredEnvMass *= retention;

            // Hard caps to keep outputs sane.
            desiredEnvMass = Math.min(desiredEnvMass, 600.0);
            if (desiredEnvMass < 0.02) desiredEnvMass = 0.0;

            // Take from the correct budget pool.
            double take = 0.0;
            if (desiredEnvMass > 0.0) {
                if (inner) {
                    take = Math.min(desiredEnvMass, gasInner);
                    gasInner -= take;
                } else {
                    take = Math.min(desiredEnvMass, gasOuter);
                    gasOuter -= take;
                }
            }

            e.coreMassEarth = core;
            e.envelopeMassEarth = take;
            e.massEarth = core + take;

            double envFrac = (e.massEarth > 0) ? (e.envelopeMassEarth / e.massEarth) : 0.0;

            // Envelope metallicity proxy (higher for ice giants / sub-neptunes).
            if (take <= 0.0) {
                e.envelopeZ = 0.0;
            } else if (envFrac < 0.35) {
                e.envelopeZ = rng.range(0.10, 0.35);
            } else if (envFrac < 0.70) {
                e.envelopeZ = rng.range(0.06, 0.18);
            } else {
                e.envelopeZ = rng.range(0.02, 0.08);
            }
        }

        // Restore ordering by semi-major axis for downstream logic.
        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        return new GasBudgetRemaining(gasInner, gasOuter);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
