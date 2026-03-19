package dev.aceclaw.core.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatisticalTestTest {

    @Test
    void confidenceInterval95_fairCoin() {
        double[] ci = StatisticalTest.confidenceInterval95(50, 100);
        assertThat(ci[0]).isCloseTo(0.40, within(0.02));
        assertThat(ci[1]).isCloseTo(0.60, within(0.02));
    }

    @Test
    void confidenceInterval95_smallSample() {
        double[] ci = StatisticalTest.confidenceInterval95(3, 5);
        // Wilson interval should be wider than naive for small n
        assertThat(ci[0]).isLessThan(0.60);
        assertThat(ci[1]).isGreaterThan(0.60);
        assertThat(ci[1] - ci[0]).isGreaterThan(0.20); // wide interval
    }

    @Test
    void confidenceInterval95_zeroSample() {
        double[] ci = StatisticalTest.confidenceInterval95(0, 0);
        assertThat(ci[0]).isEqualTo(0.0);
        assertThat(ci[1]).isEqualTo(0.0);
    }

    @Test
    void twoProportionZTest_identicalRates() {
        double p = StatisticalTest.twoProportionZTest(50, 100, 50, 100);
        assertThat(p).isGreaterThan(0.05); // not significant
    }

    @Test
    void twoProportionZTest_clearDifference() {
        double p = StatisticalTest.twoProportionZTest(90, 100, 50, 100);
        assertThat(p).isLessThan(0.001); // highly significant
    }

    @Test
    void twoProportionZTest_smallSampleNoise() {
        double p = StatisticalTest.twoProportionZTest(3, 5, 2, 5);
        assertThat(p).isGreaterThan(0.05); // not significant with n=5
    }

    @Test
    void twoProportionZTest_emptyGroups() {
        assertThat(StatisticalTest.twoProportionZTest(0, 0, 0, 0)).isEqualTo(1.0);
    }

    @Test
    void chiSquareTest_uniformFit() {
        int[] observed = {25, 25, 25, 25};
        double[] expected = {25.0, 25.0, 25.0, 25.0};
        double p = StatisticalTest.chiSquareTest(observed, expected);
        assertThat(p).isGreaterThan(0.99); // perfect fit
    }

    @Test
    void chiSquareTest_clearDeviation() {
        int[] observed = {90, 5, 3, 2};
        double[] expected = {25.0, 25.0, 25.0, 25.0};
        double p = StatisticalTest.chiSquareTest(observed, expected);
        assertThat(p).isLessThan(0.001); // very significant deviation
    }

    @Test
    void fisherExactTest_independentVariables() {
        // No association
        double p = StatisticalTest.fisherExactTest(10, 10, 10, 10);
        assertThat(p).isGreaterThan(0.05);
    }

    @Test
    void fisherExactTest_strongAssociation() {
        // Strong association
        double p = StatisticalTest.fisherExactTest(18, 2, 2, 18);
        assertThat(p).isLessThan(0.001);
    }

    @Test
    void isSignificant_belowThreshold() {
        assertThat(StatisticalTest.isSignificant(0.03, 0.05)).isTrue();
    }

    @Test
    void isSignificant_aboveThreshold() {
        assertThat(StatisticalTest.isSignificant(0.08, 0.05)).isFalse();
    }

    @Test
    void hasSufficientSamples_meets() {
        assertThat(StatisticalTest.hasSufficientSamples(10, 10)).isTrue();
    }

    @Test
    void hasSufficientSamples_insufficient() {
        assertThat(StatisticalTest.hasSufficientSamples(5, 10)).isFalse();
    }

    @Test
    void normalCdfComplement_knownValues() {
        // P(Z > 0) = 0.5
        assertThat(StatisticalTest.normalCdfComplement(0)).isCloseTo(0.5, within(0.01));
        // P(Z > 1.96) ≈ 0.025
        assertThat(StatisticalTest.normalCdfComplement(1.96)).isCloseTo(0.025, within(0.005));
        // P(Z > 3) ≈ 0.0013
        assertThat(StatisticalTest.normalCdfComplement(3.0)).isCloseTo(0.0013, within(0.001));
    }
}
