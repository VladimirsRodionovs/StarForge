package org.example.starforge.core.random;

/** Small, fast deterministic RNG (SplitMix64). Great for reproducible generation. */
public final class DeterministicRng {
    private long state;

    // Cache for Box–Muller gaussian
    private boolean hasNextGaussian = false;
    private double nextGaussianCache = 0.0;

    public DeterministicRng(long seed) { this.state = seed; }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public double nextDouble() {
        // 53-bit precision
        return (nextLong() >>> 11) * (1.0 / (1L << 53));
    }

    public double range(double min, double max) {
        return min + (max - min) * nextDouble();
    }

    public int rangeInt(int min, int maxInclusive) {
        if (maxInclusive <= min) return min;
        int span = (maxInclusive - min) + 1;
        long r = Math.floorMod(nextLong(), span);
        return min + (int) r;
    }

    public boolean chance(double p) {
        return nextDouble() < p;
    }

    public double logUniform(double min, double max) {
        double lmin = Math.log(min);
        double lmax = Math.log(max);
        return Math.exp(range(lmin, lmax));
    }

    /** Standard normal N(0,1) via Box–Muller (cached). */
    public double nextGaussian() {
        if (hasNextGaussian) {
            hasNextGaussian = false;
            return nextGaussianCache;
        }

        // Avoid log(0)
        double u1 = Math.max(1e-12, nextDouble());
        double u2 = nextDouble();

        double r = Math.sqrt(-2.0 * Math.log(u1));
        double theta = 2.0 * Math.PI * u2;

        double z0 = r * Math.cos(theta);
        double z1 = r * Math.sin(theta);

        nextGaussianCache = z1;
        hasNextGaussian = true;
        return z0;
    }

    /**
     * Log-normal multiplier with median = medianMult.
     * If sigma=0.35 then 1-sigma ~ *exp(±0.35) ≈ x[0.70..1.42]
     */
    public double logNormal(double medianMult, double sigma) {
        if (sigma <= 0) return medianMult;
        return medianMult * Math.exp(sigma * nextGaussian());
    }
}
