package org.example.starforge.core.model;

/**
 * Helpers for mixing/accreting solid composition in a stable way.
 */
public final class CompositionUtil {

    private CompositionUtil() {}

    public static void mixAddSolids(EmbryoModel e, double deltaMassEarth, double addIron, double addRock, double addIce) {
        if (e == null || deltaMassEarth <= 0) return;

        double m0 = Math.max(0.0, e.massEarth);
        double m1 = m0 + deltaMassEarth;
        if (m1 <= 0) return;

        // Normalize the added composition just in case.
        double s = addIron + addRock + addIce;
        if (!Double.isFinite(s) || s <= 0) {
            addIron = 0.30;
            addRock = 0.70;
            addIce = 0.0;
            s = 1.0;
        }
        addIron /= s;
        addRock /= s;
        addIce  /= s;

        e.fracIron = (e.fracIron * m0 + addIron * deltaMassEarth) / m1;
        e.fracRock = (e.fracRock * m0 + addRock * deltaMassEarth) / m1;
        e.fracIce  = (e.fracIce  * m0 + addIce  * deltaMassEarth) / m1;

        // Keep a simple mapping for now.
        e.waterMassFrac = e.fracIce;
        e.renormalizeComposition();
    }

    public static void mixMergeSolids(EmbryoModel big, EmbryoModel small) {
        if (big == null || small == null) return;
        double mBig = Math.max(0.0, big.massEarth);
        double mSmall = Math.max(0.0, small.massEarth);
        double mTot = mBig + mSmall;
        if (mTot <= 0) return;

        big.fracIron = (big.fracIron * mBig + small.fracIron * mSmall) / mTot;
        big.fracRock = (big.fracRock * mBig + small.fracRock * mSmall) / mTot;
        big.fracIce  = (big.fracIce  * mBig + small.fracIce  * mSmall) / mTot;

        big.waterMassFrac = (big.waterMassFrac * mBig + small.waterMassFrac * mSmall) / mTot;

        if (big.crustType != small.crustType) {
            big.crustType = CrustType.MIXED;
        }
        big.renormalizeComposition();
    }
}
