package ru.alikdrone.hub;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowSettings;

import java.util.Set;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 30)
public final class TaskbarVisibilityPreferencesTest {
    private static final String DIRECT_BOOT_PREFERENCES =
            "alikdrone_switcher_direct_boot";

    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                        SwitcherController.PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
        application.createDeviceProtectedStorageContext()
                .getSharedPreferences(
                        DIRECT_BOOT_PREFERENCES,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
        ShadowSettings.setCanDrawOverlays(false);
        drainStartedServices();
    }

    @Test
    public void packagesAreVisibleByDefaultAndCanBeHiddenAndShownAgain() {
        String packageName = "example.toggle";

        assertTrue(SwitcherController.isPackageVisible(
                application,
                packageName
        ));

        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );
        assertFalse(SwitcherController.isPackageVisible(
                application,
                packageName
        ));
        assertTrue(SwitcherController.readHiddenPackages(application)
                .contains(packageName));

        SwitcherController.setPackageVisible(
                application,
                packageName,
                true
        );
        assertTrue(SwitcherController.isPackageVisible(
                application,
                packageName
        ));
        assertFalse(SwitcherController.readHiddenPackages(application)
                .contains(packageName));
    }

    @Test
    public void packageKeySurvivesLauncherActivityAliasReplacement() {
        ComponentName oldAlias = new ComponentName(
                "example.alias.update",
                "example.alias.update.OldLauncherAlias"
        );
        ComponentName newAlias = new ComponentName(
                "example.alias.update",
                "example.alias.update.NewLauncherAlias"
        );

        SwitcherController.setPackageVisible(
                application,
                oldAlias.getPackageName(),
                false
        );

        assertFalse(SwitcherController.isPackageVisible(
                application,
                newAlias.getPackageName()
        ));
        assertEquals(
                oldAlias.getPackageName(),
                newAlias.getPackageName()
        );
    }

    @Test
    public void returnedHiddenSetIsDefensiveCopy() {
        String packageName = "example.defensive.copy";
        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );

        Set<String> callerCopy =
                SwitcherController.readHiddenPackages(application);
        callerCopy.clear();
        callerCopy.add("example.injected.by.caller");

        Set<String> reread =
                SwitcherController.readHiddenPackages(application);
        assertTrue(reread.contains(packageName));
        assertFalse(reread.contains("example.injected.by.caller"));
    }

    @Test
    public void invalidPackageKeysDoNotChangePreferences() {
        SwitcherController.setPackageVisible(application, null, false);
        SwitcherController.setPackageVisible(application, "", false);
        SwitcherController.setPackageVisible(application, "   ", false);

        assertTrue(SwitcherController.readHiddenPackages(application).isEmpty());
        assertTrue(SwitcherController.isPackageVisible(application, null));
    }

    @Test
    public void temporarilyMissingPackageKeepsPreferenceForReinstall() {
        String packageName = "example.uninstalled.then.reinstalled";

        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );

        // Package visibility preferences deliberately outlive PackageManager
        // discovery. A reinstall can therefore not unexpectedly repopulate
        // the user's quick panel.
        assertTrue(SwitcherController.readHiddenPackages(application)
                .contains(packageName));
        assertFalse(SwitcherController.isPackageVisible(
                application,
                packageName
        ));
    }

    @Test
    public void changedPreferenceRefreshesRunningTaskbarExactlyOnce() {
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(SwitcherController.setEnabled(application, true));
        drainStartedServices();

        String packageName = "example.refresh";
        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );

        Intent refresh = nextStartedService();
        assertEquals(
                FloatingSwitcherService.class.getName(),
                refresh.getComponent().getClassName()
        );
        assertEquals(
                FloatingSwitcherService.ACTION_REFRESH_TARGETS,
                refresh.getAction()
        );
        assertNull(nextStartedService());

        // Re-applying the same value is idempotent and must not restart or
        // refresh the overlay a second time.
        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );
        assertNull(nextStartedService());
    }

    @Test
    public void layoutSettingsPersistToDirectBootAndRefreshOnce() {
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(SwitcherController.setEnabled(application, true));
        drainStartedServices();

        TaskbarSettings settings = new TaskbarSettings(
                4,
                8,
                TaskbarSettings.SortMode.ALPHABETICAL,
                TaskbarSettings.Anchor.BOTTOM_LEFT
        );
        SwitcherController.setTaskbarSettings(application, settings);

        assertEquals(
                settings,
                SwitcherController.readTaskbarSettings(application)
        );
        Intent refresh = nextStartedService();
        assertEquals(
                FloatingSwitcherService.ACTION_REFRESH_TARGETS,
                refresh.getAction()
        );
        assertNull(nextStartedService());

        SwitcherController.setTaskbarSettings(application, settings);
        assertNull(nextStartedService());

        application.getSharedPreferences(
                        SwitcherController.PREFERENCES_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
        assertEquals(
                settings,
                SwitcherController.readTaskbarSettings(application)
        );
    }

    @Test
    public void categoryOverrideUsesPackageKeyAndCanReturnToAuto() {
        String packageName = "org.telegram.messenger";

        SwitcherController.setPackageCategoryOverride(
                application,
                packageName,
                TaskbarCategory.MEDIA_STREAMING
        );
        Map<String, String> overrides =
                SwitcherController.readPackageCategoryOverrides(application);
        assertEquals(
                TaskbarCategory.MEDIA_STREAMING.getId(),
                overrides.get(packageName)
        );
        assertEquals(
                TaskbarCategory.MEDIA_STREAMING,
                TaskbarCategory.resolve(
                        packageName,
                        overrides.get(packageName)
                )
        );

        SwitcherController.setPackageCategoryOverride(
                application,
                packageName,
                null
        );
        assertFalse(
                SwitcherController.readPackageCategoryOverrides(application)
                        .containsKey(packageName)
        );
        assertEquals(
                TaskbarCategory.COMMUNICATION,
                TaskbarCategory.resolve(packageName, null)
        );
    }

    @Test
    public void draggingPositionSwitchesCornerPresetToFree() {
        SwitcherController.setTaskbarSettings(
                application,
                new TaskbarSettings(
                        2,
                        6,
                        TaskbarSettings.SortMode.CATEGORIES,
                        TaskbarSettings.Anchor.BOTTOM_RIGHT
                )
        );

        SwitcherController.writePosition(application, false, 123);

        TaskbarSettings updated =
                SwitcherController.readTaskbarSettings(application);
        assertEquals(TaskbarSettings.Anchor.FREE, updated.getAnchor());
        assertFalse(SwitcherController.readEdgeRight(application, true));
        assertEquals(
                123,
                SwitcherController.readVerticalPosition(application, 0)
        );
    }

    @Test
    public void restoringEnabledTaskbarReloadsDirectBootConfiguration() {
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(SwitcherController.setEnabled(application, true));
        drainStartedServices();

        assertTrue(SwitcherController.startIfEnabled(application));

        Intent restore = nextStartedService();
        assertEquals(
                FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION,
                restore.getAction()
        );
        assertNull(nextStartedService());
    }

    private Intent nextStartedService() {
        ShadowApplication shadowApplication = shadowOf(application);
        return shadowApplication.getNextStartedService();
    }

    private void drainStartedServices() {
        while (nextStartedService() != null) {
            // Drain calls made by a previous assertion or setup action.
        }
    }
}
