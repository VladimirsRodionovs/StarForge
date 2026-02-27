package org.example.starforge.core.physics;

public final class Units {
    private Units() {}

    public static final double AU_PER_PC = 206264.806; // 1 pc in AU
    public static final double KM_PER_AU = 149_597_870.7;

    public static final double M_EARTH_PER_M_SUN = 332_946.0487;
    public static final double M_JUPITER_IN_M_EARTH = 317.828;

    public static final double R_EARTH_KM = 6371.0;
    public static final double M_EARTH_KG = 5.9722e24;
    public static final double R_EARTH_M  = 6_371_000.0;
    public static final double BAR_PA     = 1.0e5;

    // (опционально, пригодится позже)
    public static final double G_SI = 6.67430e-11;


    /**
     * Kepler's third law:
     * P^2 = a^3 / M
     * P in years, a in AU, M in solar masses
     */
    public static double orbitalPeriodDays(double aAU, double mStarSolar) {
        double pYears = Math.sqrt((aAU * aAU * aAU) / Math.max(mStarSolar, 1e-12));
        return pYears * 365.25;
    }
}
