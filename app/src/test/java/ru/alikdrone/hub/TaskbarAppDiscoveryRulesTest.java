package ru.alikdrone.hub;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TaskbarAppDiscoveryRulesTest {
    @Test
    public void arbitraryPreinstalledUserApplicationIsIncluded() {
        assertTrue(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "example.preinstalled.before.hub",
                0
        ));
        assertTrue(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "com.lenovo.anyshare.gps",
                0
        ));
    }

    @Test
    public void ownDjiAndImmutableSystemPackagesAreHandledSeparately() {
        assertFalse(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "ru.alikdrone.hub",
                0
        ));
        assertFalse(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "dji.go.v5",
                ApplicationInfo.FLAG_SYSTEM
        ));
        assertFalse(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "android.settings",
                ApplicationInfo.FLAG_SYSTEM
        ));
    }

    @Test
    public void updatedSystemApplicationIsIncluded() {
        assertTrue(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "example.updated.system.app",
                ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        ));
    }
}
