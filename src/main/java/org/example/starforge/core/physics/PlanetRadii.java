package org.example.starforge.core.physics;

/**
 * Game-friendly mass-radius relations.
 *
 * IMPORTANT: This is not a full interior structure model. It's a stable
 * approximation that allows different bulk compositions (iron/rock/ice)
 * to influence radius.
 */
public final class PlanetRadii {

    private PlanetRadii() {}

    /**
     * Solid planet radius in Earth radii from mass (Earth masses) and bulk fractions.
     */
    public static double solidRadiusEarth(double massEarth, double fracIron, double fracRock, double fracIce) {
        double m = Math.max(1e-6, massEarth);

        // Normalize fractions defensively
        if (!Double.isFinite(fracIron)) fracIron = 0;
        if (!Double.isFinite(fracRock)) fracRock = 0;
        if (!Double.isFinite(fracIce)) fracIce = 0;
        fracIron = Math.max(0, fracIron);
        fracRock = Math.max(0, fracRock);
        fracIce = Math.max(0, fracIce);
        double s = fracIron + fracRock + fracIce;
        if (s <= 0) { fracIron = 0.30; fracRock = 0.70; fracIce = 0.0; s = 1.0; }
        fracIron /= s;
        fracRock /= s;
        fracIce  /= s;

        // Three anchor curves (rough, but monotonic and tuneable):
        //  - iron-rich: smaller radius
        //  - rocky:     baseline (similar to current M^0.27)
        //  - ice-rich:  larger radius
        double rIron = 0.80 * Math.pow(m, 0.29);
        double rRock = 1.00 * Math.pow(m, 0.27);
        double rIce  = 1.26 * Math.pow(m, 0.27);

        double r = fracIron * rIron + fracRock * rRock + fracIce * rIce;

        // Keep sane bounds for tiny bodies.
        if (!Double.isFinite(r) || r <= 0) r = Math.pow(m, 0.27);
        return Math.max(0.05, r);
    }
}
