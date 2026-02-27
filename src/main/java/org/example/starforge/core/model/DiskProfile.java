package org.example.starforge.core.model;

public class DiskProfile {

    public final double sigmaGas0;
    public final double sigmaSolid0;
    public final double pGas;
    public final double pSolid;

    public DiskProfile(double sigmaGas0, double sigmaSolid0, double pGas, double pSolid) {
        this.sigmaGas0 = sigmaGas0;
        this.sigmaSolid0 = sigmaSolid0;
        this.pGas = pGas;
        this.pSolid = pSolid;
    }

    public double sigmaGas(double rAU) {
        return sigmaGas0 * Math.pow(rAU, -pGas);
    }

    public double sigmaSolid(double rAU) {
        return sigmaSolid0 * Math.pow(rAU, -pSolid);
    }
}
