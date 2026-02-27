// starforge-core/src/main/java/com/yourgame/starforge/core/gen/StarParamEstimator.java
package org.example.starforge.core.gen;

import org.example.starforge.core.model.StarModel;
import org.example.starforge.core.model.StarRecord;

public final class StarParamEstimator {

    public StarModel estimate(StarRecord r) {
        double lum = Double.isFinite(r.lumSolar()) ? r.lumSolar() : 1.0;

        // Very first-pass Teff estimate: from spectral class rough; fallback to Sun.
        double teff = estimateTeff(r.spect(), r.ci());

        // Mass from crude L~M^4 for MS; clamp.
        double mass = Math.pow(Math.max(lum, 1e-6), 1.0 / 4.0);
        mass = clamp(mass, 0.08, 5.0);

        // Radius from Stefan-Boltzmann relative to Sun.
        double tSun = 5772.0;
        double radius = Math.sqrt(lum) * Math.pow(tSun / teff, 2.0);
        radius = clamp(radius, 0.08, 20.0);

        String stage = r.spect() != null && r.spect().startsWith("DA") ? "white_dwarf" : "main_sequence";

        // Optimistic HZ and snow line
        double hzCenter = Math.sqrt(lum);      // AU
        double hzInner = 0.75 * hzCenter;
        double hzOuter = 1.80 * hzCenter;
        double snow = 2.7 * hzCenter;

        String activity = estimateActivity(r.spect());

        return new StarModel(r, teff, lum, mass, radius, stage, activity, hzInner, hzOuter, snow);
    }

    private static double estimateTeff(String spect, double ci) {
        if (spect == null) spect = "";
        String s = spect.trim().toUpperCase();

        // Rough mapping (good enough for preview; refine later).
        double teff;
        if (s.startsWith("M")) teff = 3200;
        else if (s.startsWith("K")) teff = 5100;
        else if (s.startsWith("G")) teff = 5770;
        else if (s.startsWith("F")) teff = 6500;
        else if (s.startsWith("A")) teff = 9000;
        else if (s.startsWith("DA")) teff = 12000;
        else teff = 5770;

        // Optional small correction using B-V if present.
        if (Double.isFinite(ci)) {
            // crude correction: bluer -> hotter, redder -> cooler
            teff *= clamp(1.1 - 0.25 * (ci - 0.65), 0.7, 1.3);
        }
        return clamp(teff, 2500, 40000);
    }

    private static String estimateActivity(String spect) {
        if (spect == null) return "MEDIUM";
        String s = spect.toLowerCase();
        if (s.contains("e")) return "HIGH";
        if (s.startsWith("m")) return "MEDIUM";
        return "LOW";
    }

    private static double clamp(double v, double a, double b) {
        return Math.max(a, Math.min(b, v));
    }
}
