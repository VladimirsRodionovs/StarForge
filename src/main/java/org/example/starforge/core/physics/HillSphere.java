// starforge-core/src/main/java/com/yourgame/starforge/core/physics/HillSphere.java
package org.example.starforge.core.physics;

public final class HillSphere {
    private HillSphere() {}

    /** Hill radius in AU. Mp in Earth masses, Mstar in Solar masses. */
    public static double hillRadiusAU(double aPlanetAU, double mPlanetEarth, double mStarSolar) {
        double mpSolar = mPlanetEarth / Units.M_EARTH_PER_M_SUN;
        double denom = 3.0 * Math.max(mStarSolar, 1e-9);
        return aPlanetAU * Math.cbrt(mpSolar / denom);
    }
}
