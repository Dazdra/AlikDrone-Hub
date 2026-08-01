package ru.alikdrone.hub;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class LaunchableAppDiscoveryTest {
    private Application application;
    private ShadowPackageManager packages;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        packages = shadowOf(application.getPackageManager());
    }

    @Test
    public void queryFindsArbitraryApplicationInstalledBeforeHub() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.preinstalled.before.hub",
                "example.preinstalled.before.hub.HomeActivity",
                "Existing app",
                true,
                true,
                0
        );

        LaunchableAppDiscovery.Entry entry =
                requireEntry("example.preinstalled.before.hub");

        assertEquals("Existing app", entry.getDisplayName());
        assertEquals(
                "example.preinstalled.before.hub.HomeActivity",
                entry.getComponentName().getClassName()
        );
    }

    @Test
    public void queryDeduplicatesMultipleActivitiesAndLauncherCategories() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.dual.launcher",
                "example.dual.launcher.PhoneAlias",
                "Dual",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.dual.launcher",
                "example.dual.launcher.SecondActivity",
                "Dual second",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                "example.dual.launcher",
                "example.dual.launcher.TvAlias",
                "Dual TV",
                true,
                true,
                0
        );

        List<LaunchableAppDiscovery.Entry> matching =
                entriesFor("example.dual.launcher");

        assertEquals(1, matching.size());
        assertEquals(
                "example.dual.launcher.PhoneAlias",
                matching.get(0).getComponentName().getClassName()
        );
        assertEquals(
                Intent.CATEGORY_LAUNCHER,
                matching.get(0).getLaunchCategory()
        );
    }

    @Test
    public void launcherAliasSelectionIsDeterministicNotInsertionOrdered() {
        String packageName = "example.multiple.aliases";
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                packageName + ".ZetaAlias",
                "Multiple",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                packageName + ".AlphaAlias",
                "Multiple",
                true,
                true,
                0
        );

        assertEquals(
                packageName + ".AlphaAlias",
                requireEntry(packageName).getComponentName().getClassName()
        );
    }

    @Test
    public void launcherAliasSelectionPrefersAndroidFrontDoor() {
        String packageName = "example.front.door";
        String preferredActivity = packageName + ".ZetaAlias";
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                packageName + ".AlphaAlias",
                "Front door",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                preferredActivity,
                "Front door",
                true,
                true,
                0
        );
        addPackageSpecificLaunchResolution(
                packageName,
                preferredActivity
        );

        assertEquals(
                preferredActivity,
                requireEntry(packageName).getComponentName().getClassName()
        );
    }

    @Test
    public void launcherAliasSelectionIgnoresPackageInfoActivity() {
        String packageName = "example.front.door.with.info";
        String preferredActivity = packageName + ".ZetaLauncher";
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                packageName + ".AlphaLauncher",
                "Front door with info",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                preferredActivity,
                "Front door with info",
                true,
                true,
                0
        );
        addPackageSpecificResolution(
                Intent.CATEGORY_INFO,
                packageName,
                packageName + ".InfoActivity"
        );
        addPackageSpecificResolution(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                preferredActivity
        );

        assertEquals(
                preferredActivity,
                requireEntry(packageName).getComponentName().getClassName()
        );
    }

    @Test
    public void queryPreservesActivityAliasAsExactLaunchComponent() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.aliased",
                "example.aliased.PublicLauncherAlias",
                "Aliased app",
                true,
                true,
                0
        );

        ComponentName component =
                requireEntry("example.aliased").getComponentName();

        assertEquals("example.aliased", component.getPackageName());
        assertEquals(
                "example.aliased.PublicLauncherAlias",
                component.getClassName()
        );
    }

    @Test
    public void queryFiltersDisabledActivityAndDisabledApplication() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.disabled.activity",
                "example.disabled.activity.Main",
                "Disabled activity",
                false,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.disabled.application",
                "example.disabled.application.Main",
                "Disabled application",
                true,
                false,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.enabled",
                "example.enabled.Main",
                "Enabled",
                true,
                true,
                0
        );

        List<String> packageNames = discoveredPackageNames();

        assertFalse(packageNames.contains("example.disabled.activity"));
        assertFalse(packageNames.contains("example.disabled.application"));
        assertTrue(packageNames.contains("example.enabled"));
    }

    @Test
    public void queryFiltersUnexportedLauncherActivity() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.not.exported",
                "example.not.exported.Main",
                "Not exported",
                true,
                true,
                0,
                false
        );

        assertFalse(discoveredPackageNames().contains("example.not.exported"));
    }

    @Test
    public void querySortsByLabelIgnoringCaseThenByPackageName() {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.zebra",
                "example.zebra.Main",
                "zebra",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.same.b",
                "example.same.b.Main",
                "Alpha",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.same.a",
                "example.same.a.Main",
                "alpha",
                true,
                true,
                0
        );

        List<String> selected = new ArrayList<>();
        for (LaunchableAppDiscovery.Entry entry
                : LaunchableAppDiscovery.query(application)) {
            if (entry.getPackageName().startsWith("example.")) {
                selected.add(entry.getPackageName());
            }
        }

        assertEquals(
                Arrays.asList(
                        "example.same.a",
                        "example.same.b",
                        "example.zebra"
                ),
                selected
        );
    }

    @Test
    public void systemAndUpdatedSystemFlagsUseDifferentTaskbarPolicy() {
        int immutableSystem = ApplicationInfo.FLAG_SYSTEM;
        int updatedSystem = ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

        assertFalse(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "example.immutable.system",
                immutableSystem
        ));
        assertTrue(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "example.updated.system",
                updatedSystem
        ));
        assertTrue(FloatingSwitcherService.shouldIncludeDiscoveredApplication(
                "ru.alikdrone.hub",
                "example.user",
                0
        ));
    }

    @Test
    public void taskbarOrdersHubThenDjiThenOtherApplications()
            throws Exception {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.alphabetically.first",
                "example.alphabetically.first.Main",
                "AAA app",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "dji.go.v5",
                "dji.go.v5.MainActivity",
                "ZZZ DJI Fly",
                true,
                true,
                ApplicationInfo.FLAG_SYSTEM
        );

        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        Method method = FloatingSwitcherService.class
                .getDeclaredMethod("availableTargets");
        method.setAccessible(true);
        List<?> targets = (List<?>) method.invoke(service);

        assertNotNull(targets);
        assertTrue(targets.size() >= 3);
        assertEquals(null, targetComponent(targets.get(0)));
        assertEquals(
                "dji.go.v5",
                targetComponent(targets.get(1)).getPackageName()
        );
        assertEquals(
                "example.alphabetically.first",
                targetComponent(targets.get(2)).getPackageName()
        );
    }

    @Test
    public void taskbarCanSortByCategoryOrAlphabetically()
            throws Exception {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "com.discord",
                "com.discord.Main",
                "Zulu chat",
                true,
                true,
                0
        );
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "com.windyty.android",
                "com.windyty.android.Main",
                "Alpha weather",
                true,
                true,
                0
        );

        SwitcherController.setTaskbarSettings(
                application,
                new TaskbarSettings(
                        2,
                        6,
                        TaskbarSettings.SortMode.CATEGORIES,
                        TaskbarSettings.Anchor.FREE
                )
        );
        FloatingSwitcherService categoriesService =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        List<String> categoryOrder =
                taskbarPackageNames(categoriesService);
        assertTrue(
                categoryOrder.indexOf("com.discord")
                        < categoryOrder.indexOf("com.windyty.android")
        );
        categoriesService.onDestroy();

        SwitcherController.setTaskbarSettings(
                application,
                new TaskbarSettings(
                        2,
                        6,
                        TaskbarSettings.SortMode.ALPHABETICAL,
                        TaskbarSettings.Anchor.FREE
                )
        );
        FloatingSwitcherService alphabeticalService =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        List<String> alphabeticalOrder =
                taskbarPackageNames(alphabeticalService);
        assertTrue(
                alphabeticalOrder.indexOf("com.windyty.android")
                        < alphabeticalOrder.indexOf("com.discord")
        );
        alphabeticalService.onDestroy();
    }

    @Test
    public void hiddenUserApplicationIsExcludedOnlyFromTaskbar()
            throws Exception {
        String packageName = "example.hidden.from.taskbar";
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                packageName + ".Main",
                "Hidden app",
                true,
                true,
                0
        );
        SwitcherController.setPackageVisible(
                application,
                packageName,
                false
        );

        // Shared discovery remains complete for the installed-apps screen.
        assertTrue(discoveredPackageNames().contains(packageName));

        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        assertFalse(taskbarPackageNames(service).contains(packageName));
        service.onDestroy();
    }

    @Test
    public void hubAndDjiRemainInTaskbarWhenTheirPackageKeysAreHidden()
            throws Exception {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "dji.go.v5",
                "dji.go.v5.MainActivity",
                "DJI Fly",
                true,
                true,
                ApplicationInfo.FLAG_SYSTEM
        );
        SwitcherController.setPackageVisible(
                application,
                application.getPackageName(),
                false
        );
        SwitcherController.setPackageVisible(
                application,
                "dji.go.v5",
                false
        );

        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        List<String> packages = taskbarPackageNames(service);

        assertEquals(null, packages.get(0));
        assertTrue(packages.contains("dji.go.v5"));
        service.onDestroy();
    }

    @Test
    public void taskbarLaunchesExactLeanbackAliasComponent()
            throws Exception {
        addLaunchable(
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                "example.tv.application",
                "example.tv.application.PublicTvAlias",
                "TV application",
                true,
                true,
                0
        );

        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        Object resolvedTarget =
                requireResolvedTarget(service, "example.tv.application");
        Object target = targetObject(resolvedTarget);
        Method launch = FloatingSwitcherService.class.getDeclaredMethod(
                "launchTarget",
                target.getClass()
        );
        launch.setAccessible(true);
        launch.invoke(service, target);

        Intent started = shadowOf(application).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(
                new ComponentName(
                        "example.tv.application",
                        "example.tv.application.PublicTvAlias"
                ),
                started.getComponent()
        );
        assertTrue(started.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
    }

    @Test
    public void asynchronousRefreshPublishesImmutableSnapshotAndStopsCleanly()
            throws Exception {
        addLaunchable(
                Intent.CATEGORY_LAUNCHER,
                "example.background.discovery",
                "example.background.discovery.Main",
                "Background app",
                true,
                true,
                0
        );

        FloatingSwitcherService service =
                Robolectric.buildService(FloatingSwitcherService.class).get();
        Method requestRefresh = FloatingSwitcherService.class
                .getDeclaredMethod("requestTargetRefresh");
        requestRefresh.setAccessible(true);
        requestRefresh.invoke(service);

        Field executorField = FloatingSwitcherService.class
                .getDeclaredField("targetDiscoveryExecutor");
        executorField.setAccessible(true);
        ExecutorService executor =
                (ExecutorService) executorField.get(service);
        assertTrue(executor.submit(() -> {
            // Barrier: all previously submitted discovery work has finished.
        }).get(5, TimeUnit.SECONDS) == null);
        shadowOf(Looper.getMainLooper()).idle();

        Field snapshotField = FloatingSwitcherService.class
                .getDeclaredField("targetSnapshot");
        snapshotField.setAccessible(true);
        List<?> snapshot = (List<?>) snapshotField.get(service);
        assertEquals(null, targetComponent(snapshot.get(0)));
        assertEquals(
                "example.background.discovery",
                targetComponent(snapshot.get(1)).getPackageName()
        );
        try {
            snapshot.clear();
            fail("Published Taskbar snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: the main thread only observes complete snapshots.
        }

        List<?> publishedSnapshot = snapshot;
        service.onDestroy();
        assertTrue(executor.isShutdown());

        Method publish = FloatingSwitcherService.class.getDeclaredMethod(
                "publishTargetSnapshot",
                long.class,
                List.class
        );
        publish.setAccessible(true);
        publish.invoke(service, Long.MAX_VALUE, Collections.emptyList());
        assertTrue(publishedSnapshot == snapshotField.get(service));
    }

    private LaunchableAppDiscovery.Entry requireEntry(String packageName) {
        List<LaunchableAppDiscovery.Entry> matching = entriesFor(packageName);
        assertEquals("Expected exactly one entry for " + packageName,
                1, matching.size());
        return matching.get(0);
    }

    private List<LaunchableAppDiscovery.Entry> entriesFor(String packageName) {
        List<LaunchableAppDiscovery.Entry> matching = new ArrayList<>();
        for (LaunchableAppDiscovery.Entry entry
                : LaunchableAppDiscovery.query(application)) {
            if (packageName.equals(entry.getPackageName())) {
                matching.add(entry);
            }
        }
        return matching;
    }

    private List<String> discoveredPackageNames() {
        List<String> result = new ArrayList<>();
        for (LaunchableAppDiscovery.Entry entry
                : LaunchableAppDiscovery.query(application)) {
            result.add(entry.getPackageName());
        }
        return result;
    }

    private void addLaunchable(
            String category,
            String packageName,
            String activityName,
            String label,
            boolean activityEnabled,
            boolean applicationEnabled,
            int applicationFlags
    ) {
        addLaunchable(
                category,
                packageName,
                activityName,
                label,
                activityEnabled,
                applicationEnabled,
                applicationFlags,
                true
        );
    }

    private void addLaunchable(
            String category,
            String packageName,
            String activityName,
            String label,
            boolean activityEnabled,
            boolean applicationEnabled,
            int applicationFlags,
            boolean exported
    ) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.enabled = applicationEnabled;
        applicationInfo.flags = applicationFlags;

        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = activityName;
        activityInfo.enabled = activityEnabled;
        activityInfo.exported = exported;
        activityInfo.applicationInfo = applicationInfo;

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.nonLocalizedLabel = label;

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        packages.addResolveInfoForIntent(intent, resolveInfo);
    }

    private void addPackageSpecificLaunchResolution(
            String packageName,
            String activityName
    ) {
        addPackageSpecificResolution(
                Intent.CATEGORY_LAUNCHER,
                packageName,
                activityName
        );
    }

    private void addPackageSpecificResolution(
            String category,
            String packageName,
            String activityName
    ) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.enabled = true;

        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = activityName;
        activityInfo.enabled = true;
        activityInfo.exported = true;
        activityInfo.applicationInfo = applicationInfo;

        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.nonLocalizedLabel = "Front door";

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        intent.setPackage(packageName);
        packages.addResolveInfoForIntent(intent, resolveInfo);
    }

    private static ComponentName targetComponent(Object resolvedTarget)
            throws Exception {
        Object target = targetObject(resolvedTarget);
        Field componentField =
                target.getClass().getDeclaredField("componentName");
        componentField.setAccessible(true);
        return (ComponentName) componentField.get(target);
    }

    private static Object targetObject(Object resolvedTarget)
            throws Exception {
        Field targetField =
                resolvedTarget.getClass().getDeclaredField("target");
        targetField.setAccessible(true);
        return targetField.get(resolvedTarget);
    }

    private static Object requireResolvedTarget(
            FloatingSwitcherService service,
            String packageName
    ) throws Exception {
        Method availableTargets = FloatingSwitcherService.class
                .getDeclaredMethod("availableTargets");
        availableTargets.setAccessible(true);
        List<?> targets = (List<?>) availableTargets.invoke(service);
        for (Object target : targets) {
            ComponentName component = targetComponent(target);
            if (component != null
                    && packageName.equals(component.getPackageName())) {
                return target;
            }
        }
        throw new AssertionError("Missing Taskbar target for " + packageName);
    }

    private static List<String> taskbarPackageNames(
            FloatingSwitcherService service
    ) throws Exception {
        Method availableTargets = FloatingSwitcherService.class
                .getDeclaredMethod("availableTargets");
        availableTargets.setAccessible(true);
        List<?> targets = (List<?>) availableTargets.invoke(service);
        List<String> packages = new ArrayList<>();
        for (Object target : targets) {
            ComponentName component = targetComponent(target);
            packages.add(component == null ? null : component.getPackageName());
        }
        return packages;
    }
}
