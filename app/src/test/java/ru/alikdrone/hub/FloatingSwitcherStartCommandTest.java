package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class FloatingSwitcherStartCommandTest {
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
                .putBoolean(SwitcherController.KEY_ENABLED, true)
                .commit();
        application.createDeviceProtectedStorageContext()
                .getSharedPreferences(
                        DIRECT_BOOT_PREFERENCES,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .putBoolean(SwitcherController.KEY_ENABLED, true)
                .commit();
        ShadowSettings.setCanDrawOverlays(true);
    }

    @Test
    public void reloadAdvancesRefreshGenerationButHealthCheckDoesNot() {
        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();

        // Keep this test fully synchronous. A non-null root bypasses addView(),
        // and an already-running refresh makes requestTargetRefresh() only
        // increment its generation and mark one pending follow-up scan.
        ReflectionHelpers.setField(
                service,
                "switcherView",
                new LinearLayout(service)
        );
        ReflectionHelpers.setField(
                service,
                "attachmentRecheckScheduled",
                true
        );
        ReflectionHelpers.setField(service, "targetRefreshRunning", true);
        ReflectionHelpers.setField(service, "targetRefreshPending", false);
        ReflectionHelpers.setField(
                service,
                "requestedTargetGeneration",
                41L
        );

        Intent health = new Intent(
                application,
                FloatingSwitcherService.class
        ).setAction(FloatingSwitcherService.ACTION_HEALTH_CHECK);
        assertEquals(
                android.app.Service.START_STICKY,
                service.onStartCommand(health, 0, 1)
        );
        assertEquals(41L, requestedGeneration(service));
        assertFalse(refreshPending(service));

        Intent reload = new Intent(
                application,
                FloatingSwitcherService.class
        ).setAction(FloatingSwitcherService.ACTION_RELOAD_CONFIGURATION);
        assertEquals(
                android.app.Service.START_STICKY,
                service.onStartCommand(reload, 0, 2)
        );
        assertEquals(42L, requestedGeneration(service));
        assertTrue(refreshPending(service));

        service.onStartCommand(health, 0, 3);
        assertEquals(42L, requestedGeneration(service));
        assertTrue(refreshPending(service));

        // BOOT_COMPLETED can follow USER_UNLOCKED. Once an unlocked scan has
        // been requested, the second reload must not enqueue another one.
        service.onStartCommand(reload, 0, 4);
        assertEquals(42L, requestedGeneration(service));
        assertTrue(refreshPending(service));
    }

    private static long requestedGeneration(
            FloatingSwitcherService service
    ) {
        Long generation = ReflectionHelpers.getField(
                service,
                "requestedTargetGeneration"
        );
        return generation;
    }

    private static boolean refreshPending(
            FloatingSwitcherService service
    ) {
        Boolean pending = ReflectionHelpers.getField(
                service,
                "targetRefreshPending"
        );
        return pending;
    }
}
