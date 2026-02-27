package org.example.starforge.core.model;

/**
 * A single protoplanetary embryo / core used by the accretion simulator.
 * Kept as a mutable POJO because multiple models update its mass and orbit.
 *
 * Units:
 *  - aAU: semi-major axis in AU
 *  - massEarth: mass in Earth masses
 *  - birthTime: Myr (million years)
 *  - gasPhase: true if the embryo formed before gas dispersal (can grow/migrate in gas)
 */
public class EmbryoModel {

    /** Semi-major axis (AU). */
    public double aAU;

    /** Mass (Earth masses). */
    public double massEarth;

    /** Solid (core) mass (Earth masses). For gas/ice giants, total mass = core + envelope. */
    public double coreMassEarth;

    /** H/He envelope mass (Earth masses). */
    public double envelopeMassEarth;

    /** Envelope metallicity proxy (0..1). */
    public double envelopeZ;

    /** Formation time (Myr). */
    public final double birthTime;

    /** Born before gas dispersal. */
    public final boolean gasPhase;

    /* ------------------------------------------------------------
       BULK COMPOSITION (SOLIDS)
       ------------------------------------------------------------
       Mass fractions (0..1) for the *solid* planet. These are used
       to compute a more realistic radius than a single mass→radius
       curve.

       For now we track:
         - iron:    metallic core fraction
         - rock:    silicate mantle+crust fraction
         - ice:     H2O/volatile ices bound in solids ("water inventory")

       NOTE: a future gas-phase model can add H/He envelopes separately.
     */
    public double fracIron = 0.30;
    public double fracRock = 0.70;
    public double fracIce  = 0.00;

    /** Water mass fraction carried by solids (currently == fracIce). */
    public double waterMassFrac = 0.00;

    /** Crust type tag (mostly for resources/events; small effect on radius). */
    public CrustType crustType = CrustType.BASALTIC;

    public EmbryoModel(double aAU, double massEarth, double birthTime, boolean gasPhase) {
        this.aAU = aAU;
        this.massEarth = massEarth;
        this.coreMassEarth = massEarth;
        this.envelopeMassEarth = 0.0;
        this.envelopeZ = 0.0;
        this.birthTime = birthTime;
        this.gasPhase = gasPhase;
    }

    /** Ensures fractions are finite and sum to 1 (within reason). */
    public void renormalizeComposition() {
        if (!Double.isFinite(fracIron)) fracIron = 0.0;
        if (!Double.isFinite(fracRock)) fracRock = 0.0;
        if (!Double.isFinite(fracIce)) fracIce = 0.0;

        fracIron = Math.max(0.0, fracIron);
        fracRock = Math.max(0.0, fracRock);
        fracIce  = Math.max(0.0, fracIce);

        double s = fracIron + fracRock + fracIce;
        if (s <= 0) {
            fracIron = 0.30;
            fracRock = 0.70;
            fracIce = 0.0;
            s = 1.0;
        }
        fracIron /= s;
        fracRock /= s;
        fracIce  /= s;

        waterMassFrac = Math.max(0.0, Math.min(1.0, waterMassFrac));
        // In the current model, "water" is carried as ice fraction.
        waterMassFrac = fracIce;
    }
}
