package dev.aceclaw.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenCaptureToolTest {

    @Test
    void normalizeRegionAcceptsCommonVariants() {
        assertThat(ScreenCaptureTool.normalizeRegion("0,0,800,600")).isEqualTo("0,0,800,600");
        assertThat(ScreenCaptureTool.normalizeRegion(" 0, 0, 800, 600 ")).isEqualTo("0,0,800,600");
        assertThat(ScreenCaptureTool.normalizeRegion("(0;0;800;600)")).isEqualTo("0,0,800,600");
        assertThat(ScreenCaptureTool.normalizeRegion("[-100,200,800,600]")).isEqualTo("-100,200,800,600");
    }

    @Test
    void normalizeRegionRejectsInvalidShapes() {
        assertThat(ScreenCaptureTool.normalizeRegion("0,0,0,600")).isNull();
        assertThat(ScreenCaptureTool.normalizeRegion("0,0,800")).isNull();
        assertThat(ScreenCaptureTool.normalizeRegion("abc")).isNull();
        assertThat(ScreenCaptureTool.normalizeRegion(null)).isNull();
    }
}
