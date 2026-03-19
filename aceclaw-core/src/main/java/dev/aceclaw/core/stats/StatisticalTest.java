package dev.aceclaw.core.stats;

/**
 * Statistical significance testing utilities for the continuous-learning benchmark.
 *
 * <p>Provides confidence intervals, proportion tests, and chi-square tests
 * needed to distinguish real learning gains from random variance.
 */
public final class StatisticalTest {

    private StatisticalTest() {}

    /**
     * Wilson score confidence interval for a proportion.
     * More accurate than the normal approximation for small samples.
     *
     * @param successes number of successes
     * @param total     total number of trials
     * @param z         z-score for confidence level (1.96 for 95%)
     * @return [lower, upper] bounds
     */
    public static double[] wilsonInterval(int successes, int total, double z) {
        if (total <= 0) return new double[]{0.0, 0.0};
        double p = (double) successes / total;
        double zz = z * z;
        double denom = 1.0 + zz / total;
        double center = (p + zz / (2.0 * total)) / denom;
        double spread = z * Math.sqrt((p * (1 - p) + zz / (4.0 * total)) / total) / denom;
        return new double[]{
                Math.max(0.0, center - spread),
                Math.min(1.0, center + spread)
        };
    }

    /**
     * Wilson score 95% confidence interval for a proportion.
     */
    public static double[] confidenceInterval95(int successes, int total) {
        return wilsonInterval(successes, total, 1.96);
    }

    /**
     * Two-proportion z-test for comparing two success rates.
     * Tests H0: p1 = p2, H1: p1 != p2 (two-sided).
     *
     * @param s1 successes in group 1
     * @param n1 total in group 1
     * @param s2 successes in group 2
     * @param n2 total in group 2
     * @return approximate two-sided p-value
     */
    public static double twoProportionZTest(int s1, int n1, int s2, int n2) {
        if (n1 <= 0 || n2 <= 0) return 1.0;
        double p1 = (double) s1 / n1;
        double p2 = (double) s2 / n2;
        double pooled = (double) (s1 + s2) / (n1 + n2);
        if (pooled <= 0.0 || pooled >= 1.0) return 1.0;
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        if (se <= 0.0) return 1.0;
        double z = Math.abs(p1 - p2) / se;
        return 2.0 * normalCdfComplement(z);
    }

    /**
     * Chi-square goodness-of-fit test.
     * Tests whether observed frequencies differ from expected frequencies.
     *
     * @param observed observed frequency counts
     * @param expected expected frequency counts
     * @return approximate p-value (using chi-square distribution with k-1 df)
     */
    public static double chiSquareTest(int[] observed, double[] expected) {
        if (observed == null || expected == null || observed.length != expected.length) {
            return 1.0;
        }
        int k = observed.length;
        if (k <= 1) return 1.0;

        double chiSq = 0.0;
        for (int i = 0; i < k; i++) {
            if (expected[i] <= 0) continue;
            double diff = observed[i] - expected[i];
            chiSq += (diff * diff) / expected[i];
        }
        int df = k - 1;
        return chiSquareSurvival(chiSq, df);
    }

    /**
     * Fisher's exact test for a 2x2 contingency table.
     * Tests independence of two binary variables.
     *
     * @param a cell (1,1)
     * @param b cell (1,2)
     * @param c cell (2,1)
     * @param d cell (2,2)
     * @return two-sided p-value
     */
    public static double fisherExactTest(int a, int b, int c, int d) {
        int n = a + b + c + d;
        if (n <= 0) return 1.0;

        double pCutoff = hypergeometricPmf(a, a + b, c + d, a + c, n);
        double pValue = 0.0;
        int minA = Math.max(0, (a + c) - (c + d));
        int maxA = Math.min(a + b, a + c);
        for (int i = minA; i <= maxA; i++) {
            double p = hypergeometricPmf(i, a + b, c + d, a + c, n);
            if (p <= pCutoff + 1e-12) {
                pValue += p;
            }
        }
        return Math.min(1.0, pValue);
    }

    /**
     * Checks if a result is statistically significant.
     *
     * @param pValue    the p-value from the test
     * @param threshold significance threshold (typically 0.05)
     * @return true if p-value is below threshold
     */
    public static boolean isSignificant(double pValue, double threshold) {
        return pValue < threshold;
    }

    /**
     * Checks if sample size meets minimum requirements.
     *
     * @param n       actual sample size
     * @param minimum required minimum
     * @return true if sample is large enough
     */
    public static boolean hasSufficientSamples(int n, int minimum) {
        return n >= minimum;
    }

    // -- internal math --

    /** Standard normal CDF complement: P(Z > z) using Abramowitz & Stegun approximation. */
    static double normalCdfComplement(double z) {
        if (z < 0) return 1.0 - normalCdfComplement(-z);
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        double pdf = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        return pdf * poly;
    }

    /** Chi-square survival function P(X > x) using regularized incomplete gamma. */
    static double chiSquareSurvival(double x, int df) {
        if (x <= 0 || df <= 0) return 1.0;
        return 1.0 - regularizedGammaP(df / 2.0, x / 2.0);
    }

    /** Regularized lower incomplete gamma function P(a, x) via series expansion. */
    static double regularizedGammaP(double a, double x) {
        if (x <= 0) return 0.0;
        if (x > a + 1) return 1.0 - regularizedGammaQ(a, x);

        double term = 1.0 / a;
        double sum = term;
        for (int n = 1; n < 200; n++) {
            term *= x / (a + n);
            sum += term;
            if (Math.abs(term) < 1e-12 * Math.abs(sum)) break;
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** Regularized upper incomplete gamma function Q(a, x) via continued fraction. */
    static double regularizedGammaQ(double a, double x) {
        double f = 1.0;
        double c = 1.0;
        double d = 1.0 / (x + 1 - a);
        double h = d;
        for (int n = 1; n < 200; n++) {
            double an = -n * (n - a);
            double bn = x + 2 * n + 1 - a;
            d = bn + an * d;
            if (Math.abs(d) < 1e-30) d = 1e-30;
            c = bn + an / c;
            if (Math.abs(c) < 1e-30) c = 1e-30;
            d = 1.0 / d;
            double delta = c * d;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1e-12) break;
        }
        return h * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** Stirling's approximation for log-gamma. */
    static double logGamma(double x) {
        if (x <= 0) return 0;
        // Lanczos approximation
        double[] coef = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.001208650973866179, -0.000005395239384953};
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x - 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double c : coef) {
            y += 1;
            ser += c / y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** Hypergeometric PMF for Fisher's exact test. */
    private static double hypergeometricPmf(int k, int K, int N_K, int n, int N) {
        return Math.exp(logBinom(K, k) + logBinom(N_K, n - k) - logBinom(N, n));
    }

    /** Log of binomial coefficient. */
    private static double logBinom(int n, int k) {
        if (k < 0 || k > n) return Double.NEGATIVE_INFINITY;
        if (k == 0 || k == n) return 0;
        return logGamma(n + 1) - logGamma(k + 1) - logGamma(n - k + 1);
    }
}
