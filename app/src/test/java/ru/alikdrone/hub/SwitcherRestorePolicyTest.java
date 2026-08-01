package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SwitcherRestorePolicyTest {
    @Test
    public void restoreRetriesUseBoundedBackoff() {
        assertEquals(5_000L, SwitcherController.restoreRetryDelayMillis(0));
        assertEquals(15_000L, SwitcherController.restoreRetryDelayMillis(1));
        assertEquals(45_000L, SwitcherController.restoreRetryDelayMillis(2));
        assertEquals(600_000L, SwitcherController.restoreRetryDelayMillis(99));
        assertEquals(5_000L, SwitcherController.restoreRetryDelayMillis(-1));
    }

    @Test
    public void watchdogKeepsCheckingAfterAHealthyStart() {
        assertEquals(
                15L * 60L * 1_000L,
                SwitcherController.restoreWatchdogDelayMillis()
        );
    }

    @Test
    public void retryCounterSaturatesInsteadOfStoppingRecovery() {
        assertEquals(1, SwitcherController.nextRestoreAttempt(-10));
        assertEquals(1, SwitcherController.nextRestoreAttempt(0));
        assertEquals(6, SwitcherController.nextRestoreAttempt(5));
        assertEquals(6, SwitcherController.nextRestoreAttempt(6));
        assertEquals(6, SwitcherController.nextRestoreAttempt(1_000));
    }
}
