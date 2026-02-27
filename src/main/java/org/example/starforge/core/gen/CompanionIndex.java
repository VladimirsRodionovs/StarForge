package org.example.starforge.core.gen;

import org.example.starforge.core.model.StarRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes nearest-companion separation (by shared base) and exposes a stability cap.
 * Cap rule: 0.3 * minSeparationAU
 */
public final class CompanionIndex {

    private static final double PC_TO_AU = 206_265.0;
    private static final double CAP_FACTOR = 0.3;

    private final Map<Long, Double> idToCapAU;

    private CompanionIndex(Map<Long, Double> idToCapAU) {
        this.idToCapAU = idToCapAU;
    }

    public static CompanionIndex build(List<StarRecord> stars) {
        Map<String, List<StarRecord>> byBase = new HashMap<>();
        for (StarRecord r : stars) {
            if (r == null) continue;
            String base = r.base();
            if (base == null || base.isBlank()) continue;
            byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(r);
        }

        Map<Long, Double> out = new HashMap<>();
        for (List<StarRecord> group : byBase.values()) {
            if (group.size() < 2) continue;
            int n = group.size();

            for (int i = 0; i < n; i++) {
                StarRecord a = group.get(i);
                if (!finite(a.xPc(), a.yPc(), a.zPc())) continue;
                double minSepPc = Double.POSITIVE_INFINITY;

                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    StarRecord b = group.get(j);
                    if (!finite(b.xPc(), b.yPc(), b.zPc())) continue;
                    double dx = a.xPc() - b.xPc();
                    double dy = a.yPc() - b.yPc();
                    double dz = a.zPc() - b.zPc();
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d < minSepPc) minSepPc = d;
                }

                if (Double.isFinite(minSepPc)) {
                    double capAU = CAP_FACTOR * (minSepPc * PC_TO_AU);
                    if (capAU > 0) out.put(a.id(), capAU);
                }
            }
        }

        return new CompanionIndex(out);
    }

    /** @return stability cap in AU, or NaN if unknown / not multiple. */
    public double capAU(StarRecord r) {
        if (r == null) return Double.NaN;
        Double v = idToCapAU.get(r.id());
        return v != null ? v : Double.NaN;
    }

    private static boolean finite(double... v) {
        for (double x : v) {
            if (!Double.isFinite(x)) return false;
        }
        return true;
    }
}
