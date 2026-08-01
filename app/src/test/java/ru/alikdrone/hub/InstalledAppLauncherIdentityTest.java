package ru.alikdrone.hub;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class InstalledAppLauncherIdentityTest {
    @Test
    public void activityAliasChangeInvalidatesRenderedAppIdentity() {
        String first = MainActivity.launcherIdentity(
                new ComponentName("example.app", "example.app.FirstAlias"),
                Intent.CATEGORY_LAUNCHER
        );
        String second = MainActivity.launcherIdentity(
                new ComponentName("example.app", "example.app.SecondAlias"),
                Intent.CATEGORY_LAUNCHER
        );

        assertNotEquals(first, second);
    }

    @Test
    public void launchCategoryIsPartOfRenderedAppIdentity() {
        ComponentName component =
                new ComponentName("example.app", "example.app.MainActivity");
        String launcher = MainActivity.launcherIdentity(
                component,
                Intent.CATEGORY_LAUNCHER
        );
        String leanback = MainActivity.launcherIdentity(
                component,
                Intent.CATEGORY_LEANBACK_LAUNCHER
        );

        assertNotEquals(launcher, leanback);
        assertTrue(launcher.contains(Intent.CATEGORY_LAUNCHER));
    }

    @Test
    public void availabilityFilterRefreshesExternalAndSuspendedApps() {
        IntentFilter filter = MainActivity.buildPackageAvailabilityFilter();

        assertTrue(filter.hasAction(
                Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
        ));
        assertTrue(filter.hasAction(
                Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE
        ));
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGES_SUSPENDED));
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGES_UNSUSPENDED));
    }
}
