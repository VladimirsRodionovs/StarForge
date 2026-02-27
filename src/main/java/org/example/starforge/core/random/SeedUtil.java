// starforge-core/src/main/java/com/yourgame/starforge/core/random/SeedUtil.java
package org.example.starforge.core.random;

public final class SeedUtil {
    private SeedUtil() {}

    /** Mix two longs into one deterministic seed (SplitMix64 mix). */
    public static long mix(long a, long b) {
        long z = a + 0x9E3779B97F4A7C15L + (b << 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long starSeed(long globalSeed, long starId) {
        return mix(globalSeed, starId);
    }

    public static long systemSeed(long globalSeed, String base) {
        long h = base == null ? 0 : base.hashCode();
        return mix(globalSeed, h);
    }
}
