package ru.alikdrone.hub;

import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class CatalogSectionPresentationTest {
    @Test
    public void downloadedUpdateDoesNotHideInstalledApplication()
            throws Exception {
        String packageName = "example.installed.with.update";
        addInstalledLaunchableApplication(
                packageName,
                "example.installed.with.update.MainActivity",
                "Existing app"
        );

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        waitForInstalledAppsSnapshot(activity);
        LinearLayout container = new LinearLayout(activity);

        Method render = MainActivity.class.getDeclaredMethod(
                "renderCatalogSections",
                LinearLayout.class,
                LayoutInflater.class,
                AlertDialog.class,
                List.class,
                List.class,
                Set.class
        );
        render.setAccessible(true);
        render.invoke(
                activity,
                container,
                LayoutInflater.from(activity),
                null,
                Collections.singletonList(downloadedItem(packageName)),
                field(activity, "installedAppsSnapshot"),
                field(activity, "knownInstalledCatalogPackagesSnapshot")
        );

        Button installedAction = actionForRow(container, "Existing app");
        Button downloadedAction = actionForRow(container, "Downloaded app");
        assertNotNull(installedAction);
        assertNotNull(downloadedAction);
        assertEquals(
                activity.getString(R.string.catalog_uninstall),
                installedAction.getText().toString()
        );
        assertEquals(
                activity.getString(R.string.install_action),
                downloadedAction.getText().toString()
        );
        TextView category = textForRow(
                container,
                "Existing app",
                R.id.catalog_item_category
        );
        assertNotNull(category);
        assertEquals(View.VISIBLE, category.getVisibility());
        assertTrue(category.getText().length() > 0);
        TextView sourceWarning =
                container.findViewById(R.id.catalog_source_warning);
        assertNotNull(sourceWarning);
        assertEquals(
                activity.getString(
                        AppCatalog.isPublicEdition()
                                ? R.string.catalog_official_warning
                                : R.string.catalog_source_warning
                ),
                sourceWarning.getText().toString()
        );
        assertEquals(
                activity.getColor(R.color.status_missing),
                sourceWarning.getCurrentTextColor()
        );
        while (shadowOf(activity).getNextStartedActivity() != null) {
            // Ignore unrelated startup intents before testing the row action.
        }
        View installedRow = rowFor(container, "Existing app");
        assertNotNull(installedRow);
        installedRow.performClick();
        Intent launched = shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(
                new ComponentName(
                        packageName,
                        "example.installed.with.update.MainActivity"
                ),
                launched.getComponent()
        );
        controller.pause().stop().destroy();
    }

    private static void waitForInstalledAppsSnapshot(MainActivity activity)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!(Boolean) field(activity, "installedAppsSnapshotReady")
                && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(10L);
        }
        assertEquals(
                "installed app snapshot was not delivered",
                true,
                field(activity, "installedAppsSnapshotReady")
        );
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void addInstalledLaunchableApplication(
            String packageName,
            String activityName,
            String label
    ) {
        Application application = RuntimeEnvironment.getApplication();
        ShadowPackageManager packages =
                shadowOf(application.getPackageManager());

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.enabled = true;

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.versionName = "1.0";
        packageInfo.versionCode = 1;
        packageInfo.applicationInfo = applicationInfo;
        packages.installPackage(packageInfo);

        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = activityName;
        activityInfo.enabled = true;
        activityInfo.exported = true;
        activityInfo.applicationInfo = applicationInfo;

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.nonLocalizedLabel = label;

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addResolveInfoForIntent(launcherIntent, resolveInfo);
    }

    private static ApkInventoryScanner.Item downloadedItem(
            String packageName
    ) throws Exception {
        Constructor<?> constructor =
                ApkInventoryScanner.Item.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (ApkInventoryScanner.Item) constructor.newInstance(
                null,
                "downloaded-update",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ApkSigningIdentity.singleSignerHistory(Collections.singletonList(
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                )),
                packageName,
                "Downloaded app",
                "2.0",
                2L,
                "1.0",
                1L,
                "downloaded-update.apk",
                32_768L,
                1L,
                null
        );
    }

    private static Button actionForRow(
            LinearLayout container,
            String expectedName
    ) {
        return viewForRow(
                container,
                expectedName,
                R.id.catalog_item_action
        );
    }

    private static TextView textForRow(
            LinearLayout container,
            String expectedName,
            int viewId
    ) {
        return viewForRow(container, expectedName, viewId);
    }

    private static <T extends View> T viewForRow(
            LinearLayout container,
            String expectedName,
            int viewId
    ) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            TextView name = child.findViewById(R.id.catalog_item_name);
            if (name != null
                    && expectedName.equals(name.getText().toString())) {
                return child.findViewById(viewId);
            }
        }
        return null;
    }

    private static View rowFor(
            LinearLayout container,
            String expectedName
    ) {
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            TextView name = child.findViewById(R.id.catalog_item_name);
            if (name != null
                    && expectedName.equals(name.getText().toString())) {
                return child;
            }
        }
        return null;
    }
}
