package org.example.starforge.core.model;

import org.example.starforge.core.model.EmbryoModel;
import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.random.DeterministicRng;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Post-gas "late-stage" consolidation for game-friendly realism.
 *
 * Rationale:
 *  - Hill-based spacing does not trigger for tiny masses (RH is extremely small),
 *    so crowded inner chains stay as many small planets.
 *  - Instead we reduce the number of bodies inside innerAU down to a random target count,
 *    merging nearest neighbors in log(a) (period-like space).
 *
 * This is a single post-process step: it only changes (aAU, massEarth) of embryos.
 */
public final class LateStageConsolidation {

    private LateStageConsolidation() {}

    /**
     * Consolidate embryos inside innerAU by reducing their count to a target in [minKeep..maxKeep].
     * Nearest-neighbor merge criterion: smallest abs(log(a2/a1)).
     *
     * @param embryos         input embryos
     * @param rng             deterministic rng
     * @param innerAU         inner zone boundary (e.g. 1.0 AU)
     * @param minKeep         minimum bodies to keep inside innerAU (>=1)
     * @param maxKeep         maximum bodies to keep inside innerAU (>=minKeep)
     * @param impactLossFrac  fraction of mass lost per giant impact [0..0.10], e.g. 0.02
     */
    public static List<EmbryoModel> consolidateInnerByCount(
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double innerAU,
            int minKeep,
            int maxKeep,
            double impactLossFrac
    ) {
        if (embryos == null || embryos.isEmpty()) return embryos;

        double loss = clamp(impactLossFrac, 0.0, 0.10);

        List<EmbryoModel> inner = new ArrayList<>();
        List<EmbryoModel> outer = new ArrayList<>();

        for (EmbryoModel e : embryos) {
            if (e.aAU <= innerAU) inner.add(e);
            else outer.add(e);
        }

        inner.sort(Comparator.comparingDouble(e -> e.aAU));

        if (inner.size() <= 1) {
            List<EmbryoModel> out = new ArrayList<>(inner);
            out.addAll(outer);
            out.sort(Comparator.comparingDouble(e -> e.aAU));
            return out;
        }

        int keep = pickKeepCount(rng, minKeep, maxKeep, inner.size());

        // Merge closest neighbors in log(a) until we have "keep"
        int safety = 0;
        while (inner.size() > keep && safety++ < 10_000) {
            inner.sort(Comparator.comparingDouble(e -> e.aAU));

            int bestI = -1;
            double bestScore = Double.POSITIVE_INFINITY;

            for (int i = 0; i < inner.size() - 1; i++) {
                double a1 = inner.get(i).aAU;
                double a2 = inner.get(i + 1).aAU;

                // safety against weird values
                if (a1 <= 0 || a2 <= 0) continue;

                double score = Math.abs(Math.log(a2 / a1)); // smaller => closer in period-like space
                if (score < bestScore) {
                    bestScore = score;
                    bestI = i;
                }
            }

            if (bestI < 0) break;

            EmbryoModel e1 = inner.get(bestI);
            EmbryoModel e2 = inner.get(bestI + 1);

            double m1 = Math.max(0.0, e1.massEarth);
            double m2 = Math.max(0.0, e2.massEarth);
            double mTot = m1 + m2;

            // If masses are degenerate, just drop one
            if (mTot <= 0) {
                inner.remove(bestI + 1);
                continue;
            }

            double newMass = mTot * (1.0 - loss);
            double newA = (e1.aAU * m1 + e2.aAU * m2) / mTot;

            // Keep the more massive body object (birthTime/gasPhase are final)
            EmbryoModel keeper = (m1 >= m2) ? e1 : e2;
            EmbryoModel removed = (keeper == e1) ? e2 : e1;

            // Mix solid composition before applying impact mass loss.
            CompositionUtil.mixMergeSolids(keeper, removed);

            keeper.massEarth = newMass;
            keeper.aAU = newA;

            inner.remove(removed);
        }

        List<EmbryoModel> out = new ArrayList<>(inner);
        out.addAll(outer);
        out.sort(Comparator.comparingDouble(e -> e.aAU));
        return out;
    }

