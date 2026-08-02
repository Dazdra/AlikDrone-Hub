package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskbarBootRefreshPolicyTest {
    @Test
    public void directBootReloadRescansApplicationsAfterUserUnlock() {
        assertTrue(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION,
                false,
                true
        ));
    }

    @Test
    public void bootBroadcastBurstRequestsOnlyOneUnlockedScan() {
        assertFalse(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION,
                false,
                false
        ));
        assertTrue(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION,
                false,
                true
        ));
        assertFalse(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION,
                true,
                true
        ));
    }

    @Test
    public void explicitPackageRefreshRescansApplications() {
        assertTrue(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_REFRESH_TARGETS,
                true,
                true
        ));
    }

    @Test
    public void watchdogDoesNotDecodeEveryApplicationIcon() {
        assertFalse(FloatingSwitcherService.shouldRefreshTargetsForAction(
                FloatingSwitcherService.ACTION_HEALTH_CHECK,
                false,
                true
        ));
        assertFalse(FloatingSwitcherService.shouldRefreshTargetsForAction(
                null,
                false,
                true
        ));
    }
}
