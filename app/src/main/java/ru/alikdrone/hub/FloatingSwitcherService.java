package ru.alikdrone.hub;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Visible, user-controlled floating taskbar for AlikDrone Hub.
 *
 * <p>The collapsed 52dp AD handle can be dragged between screen edges. A tap
 * expands installed applications inward from the selected edge. This service
 * intentionally uses neither Accessibility nor root.</p>
 */
public final class FloatingSwitcherService extends Service {
    private static final String TAG = "AlikDroneTaskbar";
    private static final int INVALID_POINTER_ID = -1;
    public static final String ACTION_STOP =
            "ru.alikdrone.hub.action.STOP_FLOATING_SWITCHER";
    public static final String ACTION_REFRESH_TARGETS =
            "ru.alikdrone.hub.action.REFRESH_FLOATING_SWITCHER_TARGETS";
    public static final String ACTION_RELOAD_CONFIGURATION =
            "ru.alikdrone.hub.action.RELOAD_FLOATING_SWITCHER_CONFIGURATION";
    public static final String ACTION_HEALTH_CHECK =
            "ru.alikdrone.hub.action.HEALTH_CHECK_FLOATING_SWITCHER";

    private static final String CHANNEL_ID = "alikdrone_switcher";
    private static final int NOTIFICATION_ID = 2201;
    private static final int REQUEST_OPEN_HUB = 2202;
    private static final int REQUEST_STOP = 2203;
    private static final long[] ATTACHMENT_RECHECK_DELAYS_MS =
            new long[]{250L, 1_000L, 5_000L, 30_000L};

    private static final class AppTarget {
        final String displayName;
        final boolean hub;
        final ComponentName componentName;
        final String launchCategory;

        AppTarget(
                String displayName,
                boolean hub,
                ComponentName componentName,
                String launchCategory
        ) {
            this.displayName = displayName;
            this.hub = hub;
            this.componentName = componentName;
            this.launchCategory = launchCategory;
        }
    }

    private static final AppTarget HUB =
            new AppTarget(
                    "AlikDrone Hub",
                    true,
                    null,
                    Intent.CATEGORY_LAUNCHER
            );

    private static final class ResolvedTarget {
        final AppTarget target;
        final Drawable icon;
        final TaskbarCategory category;

        ResolvedTarget(
                AppTarget target,
                Drawable icon,
                TaskbarCategory category
        ) {
            this.target = target;
            this.icon = icon;
            this.category = category;
        }
    }

