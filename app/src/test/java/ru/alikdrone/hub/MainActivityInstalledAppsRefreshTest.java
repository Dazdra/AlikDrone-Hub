package ru.alikdrone.hub;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class MainActivityInstalledAppsRefreshTest {
    @Test
    public void catalogStartsDiscoveryOnlyWhenSnapshotIsMissingAndIdle() {
        assertTrue(MainActivity.shouldStartInstalledAppsRefresh(false, false));
        assertFalse(MainActivity.shouldStartInstalledAppsRefresh(true, false));
        assertFalse(MainActivity.shouldStartInstalledAppsRefresh(false, true));
        assertFalse(MainActivity.shouldStartInstalledAppsRefresh(true, true));
    }

    @Test
    public void packageManagerDiscoveryRunsOutsideMainThread() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        waitForSnapshot(activity);

        AtomicBoolean calledOnMain = new AtomicBoolean(true);
        CountDownLatch queried = new CountDownLatch(1);
        activity.installedAppsQuery = () -> {
            calledOnMain.set(
                    Looper.myLooper() == Looper.getMainLooper()
            );
            queried.countDown();
            return emptySnapshot();
        };
        requestRefresh(activity);

        assertTrue(queried.await(5L, TimeUnit.SECONDS));
        assertFalse(calledOnMain.get());
        controller.destroy();
    }

    @Test
    public void concurrentRefreshRequestsAreCoalescedIntoOneFollowUpScan()
            throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        waitForSnapshot(activity);

        AtomicInteger queryCalls = new AtomicInteger();
        CountDownLatch firstQueryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstQuery = new CountDownLatch(1);
        activity.installedAppsQuery = () -> {
            int call = queryCalls.incrementAndGet();
            if (call == 1) {
                firstQueryEntered.countDown();
                try {
                    releaseFirstQuery.await(5L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return emptySnapshot();
        };
        requestRefresh(activity);
        assertTrue(firstQueryEntered.await(5L, TimeUnit.SECONDS));
        for (int index = 0; index < 12; index++) {
            requestRefresh(activity);
        }
        releaseFirstQuery.countDown();

        waitUntil(() -> {
            shadowOf(Looper.getMainLooper()).idle();
            return !(Boolean) field(
                    activity,
                    "installedAppsRefreshRunning"
            );
        });
        assertEquals(2, queryCalls.get());
        controller.destroy();
    }

    @Test
    public void packageAddedBroadcastRefreshesImmutableSnapshot()
            throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        waitForSnapshot(activity);
        assertFalse(snapshotContains(activity, "example.added.later"));

        addLaunchableApplication("example.added.later", "Added later");
        activity.sendBroadcast(
                new Intent(
                        Intent.ACTION_PACKAGE_ADDED,
                        Uri.parse("package:example.added.later")
                )
        );
        shadowOf(Looper.getMainLooper()).idle();

        waitUntil(() -> {
            shadowOf(Looper.getMainLooper()).idle();
            return snapshotContains(activity, "example.added.later");
        });
        assertTrue(snapshotContains(activity, "example.added.later"));
        controller.pause().stop().destroy();
    }

    @Test
    public void hiddenTaskbarPackageRemainsInInstalledAppsSnapshot()
            throws Exception {
        String packageName = "example.hidden.but.installed";
        addLaunchableApplication(packageName, "Hidden but installed");
        SwitcherController.setPackageVisible(
                RuntimeEnvironment.getApplication(),
                packageName,
                false
        );

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        waitForSnapshot(activity);

        assertTrue(snapshotContains(activity, packageName));
        assertFalse(SwitcherController.isPackageVisible(
                activity,
                packageName
        ));
        controller.pause().stop().destroy();
    }

    @Test
    public void destroyedActivityNeverAppliesLateSnapshot() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        MainActivity activity = controller.get();
        waitForSnapshot(activity);
        Object originalSnapshot = field(activity, "installedAppsSnapshot");

        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        activity.installedAppsQuery = () -> {
            queryEntered.countDown();
            try {
                releaseQuery.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return emptySnapshot();
        };
        requestRefresh(activity);
        assertTrue(queryEntered.await(5L, TimeUnit.SECONDS));
        controller.destroy();
        releaseQuery.countDown();

        Thread.sleep(30L);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(originalSnapshot, field(activity, "installedAppsSnapshot"));
    }

    private static void addLaunchableApplication(
            String packageName,
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
        activityInfo.name = packageName + ".MainActivity";
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

    private static void waitForSnapshot(MainActivity activity)
            throws Exception {
        waitUntil(() -> {
            shadowOf(Looper.getMainLooper()).idle();
            return (Boolean) field(activity, "installedAppsSnapshotReady")
                    && !(Boolean) field(
                    activity,
                    "installedAppsRefreshRunning"
            );
        });
    }

    private static MainActivity.InstalledAppsSnapshot emptySnapshot() {
        return new MainActivity.InstalledAppsSnapshot(
                Collections.emptyList(),
                Collections.emptySet()
        );
    }

    private static void requestRefresh(MainActivity activity)
            throws Exception {
        Method request = MainActivity.class.getDeclaredMethod(
                "requestInstalledAppsRefresh"
        );
        request.setAccessible(true);
        request.invoke(activity);
    }

    private static void waitUntil(CheckedCondition condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue("condition timed out", condition.evaluate());
    }

    private static boolean snapshotContains(
            MainActivity activity,
            String packageName
    ) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> snapshot = (List<Object>) field(
                activity,
                "installedAppsSnapshot"
        );
        for (Object app : snapshot) {
            if (packageName.equals(field(app, "packageName"))) {
                return true;
            }
        }
        return false;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

}
