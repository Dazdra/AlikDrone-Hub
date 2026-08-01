package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FloatingSwitcherLayoutTest {
    @Test
    public void buttonWidthUsesAvailableSpaceWithoutExceedingMaximum() {
        assertEquals(
                50,
                FloatingSwitcherService.calculateButtonWidth(600, 6, 50)
        );
        assertEquals(
                30,
                FloatingSwitcherService.calculateButtonWidth(180, 6, 50)
        );
    }

    @Test
    public void appViewportKeepsFixedButtonsAndBecomesScrollable() {
        assertEquals(
                300,
                FloatingSwitcherService.calculateScrollableViewportWidth(
                        600,
                        6,
                        50
                )
        );
        assertEquals(
                180,
                FloatingSwitcherService.calculateScrollableViewportWidth(
                        180,
                        20,
                        50
                )
        );
    }

    @Test
    public void buttonWidthStaysValidForDegenerateInputs() {
        assertEquals(
                1,
                FloatingSwitcherService.calculateButtonWidth(0, 0, 0)
        );
    }

    @Test
    public void clampKeepsDragPositionInsideScreenBounds() {
        assertEquals(8, FloatingSwitcherService.clamp(-100, 8, 700));
        assertEquals(320, FloatingSwitcherService.clamp(320, 8, 700));
        assertEquals(700, FloatingSwitcherService.clamp(900, 8, 700));
    }

    @Test
    public void attachmentRecoveryUsesBoundedBackoff() {
        assertEquals(
                250L,
                FloatingSwitcherService.attachmentRecheckDelayMillis(0)
        );
        assertEquals(
                1_000L,
                FloatingSwitcherService.attachmentRecheckDelayMillis(1)
        );
        assertEquals(
                5_000L,
                FloatingSwitcherService.attachmentRecheckDelayMillis(2)
        );
        assertEquals(
                30_000L,
                FloatingSwitcherService.attachmentRecheckDelayMillis(3)
        );
        assertEquals(
                30_000L,
                FloatingSwitcherService.attachmentRecheckDelayMillis(99)
        );
    }
}
