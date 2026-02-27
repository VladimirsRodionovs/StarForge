package org.example.starforge.core.model;

/**
 * Protoplanetary disk model.
 * rOuterAU: physical disk / reservoir extent (Kuiper, Oort, etc)
 * rSolidsOuterAU: radius up to which solids actively form planets
 */
public final class DiskModel {

    public final double diskMassFrac;
    public final double metallicityZ;

    public final double mDiskEarth;
    public final double mSolidsEarth;

    public final double mGasEarth;
    public final double mGasInnerEarth;
    public final double mGasOuterEarth;

    public final double rInnerAU;
    public final double rSolidsOuterAU;
    public final double rOuterAU;

    public final double pSigma;
    public final double iceBoost;
    public final double kSpacing;

    public DiskModel(
            double diskMassFrac,
            double metallicityZ,
            double mDiskEarth,
            double mSolidsEarth,
            double mGasEarth,
            double mGasInnerEarth,
            double mGasOuterEarth,
            double rInnerAU,
            double rSolidsOuterAU,
            double rOuterAU,
            double pSigma,
            double iceBoost,
            double kSpacing
    ) {
        this.diskMassFrac = diskMassFrac;
        this.metallicityZ = metallicityZ;
        this.mDiskEarth = mDiskEarth;
        this.mSolidsEarth = mSolidsEarth;
        this.mGasEarth = mGasEarth;
        this.mGasInnerEarth = mGasInnerEarth;
        this.mGasOuterEarth = mGasOuterEarth;
        this.rInnerAU = rInnerAU;
        this.rSolidsOuterAU = rSolidsOuterAU;
        this.rOuterAU = rOuterAU;
        this.pSigma = pSigma;
        this.iceBoost = iceBoost;
        this.kSpacing = kSpacing;
    }

    /**
     * Create a derived disk view for a sub-range of the solids reservoir.
     * Used for multi-stage accretion (e.g. separate outer-system stage) without disturbing the inner stage tuning.
     *
     * Note: gas budgets are kept from the parent disk (outer-stage gas accretion is handled separately).
     */
    public DiskModel withSolidsSubset(double newInnerAU, double newSolidsOuterAU, double newSolidsEarth, double newKSpacing) {
        return new DiskModel(
                this.diskMassFrac,
                this.metallicityZ,
                this.mDiskEarth,
                newSolidsEarth,
                this.mGasEarth,
                this.mGasInnerEarth,
                this.mGasOuterEarth,
                newInnerAU,
                newSolidsOuterAU,
                this.rOuterAU,
                this.pSigma,
                this.iceBoost,
                newKSpacing
        );
    }

    public DiskModel withOuterCaps(double newSolidsOuterAU, double newOuterAU) {
        return new DiskModel(
                this.diskMassFrac,
                this.metallicityZ,
                this.mDiskEarth,
                this.mSolidsEarth,
                this.mGasEarth,
                this.mGasInnerEarth,
                this.mGasOuterEarth,
                this.rInnerAU,
                newSolidsOuterAU,
                newOuterAU,
                this.pSigma,
                this.iceBoost,
                this.kSpacing
        );
    }

}