    private static int pickKeepCount(DeterministicRng rng, int minKeep, int maxKeep, int currentInner) {
        int lo = Math.max(1, Math.min(minKeep, currentInner));
        int hi = Math.max(lo, Math.min(maxKeep, currentInner));

        // Bias toward 3–4 (game-friendly), but still allows 2 or 5
        // We implement a simple weighted choice without extra dependencies.
        List<Integer> opts = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (int k = lo; k <= hi; k++) {
            opts.add(k);
            double weight = 1.0;
            if (k == 3) weight = 3.0;
            else if (k == 4) weight = 2.2;
            else if (k == 2) weight = 1.6;
            else if (k >= 6) weight = 0.6;
            w.add(weight);
        }

        double sum = 0.0;
        for (double x : w) sum += x;
        double r = rng.nextDouble() * sum;

        for (int i = 0; i < opts.size(); i++) {
            r -= w.get(i);
            if (r <= 0) return opts.get(i);
        }
        return opts.get(opts.size() - 1);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Remove/merge the "persistent tiny innermost" body that often appears due to embryo start near inner edge.
     *
     * Rule (game-friendly realism):
     *  - define aCut = aCutCoeff * sqrt(L) AU
     *  - if innermost has a < aCut and mass < massTiny -> usually merge it into the next one
     *  - hot super-Earths / hot Jupiters are unaffected because they exceed massTiny
     */
    
    /**
     * Consolidate bodies within [aMinAU, aMaxAU] into a smaller number of survivors by stochastic merging.
     * Used for a second-stage outer-system growth without disturbing inner-system tuning.
     */
    
    /**
     * Consolidate bodies within [aMinAU, aMaxAU] into a smaller number of survivors by nearest-neighbor merging.
     * Used for a second-stage outer-system growth without disturbing inner-system tuning.
     */
    public static List<EmbryoModel> consolidateRangeByCount(
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double aMinAU,
            double aMaxAU,
            int minKeep,
            int maxKeep,
            double impactLossFrac
    ) {
        if (embryos == null || embryos.isEmpty()) return embryos;

        double loss = clamp(impactLossFrac, 0.0, 0.10);
        double amin = Math.min(aMinAU, aMaxAU);
        double amax = Math.max(aMinAU, aMaxAU);

        List<EmbryoModel> inRange = new ArrayList<>();
        List<EmbryoModel> outRange = new ArrayList<>();

        for (EmbryoModel e : embryos) {
            if (e.aAU >= amin && e.aAU <= amax) inRange.add(e);
            else outRange.add(e);
        }

        inRange.sort(Comparator.comparingDouble(e -> e.aAU));

        if (inRange.size() <= Math.max(1, minKeep)) {
            List<EmbryoModel> out = new ArrayList<>(outRange);
            out.addAll(inRange);
            out.sort(Comparator.comparingDouble(e -> e.aAU));
            return out;
        }

        int keep = clampInt(rng.rangeInt(minKeep, Math.max(minKeep, maxKeep)), minKeep, maxKeep);

        int safety = 0;
        while (inRange.size() > keep && safety++ < 10_000) {
            inRange.sort(Comparator.comparingDouble(e -> e.aAU));

            int bestI = -1;
            double bestScore = Double.POSITIVE_INFINITY;

            for (int i = 0; i < inRange.size() - 1; i++) {
                double a1 = inRange.get(i).aAU;
                double a2 = inRange.get(i + 1).aAU;
                if (a1 <= 0 || a2 <= 0) continue;

                double score = Math.abs(Math.log(a2 / a1));
                if (score < bestScore) {
                    bestScore = score;
                    bestI = i;
                }
            }

            if (bestI < 0) break;

            EmbryoModel e1 = inRange.get(bestI);
            EmbryoModel e2 = inRange.get(bestI + 1);

            double m1 = Math.max(0.0, e1.massEarth);
            double m2 = Math.max(0.0, e2.massEarth);
            double mTot = m1 + m2;

            if (mTot <= 0) {
                inRange.remove(bestI + 1);
                continue;
            }

            double newMass = mTot * (1.0 - loss);
            double newA = (e1.aAU * m1 + e2.aAU * m2) / mTot;

            EmbryoModel keeper = (m1 >= m2) ? e1 : e2;
            EmbryoModel removed = (keeper == e1) ? e2 : e1;

            CompositionUtil.mixMergeSolids(keeper, removed);

            keeper.massEarth = newMass;
            keeper.coreMassEarth = newMass;
            keeper.aAU = newA;

            inRange.remove(removed);
        }

        List<EmbryoModel> out = new ArrayList<>(outRange);
        out.addAll(inRange);
        out.sort(Comparator.comparingDouble(e -> e.aAU));
        return out;
    }

public static List<EmbryoModel> cleanupTinyInnermost(
            StarModel star,
            List<EmbryoModel> embryos,
            DeterministicRng rng,
            double aCutCoeff,
            double massTiny,
            double mergeProb,
            double impactLossFrac
    ) {
        if (embryos == null || embryos.size() < 2) return embryos;

        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        double L = Math.max(1e-6, star.lumSolar());
        double aCut = aCutCoeff * Math.sqrt(L);

        EmbryoModel first = embryos.get(0);
        if (!(first.aAU < aCut && first.massEarth < massTiny)) {
            return embryos;
        }

        // allow rare survival for variety
        if (!rng.chance(clamp(mergeProb, 0.0, 1.0))) {
            return embryos;
        }

        EmbryoModel second = embryos.get(1);

        double loss = clamp(impactLossFrac, 0.0, 0.10);

        double m1 = Math.max(0.0, first.massEarth);
        double m2 = Math.max(0.0, second.massEarth);
        double mTot = m1 + m2;

        if (mTot <= 0) {
            // just drop the first
            embryos.remove(0);
            return embryos;
        }

        double newMass = mTot * (1.0 - loss);
        double newA = (first.aAU * m1 + second.aAU * m2) / mTot;

        // We must keep an existing object because birthTime/gasPhase are final.
        // Put merged mass into the second, remove the first.
        CompositionUtil.mixMergeSolids(second, first);
        second.massEarth = newMass;
        second.aAU = newA;

        embryos.remove(0);
        embryos.sort(Comparator.comparingDouble(e -> e.aAU));
        return embryos;
    }



    /**
     * Enforce a minimum mutual-Hill spacing by merging neighbors that are too close.
     *
     * This fixes "two planets almost on the same orbit" artifacts and, as a side effect,
     * promotes growth of a few dominant cores in the outer system (helping gas-giant formation).
     *
     * @param kHill           required separation in mutual Hill radii (typical 6..12)
     * @param impactLossFrac  fractional mass loss per merge (0..0.10)
     * @param mergeEnvelopes  if true, envelope masses are merged too (used after gas accretion)
     */
    public static List<EmbryoModel> enforceMutualHillSpacing(
            List<EmbryoModel> embryos,
            StarModel star,
            DeterministicRng rng,
            double kHill,
            double impactLossFrac,
            boolean mergeEnvelopes
    ) {
        if (embryos == null || embryos.size() < 2 || star == null) return embryos;

        // Empirical long-term stability for near-circular multi-planet systems is typically
        // ~10–12 mutual Hill radii. We let the caller provide kHill, but also allow a stronger
        // separation requirement when eccentricities are non-trivial.
        double k = Math.max(0.0, kHill);
        double loss = clamp(impactLossFrac, 0.0, 0.10);

        embryos.sort(Comparator.comparingDouble(e -> e.aAU));

        int safety = 0;
        boolean changed = true;
        while (changed && safety++ < 50_000) {
            changed = false;
            embryos.sort(Comparator.comparingDouble(e -> e.aAU));

            for (int i = 0; i < embryos.size() - 1; i++) {
                EmbryoModel e1 = embryos.get(i);
                EmbryoModel e2 = embryos.get(i + 1);
                if (e1 == null || e2 == null) continue;

                double a1 = Math.max(1e-6, e1.aAU);
                double a2 = Math.max(1e-6, e2.aAU);
                if (a2 <= a1) continue;

                // Embryos do not carry eccentricity; enforce spacing using mutual Hill + design-friendly floors.
                double m1 = Math.max(0.0, e1.massEarth);
                double m2 = Math.max(0.0, e2.massEarth);
                double mTot = m1 + m2;

                double aMid = 0.5 * (a1 + a2);
                double da = (a2 - a1);
                double logSep = Math.abs(Math.log(a2 / a1));

                // Convert Earth masses to solar masses (1 Msun ~= 332,946 Mearth).
                double mSolar = mTot / 332_946.0;
                double mu = mSolar / (3.0 * Math.max(0.08, star.massSolar()));
                double rH = aMid * Math.cbrt(Math.max(0.0, mu));

                double kEff = Math.max(0.0, k);

                // For tiny masses the Hill test can be too permissive (RH ~ 0):
                // add an absolute spacing floor + a log-spacing floor.
                double fracFloor = 0.06 * aMid;              // 6% of a
                double logFloor = (aMid < 2.0) ? 0.20 : 0.12; // e^0.20≈1.22, e^0.12≈1.13

                double threshold = (rH > 1e-12)
                        ? Math.max(kEff * rH, fracFloor)
                        : Math.max(0.03 * aMid, fracFloor);

                boolean tooClose = (da < threshold) || (logSep < logFloor);

                if (tooClose) {
                    // Merge the pair.
                    EmbryoModel keeper = (m1 >= m2) ? e1 : e2;
                    EmbryoModel removed = (keeper == e1) ? e2 : e1;

                    double newMass = mTot * (1.0 - loss);
                    double newA = (a1 * m1 + a2 * m2) / Math.max(1e-12, mTot);

                    // Mix solids composition
                    CompositionUtil.mixMergeSolids(keeper, removed);

                    // Merge envelopes if requested (post-gas pass)
                    if (mergeEnvelopes) {
                        double c1 = Math.max(0.0, keeper.coreMassEarth > 0 ? keeper.coreMassEarth : keeper.massEarth);
                        double c2 = Math.max(0.0, removed.coreMassEarth > 0 ? removed.coreMassEarth : removed.massEarth);
                        double eEnv1 = Math.max(0.0, keeper.envelopeMassEarth);
                        double eEnv2 = Math.max(0.0, removed.envelopeMassEarth);

                        double newCore = (c1 + c2) * (1.0 - loss);
                        double newEnv = (eEnv1 + eEnv2) * (1.0 - loss);

                        keeper.coreMassEarth = newCore;
                        keeper.envelopeMassEarth = newEnv;

                        // crude envelope Z mixing by envelope mass
                        double z1 = keeper.envelopeZ;
                        double z2 = removed.envelopeZ;
                        double z = (newEnv > 0)
                                ? ((z1 * eEnv1 + z2 * eEnv2) / Math.max(1e-12, (eEnv1 + eEnv2)))
                                : 0.0;
                        keeper.envelopeZ = z;
                    } else {
                        // pre-gas: keep a pure-solid core view
                        keeper.coreMassEarth = newMass;
                        keeper.envelopeMassEarth = 0.0;
                        keeper.envelopeZ = 0.0;
                    }

                    keeper.massEarth = newMass;
                    keeper.aAU = newA;

                    embryos.remove(removed);
                    changed = true;
                    break; // restart scanning after a merge
                }
            }
        }

        embryos.sort(Comparator.comparingDouble(e -> e.aAU));
        return embryos;
    }


}
