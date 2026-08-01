package ru.alikdrone.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for launcher-capable applications visible to Hub.
 */
final class LaunchableAppDiscovery {
    private static final String TAG = "AlikDroneApps";

    static final class Entry {
        private final String displayName;
        private final String packageName;
        private final ComponentName componentName;
        private final String launchCategory;
        private final ApplicationInfo applicationInfo;
        private final Drawable icon;

        Entry(
                String displayName,
                String packageName,
                ComponentName componentName,
                String launchCategory,
                ApplicationInfo applicationInfo,
                Drawable icon
        ) {
            this.displayName = displayName;
            this.packageName = packageName;
            this.componentName = componentName;
            this.launchCategory = launchCategory;
            this.applicationInfo = applicationInfo;
            this.icon = icon;
        }

        String getDisplayName() {
            return displayName;
        }

        String getPackageName() {
            return packageName;
        }

        ComponentName getComponentName() {
            return componentName;
        }

        String getLaunchCategory() {
            return launchCategory;
        }

        ApplicationInfo getApplicationInfo() {
            return applicationInfo;
        }

        Drawable getIcon() {
            return icon;
        }
    }

    private LaunchableAppDiscovery() {
    }

    static List<Entry> query(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Map<String, Entry> byPackage = new LinkedHashMap<>();
        collect(
                packageManager,
                Intent.CATEGORY_LAUNCHER,
                byPackage
        );
        collect(
                packageManager,
                Intent.CATEGORY_LEANBACK_LAUNCHER,
                byPackage
        );

        List<Entry> result = new ArrayList<>(byPackage.values());
        Collections.sort(result, (left, right) -> {
            int byLabel = left.displayName.compareToIgnoreCase(right.displayName);
            if (byLabel != 0) {
                return byLabel;
            }
            return left.packageName.compareToIgnoreCase(right.packageName);
        });
        return result;
    }

    private static void collect(
            PackageManager packageManager,
            String launchCategory,
            Map<String, Entry> byPackage
    ) {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(launchCategory);
        List<ResolveInfo> activities;
        try {
            activities = packageManager.queryIntentActivities(launcherIntent, 0);
        } catch (RuntimeException error) {
            Log.w(TAG, "Launcher application query failed: " + launchCategory, error);
            return;
        }
        List<ResolveInfo> unresolvedActivities = new ArrayList<>();
        for (ResolveInfo activity : activities) {
            if (!byPackage.containsKey(activityPackage(activity))) {
                unresolvedActivities.add(activity);
            }
        }
        activities = unresolvedActivities;
        Map<String, Integer> candidateCounts = new HashMap<>();
        for (ResolveInfo activity : activities) {
            String packageName = activityPackage(activity);
            if (!packageName.isEmpty()) {
                Integer count = candidateCounts.get(packageName);
                candidateCounts.put(packageName, count == null ? 1 : count + 1);
            }
        }
        Map<String, ComponentName> preferredComponents = new HashMap<>();
        for (ResolveInfo activity : activities) {
            String packageName = activityPackage(activity);
            if (!packageName.isEmpty()
                    && candidateCounts.get(packageName) != null
                    && candidateCounts.get(packageName) > 1
                    && !preferredComponents.containsKey(packageName)) {
                preferredComponents.put(
                        packageName,
                        preferredLaunchComponent(
                                packageManager,
                                packageName,
                                launchCategory
                        )
                );
            }
        }
        // PackageManager ordering is vendor-dependent. Sorting candidates
        // before deduplication makes the selected launcher alias stable. The
        // package's official front-door activity wins when Android exposes
        // one; the class-name order is only a deterministic fallback.
        Collections.sort(activities, (left, right) -> {
            String leftPackage = activityPackage(left);
            String rightPackage = activityPackage(right);
            int packageComparison =
                    leftPackage.compareToIgnoreCase(rightPackage);
            if (packageComparison != 0) {
                return packageComparison;
            }
            ComponentName preferred = preferredComponents.get(leftPackage);
            boolean leftPreferred = matchesComponent(left, preferred);
            boolean rightPreferred = matchesComponent(right, preferred);
            if (leftPreferred != rightPreferred) {
                return leftPreferred ? -1 : 1;
            }
            return activityName(left).compareToIgnoreCase(activityName(right));
        });
        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null
                    || activity.activityInfo.applicationInfo == null
                    || !activity.activityInfo.enabled
                    || !activity.activityInfo.exported) {
                continue;
            }
            ApplicationInfo applicationInfo =
                    activity.activityInfo.applicationInfo;
            String packageName = applicationInfo.packageName;
            String activityName = activity.activityInfo.name;
            if (!applicationInfo.enabled
                    || packageName == null
                    || packageName.trim().isEmpty()
                    || activityName == null
                    || activityName.trim().isEmpty()
                    || byPackage.containsKey(packageName)) {
                continue;
            }

            CharSequence label;
            try {
                label = activity.loadLabel(packageManager);
            } catch (RuntimeException error) {
                label = null;
            }
            String displayName = label == null
                    ? packageName
                    : label.toString().trim();
            if (displayName.isEmpty()) {
                displayName = packageName;
            }

            Drawable icon;
            try {
                icon = activity.loadIcon(packageManager);
            } catch (RuntimeException error) {
                icon = null;
            }
            byPackage.put(
                    packageName,
                    new Entry(
                            displayName,
                            packageName,
                            new ComponentName(packageName, activityName),
                            launchCategory,
                            applicationInfo,
                            icon
                    )
            );
        }
    }

    private static String activityPackage(ResolveInfo info) {
        return info == null
                || info.activityInfo == null
                || info.activityInfo.packageName == null
                ? ""
                : info.activityInfo.packageName;
    }

    private static String activityName(ResolveInfo info) {
        return info == null
                || info.activityInfo == null
                || info.activityInfo.name == null
                ? ""
                : info.activityInfo.name;
    }

    private static ComponentName preferredLaunchComponent(
            PackageManager packageManager,
            String packageName,
            String launchCategory
    ) {
        try {
            if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(launchCategory)) {
                Intent launchIntent =
                        packageManager.getLeanbackLaunchIntentForPackage(
                                packageName
                        );
                return launchIntent == null
                        ? null
                        : launchIntent.getComponent();
            }

            // getLaunchIntentForPackage() may prefer CATEGORY_INFO before
            // CATEGORY_LAUNCHER. Resolve the exact launcher contract so an
            // unrelated INFO activity cannot make us fall back to the wrong
            // alphabetically sorted alias.
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launcherIntent.setPackage(packageName);
            ResolveInfo resolved = packageManager.resolveActivity(
                    launcherIntent,
                    0
            );
            if (resolved == null
                    || resolved.activityInfo == null
                    || resolved.activityInfo.packageName == null
                    || resolved.activityInfo.name == null) {
                return null;
            }
            return new ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name
            );
        } catch (RuntimeException error) {
            Log.w(TAG, "Front-door lookup failed: " + packageName, error);
            return null;
        }
    }

    private static boolean matchesComponent(
            ResolveInfo info,
            ComponentName component
    ) {
        return component != null
                && component.getPackageName().equals(activityPackage(info))
                && component.getClassName().equals(activityName(info));
    }

    static boolean isUnremovableSystemApp(ApplicationInfo applicationInfo) {
        return isUnremovableSystemFlags(applicationInfo.flags);
    }

    static boolean isUnremovableSystemFlags(int flags) {
        boolean system = (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updatedSystem =
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return system && !updatedSystem;
    }
}