    private WindowManager windowManager;
    private LinearLayout switcherView;
    private SwitcherButton handleView;
    private WindowManager.LayoutParams layoutParams;
    private boolean expanded;
    private boolean edgeRight;
    private int freePositionY;
    private int handleOffsetY;
    private int handleSize;
    private int edgeMargin;
    private boolean gestureActive;
    private boolean renderAfterGesture;
    private boolean renderScheduled;
    private boolean windowUpdateScheduled;
    private boolean restoreRecoveryArmed;
    private boolean attachmentRecheckScheduled;
    private boolean packageReceiverRegistered;
    private boolean firstStartCommand = true;
    private Drawable cachedHubIcon;
    private volatile boolean destroyed;
    private final Object targetRefreshLock = new Object();
    private final ExecutorService targetDiscoveryExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "AlikDrone-AppDiscovery"
                );
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private boolean targetRefreshRunning;
    private boolean targetRefreshPending;
    private long requestedTargetGeneration;
    private long publishedTargetGeneration;
    private int attachmentRecheckAttempt;
    private List<ResolvedTarget> targetSnapshot =
            Collections.singletonList(new ResolvedTarget(
                    HUB,
                    null,
                    TaskbarCategory.FLIGHT
            ));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver packageChangeReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    requestTargetRefresh();
                }
            };

    private final Runnable attachmentRecheckRunnable = () -> {
        attachmentRecheckScheduled = false;
        if (!SwitcherController.isEnabled(this)
                || !SwitcherController.hasOverlayPermission(this)) {
            stopSelf();
            return;
        }
        if (switcherView != null && switcherView.isAttachedToWindow()) {
            markAttachmentHealthy();
            return;
        }

        try {
            if (!removeSwitcherViewImmediately()) {
                Log.w(
                        TAG,
                        "Taskbar root removal is uncertain; recovery deferred"
                );
                scheduleAttachmentRecheck();
                return;
            }
            addSwitcherView();
        } catch (RuntimeException error) {
            Log.e(TAG, "Taskbar attachment recovery failed", error);
            armRestoreRecovery();
            stopSelf();
        }
    };

    private final Runnable renderRunnable = () -> {
        renderScheduled = false;
        if (gestureActive) {
            renderAfterGesture = true;
            return;
        }
        try {
            renderTaskbar(true);
        } catch (RuntimeException error) {
            Log.e(TAG, "Taskbar render failed", error);
            expanded = false;
            recoverCollapsedLayout();
        }
    };

    private final Runnable windowUpdateRunnable = () -> {
        windowUpdateScheduled = false;
        updateViewLayoutNow();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (!SwitcherController.isEnabled(this)
                || !SwitcherController.hasOverlayPermission(this)) {
            stopSelf();
            return;
        }
        registerPackageChangeReceiver();
        cachedHubIcon = loadHubIcon();
        requestTargetRefresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SwitcherController.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!SwitcherController.isEnabled(this)
                || !SwitcherController.hasOverlayPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (switcherView == null) {
            try {
                addSwitcherView();
            } catch (RuntimeException error) {
                Log.e(TAG, "Taskbar restore failed; retry scheduled", error);
                if (removeSwitcherViewImmediately()) {
                    armRestoreRecovery();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                Log.w(
                        TAG,
                        "Taskbar root removal is uncertain; keeping ownership "
                                + "and retrying instead of creating a duplicate"
                );
                scheduleAttachmentRecheck();
                return START_STICKY;
            }
        } else if (!switcherView.isAttachedToWindow()) {
            // addView() attachment can be transient on the RC 2 vendor build.
            // Recheck after a frame instead of dropping the Java reference and
            // leaking the old WindowManager root.
            scheduleAttachmentRecheck();
        } else {
            cancelAttachmentRecheck();
            markAttachmentHealthy();
        }
        if (intent != null
                && ACTION_RELOAD_CONFIGURATION.equals(intent.getAction())) {
            reloadSavedConfiguration();
        }
        if (intent != null && ACTION_HEALTH_CHECK.equals(intent.getAction())) {
            // The periodic watchdog only verifies/re-attaches the overlay.
            // Installed applications cannot change without a package
            // broadcast, so a healthy check must not decode every icon again.
            firstStartCommand = false;
            return START_STICKY;
        }
        if (firstStartCommand) {
            firstStartCommand = false;
        } else if (intent != null
                && ACTION_REFRESH_TARGETS.equals(intent.getAction())) {
            requestTargetRefresh();
        }
        return START_STICKY;
    }

    private void reloadSavedConfiguration() {
        if (layoutParams == null || handleSize <= 0) {
            return;
        }
        edgeRight = SwitcherController.readEdgeRight(this, edgeRight);
        freePositionY = clamp(
                SwitcherController.readVerticalPosition(
                        this,
                        freePositionY
                ),
                edgeMargin,
                maxVerticalPosition(handleSize, edgeMargin)
        );
        scheduleRenderTaskbar();
    }

    /**
     * Some vendor window managers can detach an overlay without destroying its
     * foreground service. A non-null Java reference is therefore not proof
     * that the button is still visible. The watchdog rebuilds such a detached
     * overlay from scratch.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        armRestoreRecovery();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        synchronized (targetRefreshLock) {
            destroyed = true;
            targetRefreshPending = false;
            requestedTargetGeneration++;
        }
        targetDiscoveryExecutor.shutdownNow();
        unregisterPackageChangeReceiver();
        removeSwitcherViewImmediately();
        mainHandler.removeCallbacksAndMessages(null);
        stopForeground(true);
        // onDestroy is not guaranteed after a hard process kill, but when an
        // OEM does call it we use the opportunity to arm another recovery.
        // Explicit user disable writes enabled=false before stopService(), so
        // this cannot resurrect a switcher the user deliberately turned off.
        armRestoreRecovery();
        super.onDestroy();
    }

    private void armRestoreRecovery() {
        if (restoreRecoveryArmed) {
            return;
        }
        if (SwitcherController.isEnabled(this)
                && SwitcherController.hasOverlayPermission(this)) {
            SwitcherController.scheduleRestoreRetry(this);
            restoreRecoveryArmed = true;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addSwitcherView() {
        if (switcherView != null) {
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            throw new IllegalStateException("WindowManager is unavailable");
        }

        handleSize = dp(52);
        edgeMargin = dp(8);
        edgeRight = SwitcherController.readEdgeRight(this, true);
        int savedY = SwitcherController.readVerticalPosition(this, dp(120));
        freePositionY = clamp(
                savedY,
                edgeMargin,
                maxVerticalPosition(handleSize, edgeMargin)
        );

        layoutParams = new WindowManager.LayoutParams(
                handleSize,
                handleSize,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = edgeX(handleSize);
        layoutParams.y = clamp(
                savedY,
                edgeMargin,
                maxVerticalPosition(handleSize, edgeMargin)
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setClipToPadding(false);

        SwitcherButton handle = new SwitcherButton();
        handle.setImageResource(R.drawable.ic_taskbar_brand);
        handle.setImageTintList(null);
        handle.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int handlePadding = dp(10);
        handle.setPadding(
                handlePadding,
                handlePadding,
                handlePadding,
                handlePadding
        );
        handle.setContentDescription(
                getString(R.string.switcher_content_description)
        );
        handle.setBackgroundResource(R.drawable.switcher_button_background);
        handle.setElevation(dp(6));
        handle.setOnClickListener(ignored -> setExpanded(!expanded));
        handle.setOnTouchListener(new DragTouchListener());

        switcherView = root;
        handleView = handle;
        renderTaskbar(false);
        try {
            windowManager.addView(root, layoutParams);
        } catch (RuntimeException error) {
            removeSwitcherViewImmediately();
            throw error;
        }
        if (root.isAttachedToWindow()) {
            markAttachmentHealthy();
        } else {
            scheduleAttachmentRecheck();
        }
    }

    private void scheduleAttachmentRecheck() {
        if (attachmentRecheckScheduled) {
            return;
        }
        attachmentRecheckScheduled = true;
        long delayMillis =
                attachmentRecheckDelayMillis(attachmentRecheckAttempt);
        if (attachmentRecheckAttempt
                < ATTACHMENT_RECHECK_DELAYS_MS.length - 1) {
            attachmentRecheckAttempt++;
        }
        mainHandler.postDelayed(
                attachmentRecheckRunnable,
                delayMillis
        );
    }

    private void cancelAttachmentRecheck() {
        if (!attachmentRecheckScheduled) {
            return;
        }
        mainHandler.removeCallbacks(attachmentRecheckRunnable);
        attachmentRecheckScheduled = false;
    }

    static long attachmentRecheckDelayMillis(int attempt) {
        int index = Math.max(
                0,
                Math.min(attempt, ATTACHMENT_RECHECK_DELAYS_MS.length - 1)
        );
        return ATTACHMENT_RECHECK_DELAYS_MS[index];
    }

    private void markAttachmentHealthy() {
        attachmentRecheckAttempt = 0;
        restoreRecoveryArmed = false;
        SwitcherController.markRestoreHealthy(this);
    }

    /**
     * Removes the exact root previously passed to WindowManager before any
     * reference is cleared. This prevents an orphan overlay from remaining at
     * the old position when the vendor window manager reports a transient
     * detach and the service rebuilds the taskbar.
     */
    private boolean removeSwitcherViewImmediately() {
        cancelAttachmentRecheck();
        LinearLayout oldView = switcherView;
        if (oldView != null) {
            oldView.removeCallbacks(renderRunnable);
            oldView.removeCallbacks(windowUpdateRunnable);
            if (windowManager != null) {
                try {
                    windowManager.removeViewImmediate(oldView);
                } catch (IllegalArgumentException ignored) {
                    // "View not attached" is definitive only when the View
                    // itself agrees. Otherwise retain ownership and retry:
                    // clearing references here could orphan a visible root.
                    if (oldView.isAttachedToWindow()) {
                        Log.w(
                                TAG,
                                "WindowManager rejected removal of an attached "
                                        + "Taskbar root"
                        );
                        return false;
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not remove old Taskbar root", error);
                    return false;
                }
            }
        }
        switcherView = null;
        handleView = null;
        layoutParams = null;
        renderScheduled = false;
        windowUpdateScheduled = false;
        gestureActive = false;
        renderAfterGesture = false;
        return true;
    }

    /**
     * Rebuilds the overlay so app buttons always reflect currently installed,
     * launcher-capable packages.
     */
    private void renderTaskbar(boolean updateAttachedView) {
        if (switcherView == null || handleView == null || layoutParams == null) {
            return;
        }

        TaskbarSettings settings =
                SwitcherController.readTaskbarSettings(this);
        detachFromParent(handleView);
        switcherView.removeAllViews();
        switcherView.setPadding(0, 0, 0, 0);

        if (!expanded) {
            switcherView.setBackgroundColor(0x00000000);
            switcherView.addView(
                    handleView,
                    new LinearLayout.LayoutParams(handleSize, handleSize)
            );
            layoutParams.width = handleSize;
            layoutParams.height = handleSize;
            handleOffsetY = 0;
            if (settings.getAnchor() == TaskbarSettings.Anchor.FREE) {
                layoutParams.y = freePositionY;
            } else {
                applySavedAnchor(settings.getAnchor(), handleSize);
            }
            layoutParams.x = edgeX(handleSize);
            layoutParams.y = clamp(
                    layoutParams.y,
                    edgeMargin,
                    maxVerticalPosition(handleSize, edgeMargin)
            );
            if (updateAttachedView) {
                updateViewLayout();
            }
            return;
        }

        List<ResolvedTarget> targets = targetSnapshot;
        int panelPadding = dp(4);
        int maximumWidth = Math.max(
                handleSize,
                screenWidth() - edgeMargin * 2
        );
        int maximumAppsViewportWidth = Math.max(
                1,
                maximumWidth - handleSize - panelPadding * 2
        );
        int capacity = settings.getCapacity();
        boolean hasOverflow = TaskbarLayoutPolicy.hasOverflow(
                targets.size(),
                capacity
        );
        int visibleApplicationCount =
                TaskbarLayoutPolicy.visibleApplicationCount(
                        targets.size(),
                        capacity
                );
        int slotCount = visibleApplicationCount + (hasOverflow ? 1 : 0);
        int usedColumns = TaskbarLayoutPolicy.gridColumns(
                slotCount,
                settings.getColumns()
        );
        int usedRows = TaskbarLayoutPolicy.gridRows(
                slotCount,
                usedColumns
        );
        int availablePanelHeight = Math.max(
                handleSize,
                screenHeight() - edgeMargin * 2
        );
        int buttonHeight = Math.max(
                1,
                Math.min(
                        handleSize,
                        availablePanelHeight / Math.max(1, usedRows)
                )
        );
        int buttonWidth = calculateButtonWidth(
                maximumAppsViewportWidth,
                usedColumns,
                dp(50)
        );
        int appsViewportWidth = TaskbarLayoutPolicy.gridWidth(
                buttonWidth,
                usedColumns
        );
        int appsViewportHeight = TaskbarLayoutPolicy.expandedHeight(
                buttonHeight,
                usedRows
        );
        int expandedHeight = Math.max(handleSize, appsViewportHeight);
        int expandedWidth = Math.min(
                maximumWidth,
                handleSize + panelPadding * 2 + appsViewportWidth
        );

        switcherView.setBackgroundResource(R.drawable.switcher_panel_background);
        switcherView.setPadding(
                panelPadding,
                0,
                panelPadding,
                0
        );

        positionExpandedPanel(settings.getAnchor(), expandedHeight);
        if (!edgeRight) {
            addHandle(expandedHeight, handleOffsetY);
        }
        LinearLayout appsGrid = createAppsGrid(
                targets,
                visibleApplicationCount,
                hasOverflow,
                usedRows,
                usedColumns,
                buttonWidth,
                buttonHeight
        );
        switcherView.addView(
                appsGrid,
                new LinearLayout.LayoutParams(
                        appsViewportWidth,
                        appsViewportHeight
                )
        );
        if (edgeRight) {
            addHandle(expandedHeight, handleOffsetY);
        }

        layoutParams.width = expandedWidth;
        layoutParams.height = expandedHeight;
        layoutParams.x = edgeX(expandedWidth);
        layoutParams.y = clamp(
                layoutParams.y,
                edgeMargin,
                maxVerticalPosition(expandedHeight, edgeMargin)
        );
        if (updateAttachedView) {
            updateViewLayout();
        }
    }

    private LinearLayout createAppsGrid(
            List<ResolvedTarget> targets,
            int visibleApplicationCount,
            boolean hasOverflow,
            int usedRows,
            int usedColumns,
            int buttonWidth,
            int buttonHeight
    ) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(Gravity.CENTER_VERTICAL);
        int slotCount = visibleApplicationCount + (hasOverflow ? 1 : 0);

        for (int columnIndex = 0;
             columnIndex < usedColumns;
             columnIndex++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int rowIndex = 0; rowIndex < usedRows; rowIndex++) {
                int slotIndex = rowIndex * usedColumns + columnIndex;
                if (slotIndex >= slotCount) {
                    break;
                }
                View slot = slotIndex < visibleApplicationCount
                        ? createAppButton(
                                targets.get(slotIndex),
                                buttonWidth
                        )
                        : createMoreAppsButton(buttonWidth);
                column.addView(
                        slot,
                        new LinearLayout.LayoutParams(
                                buttonWidth,
                                buttonHeight
                        )
                );
            }
            grid.addView(
                    column,
                    new LinearLayout.LayoutParams(
                            buttonWidth,
                            usedRows * buttonHeight
                    )
            );
        }
        return grid;
    }

    private void addHandle(int railHeight, int offsetY) {
        FrameLayout rail = new FrameLayout(this);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                handleSize,
                handleSize,
                Gravity.TOP | Gravity.START
        );
        handleParams.topMargin = clamp(
                offsetY,
                0,
                Math.max(0, railHeight - handleSize)
        );
        rail.addView(handleView, handleParams);
        switcherView.addView(
                rail,
                new LinearLayout.LayoutParams(handleSize, railHeight)
        );
    }

    /**
     * A nested child remains parented to its rail after the rail itself is
     * removed from the root. Detach the reusable handle explicitly before
     * rebuilding the hierarchy; ViewGroup.addView rejects a child that still
     * has any parent.
     */
    static void detachFromParent(View view) {
        if (view == null) {
            return;
        }
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private View createAppButton(ResolvedTarget resolved, int width) {
        AppTarget target = resolved.target;
        FrameLayout container = new FrameLayout(this);
        ImageButton button = new ImageButton(this);
        if (resolved.icon == null) {
            button.setImageResource(R.drawable.ic_taskbar_app_generic);
        } else {
            button.setImageDrawable(resolved.icon);
        }
        button.setImageTintList(null);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int iconPadding = Math.min(dp(8), Math.max(0, width / 5));
        button.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        button.setContentDescription(
                getString(R.string.switcher_open_app, target.displayName)
                        + ". "
                        + getString(resolved.category.getLabelResId())
        );
        button.setBackgroundResource(R.drawable.switcher_app_button_background);
        button.setOnClickListener(ignored -> launchTarget(target));
        container.addView(
                button,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        View categoryMarker = new View(this);
        GradientDrawable markerBackground = new GradientDrawable();
        markerBackground.setShape(GradientDrawable.RECTANGLE);
        markerBackground.setCornerRadius(dp(2));
        markerBackground.setColor(getColor(resolved.category.getColorResId()));
        categoryMarker.setBackground(markerBackground);
        categoryMarker.setClickable(false);
        categoryMarker.setFocusable(false);
        FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(
                Math.max(dp(14), width / 2),
                dp(3),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        markerParams.bottomMargin = dp(3);
        container.addView(categoryMarker, markerParams);
        return container;
    }

    private View createMoreAppsButton(int width) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_taskbar_more);
        button.setImageTintList(null);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        int padding = Math.min(dp(11), Math.max(0, width / 4));
        button.setPadding(padding, padding, padding, padding);
        button.setBackgroundResource(R.drawable.switcher_app_button_background);
        button.setContentDescription(getString(R.string.switcher_more_apps));
        button.setOnClickListener(ignored -> openAllApps());
        return button;
    }

    private List<ResolvedTarget> availableTargets() {
        TaskbarSettings settings =
                SwitcherController.readTaskbarSettings(this);
        Map<String, String> categoryOverrides =
                SwitcherController.readPackageCategoryOverrides(this);
        List<ResolvedTarget> result = new ArrayList<>();
        result.add(new ResolvedTarget(
                HUB,
                cachedHubIcon,
                TaskbarCategory.FLIGHT
        ));

        ResolvedTarget djiFly = null;
        List<ResolvedTarget> userApplications = new ArrayList<>();
        Set<String> hiddenPackages =
                SwitcherController.readHiddenPackages(this);
        for (LaunchableAppDiscovery.Entry entry
                : LaunchableAppDiscovery.query(this)) {
            String packageName = entry.getPackageName();
            if (getPackageName().equals(packageName)) {
                continue;
            }
            AppTarget target = new AppTarget(
                    entry.getDisplayName(),
                    false,
                    entry.getComponentName(),
                    entry.getLaunchCategory()
            );
            ResolvedTarget resolved =
                    new ResolvedTarget(
                            target,
                            entry.getIcon(),
                            TaskbarCategory.resolve(
                                    packageName,
                                    categoryOverrides.get(packageName)
                            )
                    );
            if (isDjiFlyPackage(packageName)) {
                if (djiFly == null) {
                    djiFly = resolved;
                }
                continue;
            }
            if (!shouldIncludeDiscoveredApplication(
                    getPackageName(),
                    packageName,
                    entry.getApplicationInfo().flags
            )) {
                continue;
            }
            if (hiddenPackages.contains(packageName)) {
                continue;
            }
            userApplications.add(resolved);
        }
        Collections.sort(
                userApplications,
                (left, right) -> compareUserTargets(
                        left,
                        right,
                        settings.getSortMode()
                )
        );
        if (djiFly != null) {
            result.add(djiFly);
        }
        result.addAll(userApplications);
        return result;
    }

    private static int compareUserTargets(
            ResolvedTarget left,
            ResolvedTarget right,
            TaskbarSettings.SortMode sortMode
    ) {
        if (sortMode == TaskbarSettings.SortMode.CATEGORIES) {
            int byCategory = Integer.compare(
                    left.category.ordinal(),
                    right.category.ordinal()
            );
            if (byCategory != 0) {
                return byCategory;
            }
        }
        int byLabel = left.target.displayName.compareToIgnoreCase(
                right.target.displayName
        );
        if (byLabel != 0) {
            return byLabel;
        }
        String leftPackage = left.target.componentName == null
                ? ""
                : left.target.componentName.getPackageName();
        String rightPackage = right.target.componentName == null
                ? ""
                : right.target.componentName.getPackageName();
        return leftPackage.compareToIgnoreCase(rightPackage);
    }

    /**
     * Requests an asynchronous application scan. Repeated package broadcasts
     * are coalesced while the single worker is busy, so a burst of install or
     * update events produces at most one follow-up scan.
     */
    private void requestTargetRefresh() {
        synchronized (targetRefreshLock) {
            if (destroyed) {
                return;
            }
            requestedTargetGeneration++;
            if (targetRefreshRunning) {
                targetRefreshPending = true;
                return;
            }
            targetRefreshRunning = true;
            targetRefreshPending = false;
        }

        try {
            targetDiscoveryExecutor.execute(this::runTargetRefreshLoop);
        } catch (RejectedExecutionException error) {
            synchronized (targetRefreshLock) {
                targetRefreshRunning = false;
                targetRefreshPending = false;
            }
            if (!destroyed) {
                Log.w(TAG, "Application discovery worker rejected refresh", error);
            }
        }
    }

    private void runTargetRefreshLoop() {
        while (true) {
            long generation;
            synchronized (targetRefreshLock) {
                if (destroyed) {
                    targetRefreshRunning = false;
                    targetRefreshPending = false;
                    return;
                }
                generation = requestedTargetGeneration;
                targetRefreshPending = false;
            }

            List<ResolvedTarget> discovered = null;
            try {
                discovered = Collections.unmodifiableList(
                        new ArrayList<>(availableTargets())
                );
            } catch (RuntimeException error) {
                Log.e(TAG, "Application discovery failed", error);
            }

            synchronized (targetRefreshLock) {
                if (destroyed) {
                    targetRefreshRunning = false;
                    targetRefreshPending = false;
                    return;
                }
                if (targetRefreshPending
                        || generation != requestedTargetGeneration) {
                    // A newer request superseded this scan. Do not publish a
                    // stale list; immediately run one final coalesced scan.
                    targetRefreshPending = false;
                    continue;
                }
                targetRefreshRunning = false;
            }

            if (discovered != null) {
                List<ResolvedTarget> snapshot = discovered;
                mainHandler.post(
                        () -> publishTargetSnapshot(generation, snapshot)
                );
            }
            return;
        }
    }

    private void publishTargetSnapshot(
            long generation,
            List<ResolvedTarget> snapshot
    ) {
        synchronized (targetRefreshLock) {
            if (destroyed
                    || generation != requestedTargetGeneration
                    || generation <= publishedTargetGeneration) {
                return;
            }
            publishedTargetGeneration = generation;
        }
        targetSnapshot = snapshot;
        // A collapsed handle has no application icons. Keep the fresh
        // snapshot and build its grid only when the user expands the panel.
        if (expanded) {
            scheduleRenderTaskbar();
        }
    }

    static boolean isDjiFlyPackage(String packageName) {
        return "dji.go.v5".equals(packageName)
                || "dji.go.v4".equals(packageName)
                || "dji.pilot".equals(packageName);
    }

    static boolean shouldIncludeDiscoveredApplication(
            String ownPackage,
            String packageName,
            int applicationFlags
    ) {
        return packageName != null
                && !packageName.equals(ownPackage)
                && !isDjiFlyPackage(packageName)
                && !LaunchableAppDiscovery.isUnremovableSystemFlags(
                applicationFlags
        );
    }

    private Drawable loadHubIcon() {
        try {
            return getApplicationInfo().loadIcon(getPackageManager());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void registerPackageChangeReceiver() {
        if (packageReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        try {
            registerReceiver(packageChangeReceiver, filter);
            packageReceiverRegistered = true;
            IntentFilter availabilityFilter = new IntentFilter();
            availabilityFilter.addAction(
                    Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
            );
            availabilityFilter.addAction(
                    Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                availabilityFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
                availabilityFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
            }
            registerReceiver(packageChangeReceiver, availabilityFilter);
        } catch (RuntimeException error) {
            Log.w(TAG, "Package refresh receiver is unavailable", error);
        }
    }

    private void unregisterPackageChangeReceiver() {
        if (!packageReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(packageChangeReceiver);
        } catch (RuntimeException error) {
            Log.w(TAG, "Package refresh receiver was already removed", error);
        }
        packageReceiverRegistered = false;
    }

    private void setExpanded(boolean newExpanded) {
        expanded = newExpanded;
        scheduleRenderTaskbar();
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private final int touchSlop = ViewConfiguration.get(
                FloatingSwitcherService.this
        ).getScaledTouchSlop();

        private float downRawX;
        private float downRawY;
        private float currentRawX;
        private int downWindowX;
        private int downWindowY;
        private int downHandleOffsetY;
        private boolean dragging;
        private int activePointerId = INVALID_POINTER_ID;

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View view, MotionEvent event) {
            if (layoutParams == null || windowManager == null) {
                return false;
            }

            try {
                return handleTouchSafely(view, event);
            } catch (RuntimeException error) {
                Log.e(TAG, "Taskbar gesture failed", error);
                boolean wasDragging = dragging;
                resetGesture();
                if (wasDragging) {
                    scheduleSnapToEdge(edgeRight);
                }
                return true;
            }
        }

        private boolean handleTouchSafely(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activePointerId = event.getPointerId(0);
                    beginFromPointer(event, 0);
                    dragging = false;
                    gestureActive = true;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    // Keep the original active pointer; consume the event so
                    // Android doesn't cancel the gesture stream.
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int moveIndex = event.findPointerIndex(activePointerId);
                    if (moveIndex < 0) {
                        return true;
                    }
                    currentRawX = rawX(event, moveIndex);
                    float currentRawY = rawY(event, moveIndex);
                    float deltaX = currentRawX - downRawX;
                    float deltaY = currentRawY - downRawY;
                    if (!dragging
                            && Math.hypot(deltaX, deltaY) >= touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        int viewWidth = Math.max(handleSize, layoutParams.width);
                        layoutParams.x = clamp(
                                downWindowX + Math.round(deltaX),
                                edgeMargin,
                                Math.max(
                                        edgeMargin,
                                        screenWidth() - viewWidth - edgeMargin
                                )
                        );
                        if (expanded) {
                            moveExpandedPanelToHandleY(
                                    downWindowY
                                            + downHandleOffsetY
                                            + Math.round(deltaY)
                            );
                        } else {
                            layoutParams.y = clamp(
                                    downWindowY + Math.round(deltaY),
                                    edgeMargin,
                                    maxVerticalPosition(
                                            handleSize,
                                            edgeMargin
                                    )
                            );
                        }
                        scheduleWindowUpdate();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    int liftedIndex = event.getActionIndex();
                    if (event.getPointerId(liftedIndex) == activePointerId) {
                        int replacementIndex = liftedIndex == 0 ? 1 : 0;
                        if (replacementIndex < event.getPointerCount()) {
                            activePointerId = event.getPointerId(replacementIndex);
                            beginFromPointer(event, replacementIndex);
                        } else {
                            activePointerId = INVALID_POINTER_ID;
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    int upIndex = event.findPointerIndex(activePointerId);
                    if (upIndex < 0) {
                        upIndex = 0;
                    }
                    currentRawX = rawX(event, upIndex);
                    boolean wasDragging = dragging;
                    resetGesture();
                    if (!wasDragging) {
                        view.performClick();
                    } else {
                        scheduleSnapToEdge(currentRawX >= screenWidth() / 2f);
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    boolean cancelledDrag = dragging;
                    resetGesture();
                    if (cancelledDrag) {
                        scheduleSnapToEdge(currentRawX >= screenWidth() / 2f);
                    }
                    return true;

                default:
                    return true;
            }
        }

        private void moveExpandedPanelToHandleY(int requestedHandleY) {
            int expandedHeight = Math.max(handleSize, layoutParams.height);
            int maximumPanelY =
                    maxVerticalPosition(expandedHeight, edgeMargin);
            int maximumHandleY =
                    maxVerticalPosition(handleSize, edgeMargin);
            int maximumHandleOffset =
                    Math.max(0, expandedHeight - handleSize);
            int handleY = clamp(
                    requestedHandleY,
                    edgeMargin,
                    maximumHandleY
            );

            // Keep the handle under the finger. Near either screen boundary
            // the panel stays on-screen while the handle travels inside its
            // full-height rail instead of lagging behind the gesture.
            int panelY = clamp(
                    handleY - downHandleOffsetY,
                    edgeMargin,
                    maximumPanelY
            );
            layoutParams.y = panelY;
            handleOffsetY = clamp(
                    handleY - panelY,
                    0,
                    maximumHandleOffset
            );

            if (handleView != null
                    && handleView.getLayoutParams()
                    instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams handleParams =
                        (FrameLayout.LayoutParams) handleView.getLayoutParams();
                if (handleParams.topMargin != handleOffsetY) {
                    handleParams.topMargin = handleOffsetY;
                    handleView.setLayoutParams(handleParams);
                }
            }
        }

        private void beginFromPointer(MotionEvent event, int pointerIndex) {
            downRawX = rawX(event, pointerIndex);
            downRawY = rawY(event, pointerIndex);
            currentRawX = downRawX;
            downWindowX = layoutParams.x;
            downWindowY = layoutParams.y;
            downHandleOffsetY = handleOffsetY;
        }

        private void resetGesture() {
            gestureActive = false;
            dragging = false;
            activePointerId = INVALID_POINTER_ID;
            if (renderAfterGesture) {
                renderAfterGesture = false;
                scheduleRenderTaskbar();
            }
        }

        private float rawX(MotionEvent event, int pointerIndex) {
            return event.getX(pointerIndex) + event.getRawX() - event.getX(0);
        }

        private float rawY(MotionEvent event, int pointerIndex) {
            return event.getY(pointerIndex) + event.getRawY() - event.getY(0);
        }
    }

    private void scheduleSnapToEdge(boolean newEdgeRight) {
        edgeRight = newEdgeRight;
        // The expanded window can start above the handle because its grid has
        // multiple rows. Persist the handle's real screen Y, not the panel's
        // top edge, otherwise the button jumps after collapse/restart.
        freePositionY = clamp(
                layoutParams.y + handleOffsetY,
                edgeMargin,
                maxVerticalPosition(handleSize, edgeMargin)
        );
        SwitcherController.writePosition(this, edgeRight, freePositionY);
        scheduleRenderTaskbar();
    }

    private void positionExpandedPanel(
            TaskbarSettings.Anchor anchor,
            int expandedHeight
    ) {
        TaskbarSettings.Anchor safeAnchor = anchor == null
                ? TaskbarSettings.Anchor.FREE
                : anchor;
        int maximumPanelY =
                maxVerticalPosition(expandedHeight, edgeMargin);
        int maximumHandleY =
                maxVerticalPosition(handleSize, edgeMargin);
        int maximumHandleOffset =
                Math.max(0, expandedHeight - handleSize);

        switch (safeAnchor) {
            case TOP_LEFT:
                edgeRight = false;
                layoutParams.y = edgeMargin;
                handleOffsetY = 0;
                return;
            case TOP_RIGHT:
                edgeRight = true;
                layoutParams.y = edgeMargin;
                handleOffsetY = 0;
                return;
            case BOTTOM_LEFT:
                edgeRight = false;
                layoutParams.y = maximumPanelY;
                handleOffsetY = maximumHandleOffset;
                return;
            case BOTTOM_RIGHT:
                edgeRight = true;
                layoutParams.y = maximumPanelY;
                handleOffsetY = maximumHandleOffset;
                return;
            case FREE:
            default:
                int desiredHandleY = clamp(
                        freePositionY,
                        edgeMargin,
                        maximumHandleY
                );
                int preferredHandleOffset = maximumHandleOffset / 2;
                int panelY = clamp(
                        desiredHandleY - preferredHandleOffset,
                        edgeMargin,
                        maximumPanelY
                );
                layoutParams.y = panelY;
                handleOffsetY = clamp(
                        desiredHandleY - panelY,
                        0,
                        maximumHandleOffset
                );
        }
    }

    private void applySavedAnchor(
            TaskbarSettings.Anchor anchor,
            int currentViewHeight
    ) {
        if (anchor == null || anchor == TaskbarSettings.Anchor.FREE) {
            return;
        }
        switch (anchor) {
            case TOP_RIGHT:
                edgeRight = true;
                layoutParams.y = edgeMargin;
                break;
            case BOTTOM_RIGHT:
                edgeRight = true;
                layoutParams.y = maxVerticalPosition(
                        currentViewHeight,
                        edgeMargin
                );
                break;
            case TOP_LEFT:
                edgeRight = false;
                layoutParams.y = edgeMargin;
                break;
            case BOTTOM_LEFT:
                edgeRight = false;
                layoutParams.y = maxVerticalPosition(
                        currentViewHeight,
                        edgeMargin
                );
                break;
            case FREE:
            default:
                break;
        }
    }

    private void scheduleRenderTaskbar() {
        if (destroyed || switcherView == null || renderScheduled) {
            return;
        }
        renderScheduled = true;
        if (!switcherView.post(renderRunnable)) {
            renderScheduled = false;
        }
    }

    private void scheduleWindowUpdate() {
        if (switcherView == null || windowUpdateScheduled) {
            return;
        }
        windowUpdateScheduled = true;
        switcherView.postOnAnimation(windowUpdateRunnable);
    }

    private void updateViewLayout() {
        scheduleWindowUpdate();
    }

    private void updateViewLayoutNow() {
        if (windowManager == null || switcherView == null || layoutParams == null) {
            return;
        }
        if (!switcherView.isAttachedToWindow()) {
            return;
        }
        try {
            windowManager.updateViewLayout(switcherView, layoutParams);
        } catch (RuntimeException error) {
            Log.e(TAG, "WindowManager update failed", error);
            stopIfOverlayBecameUnavailable(error);
        }
    }

    private void recoverCollapsedLayout() {
        if (switcherView == null || handleView == null || layoutParams == null) {
            return;
        }
        try {
            detachFromParent(handleView);
            switcherView.removeAllViews();
            switcherView.setPadding(0, 0, 0, 0);
            switcherView.setBackgroundColor(0x00000000);
            switcherView.addView(
                    handleView,
                    new LinearLayout.LayoutParams(handleSize, handleSize)
            );
            layoutParams.width = handleSize;
            layoutParams.height = handleSize;
            handleOffsetY = 0;
            TaskbarSettings.Anchor anchor =
                    SwitcherController.readTaskbarSettings(this).getAnchor();
            if (anchor == TaskbarSettings.Anchor.FREE) {
                layoutParams.y = freePositionY;
            } else {
                applySavedAnchor(anchor, handleSize);
            }
            layoutParams.x = edgeX(handleSize);
            layoutParams.y = clamp(
                    layoutParams.y,
                    edgeMargin,
                    maxVerticalPosition(handleSize, edgeMargin)
            );
            scheduleWindowUpdate();
        } catch (RuntimeException error) {
            Log.e(TAG, "Collapsed taskbar recovery failed", error);
            stopIfOverlayBecameUnavailable(error);
        }
    }

    private void stopIfOverlayBecameUnavailable(RuntimeException error) {
        if (error instanceof SecurityException
                || error instanceof WindowManager.BadTokenException
                || !SwitcherController.hasOverlayPermission(this)) {
            stopSelf();
        }
    }

    private void launchTarget(AppTarget target) {
        Intent intent;
        if (target.hub) {
            intent = createHubIntent();
        } else if (target.componentName != null) {
            intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(target.launchCategory)
                    .setComponent(target.componentName);
        } else {
            intent = null;
        }
        if (intent == null) {
            Toast.makeText(
                    this,
                    getString(R.string.switcher_app_unavailable, target.displayName),
                    Toast.LENGTH_SHORT
            ).show();
            requestTargetRefresh();
            return;
        }

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        setExpanded(false);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    getString(R.string.switcher_app_launch_failed, target.displayName),
                    Toast.LENGTH_LONG
            ).show();
            requestTargetRefresh();
        }
    }

    private void openAllApps() {
        Intent intent = createHubIntent();
        intent.putExtra(MainActivity.EXTRA_OPEN_APPS_SECTION, true);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        setExpanded(false);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    R.string.switcher_start_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private Intent createHubIntent() {
        return new Intent(this, MainActivity.class);
    }

    private Notification createNotification() {
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_HUB,
                createHubIntent().addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
                pendingIntentFlags()
        );

        Intent stopIntent = new Intent(this, FloatingSwitcherService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                REQUEST_STOP,
                stopIntent,
                pendingIntentFlags()
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_switcher_notification)
                .setContentTitle(getString(R.string.switcher_notification_title))
                .setContentText(getString(R.string.switcher_notification_text))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                getString(R.string.switcher_notification_stop),
                                stopPendingIntent
                        ).build()
                )
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.switcher_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.switcher_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @SuppressLint("ObsoleteSdkInt")
    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int edgeX(int viewWidth) {
        return edgeRight
                ? Math.max(edgeMargin, screenWidth() - viewWidth - edgeMargin)
                : edgeMargin;
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int maxVerticalPosition(int viewSize, int margin) {
        return Math.max(margin, screenHeight() - viewSize - margin);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static int calculateButtonWidth(
            int availableWidth,
            int targetCount,
            int maximumButtonWidth
    ) {
        int safeTargetCount = Math.max(1, targetCount);
        return Math.min(
                Math.max(1, maximumButtonWidth),
                Math.max(1, availableWidth / safeTargetCount)
        );
    }

    static int calculateScrollableViewportWidth(
            int maximumViewportWidth,
            int targetCount,
            int buttonWidth
    ) {
        long contentWidth =
                (long) Math.max(1, targetCount) * Math.max(1, buttonWidth);
        return (int) Math.min(
                Math.max(1, maximumViewportWidth),
                contentWidth
        );
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class SwitcherButton extends ImageButton {
        SwitcherButton() {
            super(FloatingSwitcherService.this);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
