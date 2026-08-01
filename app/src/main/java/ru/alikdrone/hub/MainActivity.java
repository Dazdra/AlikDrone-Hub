package ru.alikdrone.hub;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String TAG = "AlikDroneHub";
    static final String EXTRA_OPEN_APPS_SECTION =
            "ru.alikdrone.hub.extra.OPEN_APPS_SECTION";
    private static final int REQUEST_PICK_APK = 1001;
    private static final int REQUEST_UNKNOWN_SOURCES = 1002;
    private static final int REQUEST_OVERLAY_PERMISSION = 1003;
    private static final int REQUEST_UNINSTALL_APP = 1004;
    private static final int REQUEST_PICK_APK_TREE = 1005;
    private static final String STATE_PENDING_APK = "pending_apk";
    private static final String STATE_PENDING_SPLIT_PACKAGE =
            "pending_split_package";
    private static final String QUEUE_PREFERENCES = "apk_download_queue";
    private static final String KEY_DOWNLOAD_QUEUE = "entries";
    // Package/download/SAF events already trigger immediate scans. The
    // periodic pass is only a safety net; a short interval would continuously
    // re-hash large APK collections on RC 2 storage.
    private static final long INVENTORY_REFRESH_INTERVAL_MS = 60_000L;
    // Package broadcasts and download/SAF callbacks refresh immediately. This
    // is only a vendor-ROM safety net, not a reason to re-query PackageManager
    // and hash every APK several times per minute while the dialog is open.
    private static final long CATALOG_REFRESH_INTERVAL_MS = 60_000L;
    private static final long CATALOG_BUSY_RETRY_MS = 1_000L;
    private static final long DOWNLOAD_PROGRESS_POLL_MS = 1_500L;
    private static final long COMPLETED_PANEL_HIDE_DELAY_MS = 1_200L;
    private static final String APK_MIME =
            "application/vnd.android.package-archive";
    // Intent.CATEGORY_APP_FILES was added in API 29. Keeping the literal here
    // avoids loading a newer framework field on the RC 2's older Android base.
    private static final String CATEGORY_APP_FILES =
            "android.intent.category.APP_FILES";

    private Uri pendingApkUri;
    private String pendingSplitPackage;
    private ApkDownloadController downloadController;
    private ApkInventoryScanner inventoryScanner;
    private final Handler downloadPollHandler = new Handler(Looper.getMainLooper());
    private final Handler inventoryRefreshHandler =
            new Handler(Looper.getMainLooper());
    private final Handler catalogRefreshHandler =
            new Handler(Looper.getMainLooper());
    private final Handler installedAppsCallbackHandler =
            new Handler(Looper.getMainLooper());
    private final ExecutorService installedAppsExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService safAccessExecutor =
            Executors.newSingleThreadExecutor();
    private final Object installedAppsRefreshLock = new Object();
    InstalledAppsQuery installedAppsQuery = this::queryManagedInstalledApps;
    private AlertDialog activeCatalogDialog;
    private LinearLayout activeCatalogList;
    private LayoutInflater activeCatalogInflater;
    private String activeCatalogFingerprint;
    private List<InstalledApp> installedAppsSnapshot = Collections.emptyList();
    private Set<String> knownInstalledCatalogPackagesSnapshot =
            Collections.emptySet();
    private boolean installedAppsSnapshotReady;
    private boolean installedAppsRefreshRunning;
    private boolean installedAppsRefreshPending;
    private long installedAppsRefreshGeneration;
    private List<ApkInventoryScanner.Item> lastInventoryItems =
            Collections.emptyList();
    private ApkInventoryScanner.ScanResult lastInventoryResult;
    private boolean inventorySnapshotReady;
    private String mainAppsFingerprint;
    private boolean pendingOpenAppsSection;
    private final Deque<AppCatalog.Entry> downloadQueue = new ArrayDeque<>();
    private volatile boolean destroyed;
    private boolean firstResume = true;
    private boolean packageReceiverRegistered;
    private final BroadcastReceiver packageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (inventoryScanner != null) {
                inventoryScanner.invalidatePackageState();
            }
            requestInstalledAppsRefresh();
            refreshStatuses();
            refreshInstallBootstrap();
            refreshApkInventory();
            scheduleCatalogRefresh(0L);
        }
    };
    private final Runnable downloadPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (downloadController != null && downloadController.hasPendingDownload()) {
                downloadController.refreshPending();
                downloadPollHandler.postDelayed(
                        this,
                        DOWNLOAD_PROGRESS_POLL_MS
                );
            }
        }
    };
    private final Runnable hideCompletedDownloadPanel = () -> {
        if (destroyed
                || downloadController == null
                || downloadController.hasPendingDownload()
                || !downloadQueue.isEmpty()) {
            return;
        }
        View panel = findViewById(R.id.download_panel);
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
        ProgressBar bar = findViewById(R.id.progress_download);
        bar.setProgress(0);
        bar.setVisibility(View.GONE);
    };
    private final Runnable inventoryRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatuses();
            refreshApkInventory();
            inventoryRefreshHandler.postDelayed(
                    this,
                    INVENTORY_REFRESH_INTERVAL_MS
            );
        }
    };
    private final Runnable inventoryRetryRunnable = this::refreshApkInventory;
    private final Runnable catalogRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            AlertDialog dialog = activeCatalogDialog;
            LinearLayout list = activeCatalogList;
            LayoutInflater inflater = activeCatalogInflater;
            if (dialog == null
                    || list == null
                    || inflater == null
                    || !dialog.isShowing()) {
                return;
            }
            requestCatalogInventory(dialog, list, inflater);
        }
    };

    private static final class InstalledApp {
        final String displayName;
        final String packageName;
        final PackageInfo packageInfo;
        final Drawable icon;
        final ComponentName componentName;
        final String launchCategory;

        InstalledApp(
                String displayName,
                String packageName,
                PackageInfo packageInfo,
                Drawable icon,
                ComponentName componentName,
                String launchCategory
        ) {
            this.displayName = displayName;
            this.packageName = packageName;
            this.packageInfo = packageInfo;
            this.icon = icon;
            this.componentName = componentName;
            this.launchCategory = launchCategory;
        }
    }

    static final class SplitInstallToastListener
            implements SplitPackageInstaller.Listener {
        private final Context applicationContext;

        SplitInstallToastListener(Context context) {
            Context candidate = context.getApplicationContext();
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "Application context is unavailable"
                );
            }
            applicationContext = candidate;
        }

        @Override
        public void onSubmitted() {
            Toast.makeText(
                    applicationContext,
                    "Discord готов — подтвердите установку.",
                    Toast.LENGTH_LONG
            ).show();
        }

        @Override
        public void onFailure(String reason) {
            Toast.makeText(
                    applicationContext,
                    "Не удалось подготовить Discord: " + reason,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    interface InstalledAppsQuery {
        InstalledAppsSnapshot query();
    }

    static final class InstalledAppsSnapshot {
        final List<InstalledApp> apps;
        final Set<String> knownCatalogPackages;

        InstalledAppsSnapshot(
                List<InstalledApp> apps,
                Set<String> knownCatalogPackages
        ) {
            this.apps = Collections.unmodifiableList(
                    new ArrayList<>(apps)
            );
            this.knownCatalogPackages = Collections.unmodifiableSet(
                    new HashSet<>(knownCatalogPackages)
            );
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pendingOpenAppsSection = getIntent().getBooleanExtra(
                EXTRA_OPEN_APPS_SECTION,
                false
        );
        downloadController = new ApkDownloadController(
                this,
                new ApkDownloadController.Listener() {
                    @Override
                    public void onStatus(ApkDownloadController.Status status) {
                        runOnUiThread(() -> {
                            if (!destroyed) {
                                renderDownloadStatus(status);
                            }
                        });
                    }

                    @Override
                    public void onApkReady(AppCatalog.Entry entry, Uri contentUri) {
                        runOnUiThread(() -> {
                            if (destroyed) {
                                return;
                            }
                            refreshApkInventory();
                            scheduleCatalogRefresh(0L);
                            downloadPollHandler.post(() -> {
                                startNextQueuedDownload();
                                if (!downloadController.hasPendingDownload()
                                        && downloadQueue.isEmpty()) {
                                    downloadPollHandler.postDelayed(
                                            hideCompletedDownloadPanel,
                                            COMPLETED_PANEL_HIDE_DELAY_MS
                                    );
                                }
                            });
                        });
                    }
                }
        );
        inventoryScanner = new ApkInventoryScanner(this);
        restoreDownloadQueue();
        requestInstalledAppsRefresh();

        if (savedInstanceState != null) {
            String pendingUri = savedInstanceState.getString(STATE_PENDING_APK);
            if (pendingUri != null) {
                pendingApkUri = Uri.parse(pendingUri);
            }
            pendingSplitPackage = savedInstanceState.getString(
                    STATE_PENDING_SPLIT_PACKAGE
            );
        }

        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));

        findViewById(R.id.button_install_apk).setOnClickListener(
                view -> chooseApkFromStorage()
        );
        findViewById(R.id.button_open_dji_files).setOnClickListener(
                view -> openDjiFiles()
        );
        findViewById(R.id.button_install_settings).setOnClickListener(
                view -> openUnknownSourcesSettings()
        );
        findViewById(R.id.button_choose_apk_folder).setOnClickListener(
                view -> chooseApkFolder()
        );
        findViewById(R.id.button_forget_apk_folder).setOnClickListener(
                view -> forgetApkFolder()
        );
        findViewById(R.id.button_cancel_download).setOnClickListener(
                view -> downloadController.cancelPending()
        );
        findViewById(R.id.button_switcher).setOnClickListener(
                view -> toggleFloatingSwitcher()
        );
        findViewById(R.id.button_switcher_settings).setOnClickListener(
                view -> showTaskbarSettingsDialog()
        );
        findViewById(R.id.button_device_info).setOnClickListener(
                view -> startActivity(new Intent(this, DeviceInfoActivity.class))
        );
        findViewById(R.id.button_operations).setOnClickListener(
                view -> startActivity(new Intent(this, OperationsActivity.class))
        );
        findViewById(R.id.button_donation).setOnClickListener(
                view -> showDonationDialog()
        );
        SwitcherController.startIfEnabled(this);
        refreshHubVersion();
        refreshStatuses();
        refreshInstallBootstrap();
        refreshSwitcherState();
        downloadController.start();
        downloadPollHandler.post(this::startNextQueuedDownload);
        maybeShowSwitcherOnboarding();
        scrollToAppsSectionIfRequested();
        animateScreenIn(findViewById(R.id.main_scroll));
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerPackageChangeReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(
                EXTRA_OPEN_APPS_SECTION,
                false
        )) {
            pendingOpenAppsSection = true;
            scrollToAppsSectionIfRequested();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
        } else {
            // Returning from Package Installer/settings may not emit a
            // reliable vendor broadcast. Force one fresh package-state view.
            inventoryScanner.invalidatePackageState();
            requestInstalledAppsRefresh();
            SwitcherController.startIfEnabled(this);
            refreshStatuses();
            refreshInstallBootstrap();
            refreshSwitcherState();
            downloadController.refreshPending();
        }
        startInventoryRefresh();
        completePendingSwitcherEnable();
        scheduleCatalogRefresh(0L);
    }

    @Override
    protected void onPause() {
        stopDownloadPolling();
        stopInventoryRefresh();
        stopCatalogRefresh();
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterPackageChangeReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        synchronized (installedAppsRefreshLock) {
            installedAppsRefreshGeneration++;
            installedAppsRefreshPending = false;
        }
        installedAppsCallbackHandler.removeCallbacksAndMessages(null);
        installedAppsExecutor.shutdownNow();
        safAccessExecutor.shutdownNow();
        stopDownloadPolling();
        downloadPollHandler.removeCallbacks(hideCompletedDownloadPanel);
        stopInventoryRefresh();
        stopCatalogRefresh();
        activeCatalogDialog = null;
        activeCatalogList = null;
        activeCatalogInflater = null;
        if (downloadController != null) {
            downloadController.close();
        }
        if (inventoryScanner != null) {
            inventoryScanner.close();
        }
        unregisterPackageChangeReceiver();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingApkUri != null) {
            outState.putString(STATE_PENDING_APK, pendingApkUri.toString());
        }
        if (pendingSplitPackage != null) {
            outState.putString(
                    STATE_PENDING_SPLIT_PACKAGE,
                    pendingSplitPackage
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_PICK_APK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (SecurityException ignored) {
                    // The one-time read grant is enough for Package Installer.
                }
            }
            continueApkInstall(uri);
            return;
        }

        if (requestCode == REQUEST_PICK_APK_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                connectApkFolder(data);
            } else {
                refreshInstallBootstrap();
            }
            return;
        }

        if (requestCode == REQUEST_UNKNOWN_SOURCES) {
            Uri uri = pendingApkUri;
            String splitPackage = pendingSplitPackage;
            pendingApkUri = null;
            pendingSplitPackage = null;
            InstallEnvironment.Snapshot snapshot =
                    InstallEnvironment.inspect(this);
            refreshInstallBootstrap(snapshot);
            if (uri == null) {
                return;
            }
            if (snapshot.hasPolicyWarning()) {
                showPolicyWarning(
                        snapshot,
                        () -> continueAfterPolicyWarning(
                                snapshot,
                                uri,
                                splitPackage
                        )
                );
                return;
            }
            if (snapshot.hasVerifiedInstallerHelper()) {
                launchPreparedInstaller(uri, splitPackage);
                return;
            }
            if (snapshot.needsSourcePermission()) {
                Toast.makeText(
                        this,
                        R.string.unknown_sources_denied,
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            launchPreparedInstaller(uri, splitPackage);
            return;
        }

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            SwitcherController.setEnablePending(this, false);
            if (SwitcherController.hasOverlayPermission(this)) {
                if (!SwitcherController.setEnabled(this, true)) {
                    Toast.makeText(
                            this,
                            R.string.switcher_start_failed,
                            Toast.LENGTH_LONG
                    ).show();
                }
            } else {
                Toast.makeText(
                        this,
                        R.string.switcher_permission_denied,
                        Toast.LENGTH_LONG
                ).show();
            }
            refreshSwitcherState();
            return;
        }

        if (requestCode == REQUEST_UNINSTALL_APP) {
            inventoryScanner.invalidatePackageState();
            refreshStatuses();
            refreshApkInventory();
        }
    }

    private void toggleFloatingSwitcher() {
        boolean hasPermission = SwitcherController.hasOverlayPermission(this);
        if (SwitcherController.isEnabled(this) && hasPermission) {
            SwitcherController.setEnabled(this, false);
            refreshSwitcherState();
            return;
        }
        if (SwitcherController.isEnabled(this)) {
            SwitcherController.setEnabled(this, false);
        }

        if (!hasPermission) {
            SwitcherController.markOnboardingShown(this);
            SwitcherController.setEnablePending(this, true);
            try {
                startActivityForResult(
                        SwitcherController.createOverlayPermissionIntent(this),
                        REQUEST_OVERLAY_PERMISSION
                );
                Toast.makeText(
                        this,
                        R.string.switcher_permission_required,
                        Toast.LENGTH_LONG
                ).show();
            } catch (ActivityNotFoundException error) {
                SwitcherController.setEnablePending(this, false);
                Toast.makeText(
                        this,
                        R.string.switcher_start_failed,
                        Toast.LENGTH_LONG
                ).show();
            }
            return;
        }

        if (!SwitcherController.setEnabled(this, true)) {
            Toast.makeText(
                    this,
                    R.string.switcher_start_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
        refreshSwitcherState();
    }

    private void maybeShowSwitcherOnboarding() {
        if (SwitcherController.isEnabled(this)
                || SwitcherController.wasOnboardingShown(this)
                || SwitcherController.isEnablePending(this)) {
            return;
        }

        SwitcherController.markOnboardingShown(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.switcher_onboarding_title)
                .setMessage(R.string.switcher_onboarding_message)
                .setPositiveButton(
                        R.string.switcher_onboarding_enable,
                        (dialog, which) -> beginSwitcherEnable()
                )
                .setNegativeButton(R.string.switcher_onboarding_later, null)
                .show();
    }

    private void beginSwitcherEnable() {
        if (SwitcherController.hasOverlayPermission(this)) {
            if (!SwitcherController.setEnabled(this, true)) {
                Toast.makeText(
                        this,
                        R.string.switcher_start_failed,
                        Toast.LENGTH_LONG
                ).show();
            }
            refreshSwitcherState();
            return;
        }

        SwitcherController.setEnablePending(this, true);
        try {
            startActivityForResult(
                    SwitcherController.createOverlayPermissionIntent(this),
                    REQUEST_OVERLAY_PERMISSION
            );
            Toast.makeText(
                    this,
                    R.string.switcher_permission_required,
                    Toast.LENGTH_LONG
            ).show();
        } catch (ActivityNotFoundException error) {
            SwitcherController.setEnablePending(this, false);
            Toast.makeText(
                    this,
                    R.string.switcher_start_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void completePendingSwitcherEnable() {
        if (!SwitcherController.isEnablePending(this)) {
            return;
        }
        SwitcherController.setEnablePending(this, false);
        if (!SwitcherController.hasOverlayPermission(this)) {
            Toast.makeText(
                    this,
                    R.string.switcher_permission_denied,
                    Toast.LENGTH_LONG
            ).show();
            refreshSwitcherState();
            return;
        }
        if (!SwitcherController.setEnabled(this, true)) {
            Toast.makeText(
                    this,
                    R.string.switcher_start_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
        refreshSwitcherState();
    }

    private void showTaskbarSettingsDialog() {
        TaskbarSettings current =
                SwitcherController.readTaskbarSettings(this);
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_taskbar_settings,
                null,
                false
        );
        Spinner rows = content.findViewById(R.id.spinner_taskbar_rows);
        Spinner columns = content.findViewById(R.id.spinner_taskbar_columns);
        Spinner sort = content.findViewById(R.id.spinner_taskbar_sort);
        Spinner anchor = content.findViewById(R.id.spinner_taskbar_anchor);

        bindSpinner(rows, Arrays.asList("1", "2", "3", "4"));
        bindSpinner(
                columns,
                Arrays.asList("3", "4", "5", "6", "7", "8")
        );
        bindSpinner(
                sort,
                Arrays.asList(
                        getString(R.string.switcher_sort_categories),
                        getString(R.string.switcher_sort_alphabetical)
                )
        );
        bindSpinner(
                anchor,
                Arrays.asList(
                        getString(R.string.switcher_anchor_free),
                        getString(R.string.switcher_anchor_top_left),
                        getString(R.string.switcher_anchor_top_right),
                        getString(R.string.switcher_anchor_bottom_left),
                        getString(R.string.switcher_anchor_bottom_right)
                )
        );

        rows.setSelection(current.getRows() - TaskbarSettings.MIN_ROWS);
        columns.setSelection(
                current.getColumns() - TaskbarSettings.MIN_COLUMNS
        );
        sort.setSelection(
                current.getSortMode()
                        == TaskbarSettings.SortMode.CATEGORIES
                        ? 0
                        : 1
        );
        anchor.setSelection(anchorSelection(current.getAnchor()));

        new AlertDialog.Builder(this)
                .setTitle(R.string.switcher_settings_title)
                .setView(content)
                .setPositiveButton(
                        R.string.switcher_apply,
                        (dialog, which) -> {
                            TaskbarSettings updated = new TaskbarSettings(
                                    rows.getSelectedItemPosition()
                                            + TaskbarSettings.MIN_ROWS,
                                    columns.getSelectedItemPosition()
                                            + TaskbarSettings.MIN_COLUMNS,
                                    sort.getSelectedItemPosition() == 0
                                            ? TaskbarSettings.SortMode.CATEGORIES
                                            : TaskbarSettings.SortMode.ALPHABETICAL,
                                    anchorFromSelection(
                                            anchor.getSelectedItemPosition()
                                    )
                            );
                            SwitcherController.setTaskbarSettings(
                                    MainActivity.this,
                                    updated
                            );
                            mainAppsFingerprint = null;
                            activeCatalogFingerprint = null;
                            refreshSwitcherState();
                            refreshStatuses();
                            scheduleCatalogRefresh(0L);
                            Toast.makeText(
                                    MainActivity.this,
                                    R.string.switcher_settings_saved,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void bindSpinner(Spinner spinner, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                values
        );
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );
        spinner.setAdapter(adapter);
    }

    private int anchorSelection(TaskbarSettings.Anchor anchor) {
        if (anchor == null) {
            return 0;
        }
        switch (anchor) {
            case TOP_LEFT:
                return 1;
            case TOP_RIGHT:
                return 2;
            case BOTTOM_LEFT:
                return 3;
            case BOTTOM_RIGHT:
                return 4;
            case FREE:
            default:
                return 0;
        }
    }

    private TaskbarSettings.Anchor anchorFromSelection(int selection) {
        switch (selection) {
            case 1:
                return TaskbarSettings.Anchor.TOP_LEFT;
            case 2:
                return TaskbarSettings.Anchor.TOP_RIGHT;
            case 3:
                return TaskbarSettings.Anchor.BOTTOM_LEFT;
            case 4:
                return TaskbarSettings.Anchor.BOTTOM_RIGHT;
            case 0:
            default:
                return TaskbarSettings.Anchor.FREE;
        }
    }

    private void refreshSwitcherState() {
        boolean enabled = SwitcherController.isEnabled(this)
                && SwitcherController.hasOverlayPermission(this);
        TextView statusView = findViewById(R.id.text_switcher_status);
        TextView layoutSummary =
                findViewById(R.id.text_switcher_layout_summary);
        Button button = findViewById(R.id.button_switcher);
        statusView.setText(
                enabled ? R.string.switcher_status_on : R.string.switcher_status_off
        );
        statusView.setTextColor(
                enabled
                        ? getColor(R.color.status_success)
                        : getColor(R.color.text_secondary)
        );
        button.setText(enabled ? R.string.switcher_disable : R.string.switcher_enable);
        TaskbarSettings settings =
                SwitcherController.readTaskbarSettings(this);
        layoutSummary.setText(getString(
                R.string.switcher_layout_summary,
                settings.getRows(),
                settings.getColumns(),
                getString(
                        settings.getSortMode()
                                == TaskbarSettings.SortMode.CATEGORIES
                                ? R.string.switcher_sort_categories
                                : R.string.switcher_sort_alphabetical
                ),
                getString(anchorLabel(settings.getAnchor()))
        ));
    }

    private int anchorLabel(TaskbarSettings.Anchor anchor) {
        if (anchor == null) {
            return R.string.switcher_anchor_free;
        }
        switch (anchor) {
            case TOP_LEFT:
                return R.string.switcher_anchor_top_left;
            case TOP_RIGHT:
                return R.string.switcher_anchor_top_right;
            case BOTTOM_LEFT:
                return R.string.switcher_anchor_bottom_left;
            case BOTTOM_RIGHT:
                return R.string.switcher_anchor_bottom_right;
            case FREE:
            default:
                return R.string.switcher_anchor_free;
        }
    }

    private void scrollToAppsSectionIfRequested() {
        if (!pendingOpenAppsSection) {
            return;
        }
        ScrollView scroll = findViewById(R.id.main_scroll);
        View apps = findViewById(R.id.apps_management_container);
        scroll.post(() -> {
            if (!pendingOpenAppsSection || destroyed) {
                return;
            }
            pendingOpenAppsSection = false;
            Rect target = new Rect();
            apps.getDrawingRect(target);
            scroll.offsetDescendantRectToMyCoords(apps, target);
            scroll.smoothScrollTo(
                    0,
                    Math.max(0, target.top - dp(72))
            );
        });
    }

    private void refreshHubVersion() {
        TextView versionView = findViewById(R.id.text_hub_version);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionView.setText(getString(R.string.hub_version, info.versionName));
        } catch (PackageManager.NameNotFoundException ignored) {
            versionView.setText(R.string.hub_version_unknown);
        }
    }

    private void showDonationDialog() {
        View content = LayoutInflater.from(this).inflate(
                R.layout.dialog_donation,
                null,
                false
        );
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.donation_copy, null)
                .setNegativeButton(R.string.donation_close, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(
                            Context.CLIPBOARD_SERVICE
                    );
                    if (clipboard == null) {
                        return;
                    }
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.donation_card_label),
                            getString(R.string.donation_card_number_raw)
                    ));
                    Toast.makeText(
                            this,
                            R.string.donation_copied,
                            Toast.LENGTH_SHORT
                    ).show();
                })
        );
        dialog.show();
    }

    private void showAppCatalog() {
        if (activeCatalogDialog != null && activeCatalogDialog.isShowing()) {
            return;
        }
        requestInstalledAppsRefresh();
        ScrollView catalogScroll = new ScrollView(this);
        catalogScroll.setFillViewport(true);
        LinearLayout catalogList = new LinearLayout(this);
        catalogList.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(14);
        catalogList.setPadding(padding, padding, padding, padding / 2);
        catalogScroll.addView(
                catalogList,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.catalog_title)
                .setView(catalogScroll)
                .setNegativeButton(R.string.cancel, null)
                .create();

        LayoutInflater inflater = LayoutInflater.from(this);
        addCatalogMessage(catalogList, R.string.catalog_refreshing);

        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() == null) {
                return;
            }
            int targetWidth = Math.min(
                    getResources().getDisplayMetrics().widthPixels - dp(64),
                    dp(760)
            );
            dialog.getWindow().setLayout(
                    targetWidth,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
        });
        dialog.setOnDismissListener(ignored -> {
            if (activeCatalogDialog == dialog) {
                stopCatalogRefresh();
                activeCatalogDialog = null;
                activeCatalogList = null;
                activeCatalogInflater = null;
                activeCatalogFingerprint = null;
            }
        });
        dialog.show();
        activeCatalogDialog = dialog;
        activeCatalogList = catalogList;
        activeCatalogInflater = inflater;
        activeCatalogFingerprint = null;
        requestCatalogInventory(dialog, catalogList, inflater);
    }

    private void addCatalogSectionHeader(LinearLayout container, int textResId) {
        TextView header = new TextView(this);
        header.setText(textResId);
        header.setTextColor(getColor(R.color.brand));
        header.setTextSize(11);
        header.setAllCaps(true);
        header.setPadding(dp(4), dp(12), dp(4), dp(6));
        container.addView(header);
    }

    private void addCatalogMessage(LinearLayout container, int textResId) {
        TextView message = new TextView(this);
        message.setText(textResId);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setTextSize(13);
        message.setPadding(dp(4), dp(4), dp(4), dp(10));
        container.addView(message);
    }

    private void addInstalledCatalogRow(
            LinearLayout container,
            LayoutInflater inflater,
            AlertDialog dialog,
            InstalledApp app,
            boolean initiallyVisibleInTaskbar,
            TaskbarCategory initialCategory
    ) {
        View row = inflater.inflate(R.layout.catalog_app_row, container, false);
        ImageView icon = row.findViewById(R.id.catalog_item_icon);
        icon.setImageDrawable(app.icon == null
                ? getDrawable(R.drawable.ic_taskbar_app_generic)
                : app.icon);
        ((TextView) row.findViewById(R.id.catalog_item_name))
                .setText(app.displayName);
        row.setContentDescription(getString(
                R.string.catalog_open_app,
                app.displayName
        ));
        row.setOnClickListener(view -> launchInstalledApp(app));
        TextView status = row.findViewById(R.id.catalog_item_status);
        status.setTextColor(getColor(R.color.status_success));
        TextView category = row.findViewById(R.id.catalog_item_category);
        category.setVisibility(View.VISIBLE);
        bindTaskbarCategory(app, category, initialCategory);
        category.setOnClickListener(
                view -> showTaskbarCategoryDialog(app, category)
        );
        ImageButton taskbarVisibility =
                row.findViewById(R.id.catalog_item_taskbar_visibility);
        taskbarVisibility.setVisibility(View.VISIBLE);
        bindTaskbarVisibility(
                app,
                status,
                taskbarVisibility,
                initiallyVisibleInTaskbar
        );
        taskbarVisibility.setOnClickListener(view -> {
            boolean currentlyVisible =
                    SwitcherController.isPackageVisible(
                            MainActivity.this,
                            app.packageName
                    );
            SwitcherController.setPackageVisible(
                    MainActivity.this,
                    app.packageName,
                    !currentlyVisible
            );
            bindTaskbarVisibility(
                    app,
                    status,
                    taskbarVisibility,
                    !currentlyVisible
            );

            // Both the main list and the optional catalog dialog render from
            // the same snapshot. Invalidating their fingerprints keeps the
            // two views in sync without rescanning installed packages.
            mainAppsFingerprint = null;
            activeCatalogFingerprint = null;
            refreshStatuses();
            scheduleCatalogRefresh(0L);
        });
        Button action = row.findViewById(R.id.catalog_item_action);
        action.setText(R.string.catalog_uninstall);
        action.setOnClickListener(view -> {
            if (dialog != null) {
                dialog.dismiss();
            }
            requestUninstall(app.packageName, app.displayName);
        });
        container.addView(row);
    }

    private void bindTaskbarCategory(
            InstalledApp app,
            TextView categoryView,
            TaskbarCategory category
    ) {
        TaskbarCategory safeCategory = category == null
                ? TaskbarCategory.classify(app.packageName)
                : category;
        String label = getString(safeCategory.getLabelResId());
        categoryView.setText(label);
        categoryView.setContentDescription(getString(
                R.string.taskbar_category_change,
                app.displayName,
                label
        ));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(getColor(R.color.surface_variant));
        background.setStroke(
                dp(1),
                getColor(safeCategory.getColorResId())
        );
        background.setCornerRadius(dp(13));
        categoryView.setBackground(background);
    }

    private void showTaskbarCategoryDialog(
            InstalledApp app,
            TextView categoryView
    ) {
        TaskbarCategory[] categories = TaskbarCategory.values();
        String[] options = new String[categories.length + 1];
        options[0] = getString(R.string.taskbar_category_auto);
        for (int index = 0; index < categories.length; index++) {
            options[index + 1] =
                    getString(categories[index].getLabelResId());
        }

        Map<String, String> overrides =
                SwitcherController.readPackageCategoryOverrides(this);
        String overrideId = overrides.get(app.packageName);
        int checked = 0;
        if (overrideId != null) {
            TaskbarCategory override = TaskbarCategory.fromId(overrideId);
            if (override != null) {
                checked = override.ordinal() + 1;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(
                        R.string.taskbar_category_dialog_title,
                        app.displayName
                ))
                .setSingleChoiceItems(
                        options,
                        checked,
                        (dialog, which) -> {
                            TaskbarCategory selected = which == 0
                                    ? null
                                    : categories[which - 1];
                            SwitcherController.setPackageCategoryOverride(
                                    MainActivity.this,
                                    app.packageName,
                                    selected
                            );
                            bindTaskbarCategory(
                                    app,
                                    categoryView,
                                    selected == null
                                            ? TaskbarCategory.classify(
                                                    app.packageName
                                            )
                                            : selected
                            );
                            mainAppsFingerprint = null;
                            activeCatalogFingerprint = null;
                            refreshStatuses();
                            scheduleCatalogRefresh(0L);
                            Toast.makeText(
                                    MainActivity.this,
                                    R.string.taskbar_category_changed,
                                    Toast.LENGTH_SHORT
                            ).show();
                            dialog.dismiss();
                        }
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void bindTaskbarVisibility(
            InstalledApp app,
            TextView status,
            ImageButton taskbarVisibility,
            boolean visible
    ) {
        status.setText(getString(
                visible
                        ? R.string.catalog_installed_taskbar_visible
                        : R.string.catalog_installed_taskbar_hidden,
                safeVersionName(app.packageInfo)
        ));
        taskbarVisibility.setImageResource(
                visible
                        ? R.drawable.ic_taskbar_visibility_on
                        : R.drawable.ic_taskbar_visibility_off
        );
        taskbarVisibility.setContentDescription(getString(
                visible
                        ? R.string.catalog_taskbar_hide
                        : R.string.catalog_taskbar_show,
                app.displayName
        ));
    }

    private void requestCatalogInventory(
            AlertDialog dialog,
            LinearLayout catalogList,
            LayoutInflater inflater
    ) {
        if (!dialog.isShowing() || dialog != activeCatalogDialog) {
            return;
        }
        if (!installedAppsSnapshotReady) {
            requestInstalledAppsRefreshIfNeeded();
            scheduleCatalogRefresh(CATALOG_BUSY_RETRY_MS);
            return;
        }
        boolean scanStarted = inventoryScanner.scanAsync(result -> {
            if (!dialog.isShowing() || dialog != activeCatalogDialog) {
                return;
            }
            String fingerprint = catalogFingerprint(
                    result.getItems(),
                    installedAppsSnapshot,
                    knownInstalledCatalogPackagesSnapshot
            );
            if (!fingerprint.equals(activeCatalogFingerprint)) {
                renderCatalogSections(
                        catalogList,
                        inflater,
                        dialog,
                        result.getItems(),
                        installedAppsSnapshot,
                        knownInstalledCatalogPackagesSnapshot
                );
                activeCatalogFingerprint = fingerprint;
            }
            scheduleCatalogRefresh(CATALOG_REFRESH_INTERVAL_MS);
        });
        if (!scanStarted) {
            scheduleCatalogRefresh(CATALOG_BUSY_RETRY_MS);
        }
    }

    private void renderCatalogSections(
            LinearLayout container,
            LayoutInflater inflater,
            AlertDialog dialog,
            List<ApkInventoryScanner.Item> downloadedItems,
            List<InstalledApp> installedApps,
            Set<String> knownInstalledCatalogPackages
    ) {
        container.removeAllViews();

        addCatalogSectionHeader(container, R.string.catalog_installed_section);
        int installedCount = 0;
        Set<String> hiddenTaskbarPackages =
                SwitcherController.readHiddenPackages(this);
        Map<String, String> categoryOverrides =
                SwitcherController.readPackageCategoryOverrides(this);
        TaskbarSettings taskbarSettings =
                SwitcherController.readTaskbarSettings(this);
        List<InstalledApp> orderedInstalledApps =
                new ArrayList<>(installedApps);
        Collections.sort(
                orderedInstalledApps,
                (left, right) -> compareInstalledApps(
                        left,
                        right,
                        taskbarSettings.getSortMode(),
                        categoryOverrides
                )
        );
        TaskbarCategory renderedCategory = null;
        for (InstalledApp app : orderedInstalledApps) {
            TaskbarCategory appCategory = TaskbarCategory.resolve(
                    app.packageName,
                    categoryOverrides.get(app.packageName)
            );
            if (taskbarSettings.getSortMode()
                    == TaskbarSettings.SortMode.CATEGORIES
                    && appCategory != renderedCategory) {
                addTaskbarCategoryHeader(container, appCategory);
                renderedCategory = appCategory;
            }
            installedCount++;
            addInstalledCatalogRow(
                    container,
                    inflater,
                    dialog,
                    app,
                    !hiddenTaskbarPackages.contains(app.packageName),
                    appCategory
            );
        }
        if (installedCount == 0) {
            addCatalogMessage(container, R.string.catalog_installed_empty);
        }

        addCatalogSectionHeader(container, R.string.catalog_downloaded_section);
        if (downloadedItems.isEmpty()) {
            addCatalogMessage(container, R.string.catalog_downloaded_empty);
        } else {
            for (ApkInventoryScanner.Item item : downloadedItems) {
                addDownloadedCatalogRow(container, inflater, dialog, item);
            }
        }

        addCatalogSectionHeader(
                container,
                AppCatalog.isPublicEdition()
                        ? R.string.catalog_official_section
                        : R.string.catalog_server_section
        );
        View sourceWarning = inflater.inflate(
                R.layout.catalog_source_warning,
                container,
                false
        );
        if (AppCatalog.isPublicEdition()) {
            ((TextView) sourceWarning.findViewById(
                    R.id.catalog_source_warning
            )).setText(R.string.catalog_official_warning);
        }
        container.addView(sourceWarning);
        for (AppCatalog.Entry entry : AppCatalog.getEntries()) {
            if (!hasKnownInstalledPackage(
                    entry.getInstalledPackageNames(),
                    knownInstalledCatalogPackages
            )
                    && !hasDownloadedCatalogApk(entry, downloadedItems)) {
                addServerCatalogRow(container, inflater, dialog, entry);
            }
        }
        if (dialog != null && AppCatalog.allowsCustomDownloads()) {
            addCustomCatalogRow(container, inflater, dialog);
        }
    }

    private void addTaskbarCategoryHeader(
            LinearLayout container,
            TaskbarCategory category
    ) {
        TextView header = new TextView(this);
        header.setText(category.getLabelResId());
        header.setTextColor(getColor(category.getColorResId()));
        header.setTextSize(11);
        header.setAllCaps(true);
        header.setPadding(dp(4), dp(8), dp(4), dp(5));
        container.addView(header);
    }

    private static int compareInstalledApps(
            InstalledApp left,
            InstalledApp right,
            TaskbarSettings.SortMode sortMode,
            Map<String, String> categoryOverrides
    ) {
        if (sortMode == TaskbarSettings.SortMode.CATEGORIES) {
            TaskbarCategory leftCategory = TaskbarCategory.resolve(
                    left.packageName,
                    categoryOverrides.get(left.packageName)
            );
            TaskbarCategory rightCategory = TaskbarCategory.resolve(
                    right.packageName,
                    categoryOverrides.get(right.packageName)
            );
            int byCategory = Integer.compare(
                    leftCategory.ordinal(),
                    rightCategory.ordinal()
            );
            if (byCategory != 0) {
                return byCategory;
            }
        }
        int byLabel = left.displayName.compareToIgnoreCase(
                right.displayName
        );
        if (byLabel != 0) {
            return byLabel;
        }
        return left.packageName.compareToIgnoreCase(right.packageName);
    }

    private String catalogFingerprint(
            List<ApkInventoryScanner.Item> downloadedItems,
            List<InstalledApp> installedApps,
            Set<String> knownInstalledCatalogPackages
    ) {
        StringBuilder fingerprint = new StringBuilder(256);
        Set<String> hiddenTaskbarPackages =
                SwitcherController.readHiddenPackages(this);
        Map<String, String> categoryOverrides =
                SwitcherController.readPackageCategoryOverrides(this);
        TaskbarSettings taskbarSettings =
                SwitcherController.readTaskbarSettings(this);
        fingerprint.append("T:")
                .append(taskbarSettings.getRows()).append('x')
                .append(taskbarSettings.getColumns()).append(':')
                .append(taskbarSettings.getSortMode().getId()).append(':')
                .append(taskbarSettings.getAnchor().getId()).append(';');
        for (InstalledApp app : installedApps) {
            TaskbarCategory category = TaskbarCategory.resolve(
                    app.packageName,
                    categoryOverrides.get(app.packageName)
            );
            fingerprint.append("I:")
                    .append(app.packageName).append(':')
                    .append(packageVersionCode(app.packageInfo)).append(':')
                    .append(safeVersionName(app.packageInfo)).append(':')
                    .append(app.displayName).append(':')
                    .append(launcherIdentity(
                            app.componentName,
                            app.launchCategory
                    ))
                    .append(':')
                    .append(
                            !hiddenTaskbarPackages.contains(app.packageName)
                                    ? 'V'
                                    : 'H'
                    )
                    .append(':')
                    .append(category.getId())
                    .append(';');
        }
        for (ApkInventoryScanner.Item item : downloadedItems) {
            fingerprint.append("D:")
                    .append(item.getPackageName()).append(':')
                    .append(item.getVersionCode()).append(':')
                    .append(item.getVersionName()).append(':')
                    .append(item.getIdentity()).append(':')
                    .append(item.getFileSize()).append(':')
                    .append(item.getModifiedAt()).append(';');
        }
        List<String> knownPackages =
                new ArrayList<>(knownInstalledCatalogPackages);
        Collections.sort(knownPackages);
        for (String packageName : knownPackages) {
            fingerprint.append("K:").append(packageName).append(';');
        }
        return fingerprint.toString();
    }

    static String launcherIdentity(
            ComponentName componentName,
            String launchCategory
    ) {
        return (componentName == null
                ? ""
                : componentName.flattenToShortString())
                + ':'
                + (launchCategory == null ? "" : launchCategory);
    }

    private void addDownloadedCatalogRow(
            LinearLayout container,
            LayoutInflater inflater,
            AlertDialog dialog,
            ApkInventoryScanner.Item item
    ) {
        View row = inflater.inflate(R.layout.catalog_app_row, container, false);
        ImageView icon = row.findViewById(R.id.catalog_item_icon);
        icon.setImageDrawable(item.getIcon() == null
                ? getDrawable(R.drawable.ic_taskbar_app_generic)
                : item.getIcon());
        ((TextView) row.findViewById(R.id.catalog_item_name))
                .setText(item.getDisplayName());
        TextView status = row.findViewById(R.id.catalog_item_status);
        if (item.isUpdate()) {
            status.setText(getString(
                    R.string.catalog_downloaded_update,
                    item.getInstalledVersionName(),
                    item.getVersionName(),
                    item.getFileName()
            ));
        } else {
            status.setText(getString(
                    R.string.catalog_downloaded_details,
                    item.getVersionName(),
                    item.getFileName()
            ));
        }
        Button action = row.findViewById(R.id.catalog_item_action);
        action.setText(R.string.install_action);
        action.setOnClickListener(view -> {
            if (dialog != null) {
                dialog.dismiss();
            }
            if (MessagingAppWarnings.requiresWarning(item.getPackageName())) {
                showMessagingInstallWarning(
                        item.getDisplayName(),
                        () -> prepareInventoryInstall(item, action)
                );
                return;
            }
            prepareInventoryInstall(item, action);
        });
        container.addView(row);
    }

    private void addServerCatalogRow(
            LinearLayout container,
            LayoutInflater inflater,
            AlertDialog dialog,
            AppCatalog.Entry entry
    ) {
        View row = inflater.inflate(R.layout.catalog_app_row, container, false);
        ((ImageView) row.findViewById(R.id.catalog_item_icon))
                .setImageResource(catalogIcon(entry.getId()));
        ((TextView) row.findViewById(R.id.catalog_item_name))
                .setText(entry.getDisplayName());
        TextView status = row.findViewById(R.id.catalog_item_status);
        Button action = row.findViewById(R.id.catalog_item_action);
        if (entry.isDownloadable()) {
            status.setText(getString(
                    R.string.catalog_available,
                    entry.getSourceUri().getHost()
            ));
            action.setText(R.string.catalog_download);
            action.setOnClickListener(view -> {
                if (dialog != null) {
                    dialog.dismiss();
                }
                confirmDownload(entry);
            });
        } else {
            status.setText(getString(
                    R.string.catalog_official_page,
                    entry.getSourceUri().getHost()
            ));
            action.setText(R.string.catalog_open_source);
            action.setOnClickListener(view -> {
                if (dialog != null) {
                    dialog.dismiss();
                }
                openOfficialSource(entry);
            });
        }
        container.addView(row);
    }

    private void openOfficialSource(AppCatalog.Entry entry) {
        Intent intent = new Intent(Intent.ACTION_VIEW, entry.getSourceUri())
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.catalog_open_source_failed,
                            entry.getDisplayName()
                    ),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void addCustomCatalogRow(
            LinearLayout container,
            LayoutInflater inflater,
            AlertDialog dialog
    ) {
        View row = inflater.inflate(R.layout.catalog_app_row, container, false);
        ((ImageView) row.findViewById(R.id.catalog_item_icon))
                .setImageResource(R.drawable.ic_taskbar_app_generic);
        ((TextView) row.findViewById(R.id.catalog_item_name))
                .setText(R.string.catalog_custom_url);
        ((TextView) row.findViewById(R.id.catalog_item_status))
                .setText(R.string.catalog_custom_url_hint);
        Button action = row.findViewById(R.id.catalog_item_action);
        action.setText(R.string.catalog_choose);
        action.setOnClickListener(view -> {
            dialog.dismiss();
            showCustomUrlDialog();
        });
        container.addView(row);
    }

    private boolean hasDownloadedCatalogApk(
            AppCatalog.Entry entry,
            List<ApkInventoryScanner.Item> downloadedItems
    ) {
        for (ApkInventoryScanner.Item item : downloadedItems) {
            if (entry.getInstalledPackageNames().contains(item.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private InstalledAppsSnapshot queryManagedInstalledApps() {
        List<InstalledApp> result = new ArrayList<>();
        Map<String, PackageInfo> packageInfoByName = new HashMap<>();
        for (LaunchableAppDiscovery.Entry entry
                : LaunchableAppDiscovery.query(this)) {
            ApplicationInfo applicationInfo = entry.getApplicationInfo();
            String packageName = entry.getPackageName();
            if (isProtectedPackage(packageName)
                    || LaunchableAppDiscovery.isUnremovableSystemApp(
                    applicationInfo
            )) {
                continue;
            }
            PackageInfo packageInfo = readPackageInfoCached(
                    packageName,
                    packageInfoByName
            );
            if (packageInfo == null) {
                continue;
            }
            result.add(
                    new InstalledApp(
                            entry.getDisplayName(),
                            packageName,
                            packageInfo,
                            entry.getIcon(),
                            entry.getComponentName(),
                            entry.getLaunchCategory()
                    )
            );
        }

        Set<String> knownCatalogPackages = new HashSet<>();
        for (AppCatalog.Entry entry : AppCatalog.getEntries()) {
            for (String packageName : entry.getInstalledPackageNames()) {
                if (readPackageInfoCached(
                        packageName,
                        packageInfoByName
                ) != null) {
                    knownCatalogPackages.add(packageName);
                }
            }
        }
        return new InstalledAppsSnapshot(result, knownCatalogPackages);
    }

    private PackageInfo readPackageInfoCached(
            String packageName,
            Map<String, PackageInfo> cache
    ) {
        if (cache.containsKey(packageName)) {
            return cache.get(packageName);
        }
        PackageInfo packageInfo = readPackageInfo(packageName);
        cache.put(packageName, packageInfo);
        return packageInfo;
    }

    private void requestInstalledAppsRefresh() {
        long generation;
        synchronized (installedAppsRefreshLock) {
            if (destroyed) {
                return;
            }
            generation = ++installedAppsRefreshGeneration;
            if (installedAppsRefreshRunning) {
                installedAppsRefreshPending = true;
                return;
            }
            installedAppsRefreshRunning = true;
            installedAppsRefreshPending = false;
        }
        submitInstalledAppsRefresh(generation);
    }

    private void requestInstalledAppsRefreshIfNeeded() {
        synchronized (installedAppsRefreshLock) {
            if (destroyed || !shouldStartInstalledAppsRefresh(
                    installedAppsSnapshotReady,
                    installedAppsRefreshRunning
            )) {
                return;
            }
        }
        requestInstalledAppsRefresh();
    }

    static boolean shouldStartInstalledAppsRefresh(
            boolean snapshotReady,
            boolean refreshRunning
    ) {
        return !snapshotReady && !refreshRunning;
    }

    private void submitInstalledAppsRefresh(long generation) {
        try {
            installedAppsExecutor.execute(() -> {
                InstalledAppsSnapshot snapshot = null;
                try {
                    if (!Thread.currentThread().isInterrupted()) {
                        snapshot = installedAppsQuery.query();
                    }
                } catch (RuntimeException ignored) {
                    // Keep the last known-good snapshot and retry on the next
                    // lifecycle/package/catalog refresh signal.
                }
                InstalledAppsSnapshot completedSnapshot = snapshot;
                installedAppsCallbackHandler.post(
                        () -> completeInstalledAppsRefresh(
                                generation,
                                completedSnapshot
                        )
                );
            });
        } catch (RejectedExecutionException ignored) {
            synchronized (installedAppsRefreshLock) {
                installedAppsRefreshRunning = false;
                installedAppsRefreshPending = false;
            }
        }
    }

    private void completeInstalledAppsRefresh(
            long generation,
            InstalledAppsSnapshot snapshot
    ) {
        long nextGeneration = -1L;
        boolean applySnapshot;
        synchronized (installedAppsRefreshLock) {
            if (destroyed) {
                installedAppsRefreshRunning = false;
                installedAppsRefreshPending = false;
                return;
            }
            applySnapshot = generation == installedAppsRefreshGeneration
                    && snapshot != null;
            if (installedAppsRefreshPending) {
                installedAppsRefreshPending = false;
                nextGeneration = installedAppsRefreshGeneration;
            } else {
                installedAppsRefreshRunning = false;
            }
        }

        if (applySnapshot) {
            installedAppsSnapshot = snapshot.apps;
            knownInstalledCatalogPackagesSnapshot =
                    snapshot.knownCatalogPackages;
            installedAppsSnapshotReady = true;
            if (inventorySnapshotReady) {
                renderMainAppSections(lastInventoryItems);
            }
            scheduleCatalogRefresh(0L);
        }

        if (nextGeneration >= 0L) {
            submitInstalledAppsRefresh(nextGeneration);
        }
    }

    private boolean isProtectedPackage(String packageName) {
        return getPackageName().equals(packageName)
                || "dji.go.v5".equals(packageName)
                || "dji.go.v4".equals(packageName)
                || "dji.pilot".equals(packageName);
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

            registerReceiver(
                    packageChangeReceiver,
                    buildPackageAvailabilityFilter()
            );
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

    static IntentFilter buildPackageAvailabilityFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            filter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
            filter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        }
        return filter;
    }

    private void requestUninstall(String packageName, String displayName) {
        Intent uninstallIntent = new Intent(
                Intent.ACTION_DELETE,
                Uri.fromParts("package", packageName, null)
        );
        try {
            uninstallIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(uninstallIntent, REQUEST_UNINSTALL_APP);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    getString(R.string.catalog_uninstall_failed, displayName),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void launchInstalledApp(InstalledApp app) {
        if (app == null || app.componentName == null) {
            return;
        }
        Intent launchIntent = new Intent(Intent.ACTION_MAIN)
                .setComponent(app.componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (app.launchCategory != null
                && !app.launchCategory.trim().isEmpty()) {
            launchIntent.addCategory(app.launchCategory);
        }
        try {
            startActivity(launchIntent);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.catalog_open_failed,
                            app.displayName
                    ),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private int catalogIcon(String id) {
        if ("telegram".equals(id)) {
            return R.drawable.ic_catalog_telegram;
        }
        if ("discord".equals(id)) {
            return R.drawable.ic_catalog_discord;
        }
        if ("happ".equals(id)) {
            return R.drawable.ic_catalog_happ;
        }
        if ("shareit".equals(id)) {
            return R.drawable.ic_catalog_shareit;
        }
        if ("chrome".equals(id)) {
            return R.drawable.ic_catalog_browser;
        }
        if ("youtube".equals(id)) {
            return R.drawable.ic_catalog_youtube;
        }
        if ("rutube".equals(id)) {
            return R.drawable.ic_catalog_rutube;
        }
        if ("astras".equals(id)) {
            return R.drawable.ic_catalog_astras;
        }
        if ("windy".equals(id)) {
            return R.drawable.ic_catalog_windy;
        }
        if ("screenstream".equals(id)) {
            return R.drawable.ic_catalog_screen;
        }
        if ("quicktiles".equals(id)) {
            return R.drawable.ic_catalog_quicktiles;
        }
        return R.drawable.ic_taskbar_app_generic;
    }

    private void showCustomUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        input.setHint(R.string.custom_url_hint);
        input.setText(R.string.https_prefix);
        input.setSelection(input.length());

        int padding = Math.round(
                22 * getResources().getDisplayMetrics().density
        );
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setPadding(padding, 0, padding, 0);
        inputContainer.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.custom_url_title)
                .setMessage(R.string.custom_url_note)
                .setView(inputContainer)
                .setPositiveButton(R.string.download_action, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    try {
                        AppCatalog.Entry entry = AppCatalog.createCustom(
                                "Свой APK",
                                input.getText().toString().trim()
                        );
                        dialog.dismiss();
                        confirmDownload(entry);
                    } catch (IllegalArgumentException error) {
                        input.setError(getString(R.string.invalid_https_url));
                    }
                })
        );
        dialog.show();
    }

    private void confirmDownload(AppCatalog.Entry entry) {
        if (entry == null) {
            return;
        }
        if (!entry.isDownloadable()) {
            openOfficialSource(entry);
            return;
        }
        Uri uri = entry.getDownloadUri();
        if (MessagingAppWarnings.requiresWarning(entry)) {
            showWarningDialog(
                    getString(
                            R.string.messaging_download_warning_message,
                            entry.getDisplayName(),
                            uri.getHost()
                    ),
                    R.string.download_action,
                    () -> startDownload(entry)
            );
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_confirm_title)
                .setMessage(getString(
                        R.string.download_confirm_message,
                        entry.getDisplayName(),
                        uri.getHost()
                ))
                .setPositiveButton(
                        R.string.download_action,
                        (dialog, which) -> startDownload(entry)
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showMessagingInstallWarning(
            String displayName,
            Runnable continueInstall
    ) {
        showWarningDialog(
                getString(
                        R.string.messaging_install_warning_message,
                        displayName
                ),
                R.string.install_action,
                continueInstall
        );
    }

    private void showWarningDialog(
            String message,
            int positiveButtonText,
            Runnable continueAction
    ) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.messaging_warning_title)
                .setMessage(message)
                .setPositiveButton(
                        positiveButtonText,
                        (ignored, which) -> continueAction.run()
                )
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
    }

    private void startDownload(AppCatalog.Entry entry) {
        if (downloadController.hasPendingDownload()) {
            enqueueDownload(entry);
            return;
        }
        startDownloadNow(entry);
    }

    private void enqueueDownload(AppCatalog.Entry entry) {
        AppCatalog.Entry pendingEntry = downloadController.getPendingEntry();
        if ((pendingEntry != null
                && downloadQueueKey(entry).equals(downloadQueueKey(pendingEntry)))
                || isQueued(entry)) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.download_already_queued,
                            entry.getDisplayName()
                    ),
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        downloadQueue.addLast(entry);
        persistDownloadQueue();
        Toast.makeText(
                this,
                getString(
                        R.string.download_queued,
                        entry.getDisplayName(),
                        downloadQueue.size()
                ),
                Toast.LENGTH_LONG
        ).show();
    }

    private boolean isQueued(AppCatalog.Entry entry) {
        String entryKey = downloadQueueKey(entry);
        for (AppCatalog.Entry queued : downloadQueue) {
            if (downloadQueueKey(queued).equals(entryKey)) {
                return true;
            }
        }
        return false;
    }

    private void startNextQueuedDownload() {
        if (destroyed
                || downloadController.hasPendingDownload()
                || downloadQueue.isEmpty()) {
            return;
        }
        AppCatalog.Entry next = downloadQueue.removeFirst();
        persistDownloadQueue();
        startDownloadNow(next);
    }

    private static String downloadQueueKey(AppCatalog.Entry entry) {
        return "custom".equals(entry.getId())
                ? entry.getId() + ":" + entry.getDownloadUri()
                : entry.getId();
    }

    private void persistDownloadQueue() {
        JSONArray array = new JSONArray();
        for (AppCatalog.Entry entry : downloadQueue) {
            JSONObject value = new JSONObject();
            try {
                value.put("id", entry.getId());
                value.put("name", entry.getDisplayName());
                value.put("url", entry.getDownloadUri().toString());
                array.put(value);
            } catch (JSONException ignored) {
                // These values are plain strings and cannot fail in practice.
            }
        }
        getSharedPreferences(QUEUE_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(KEY_DOWNLOAD_QUEUE, array.toString())
                .apply();
    }

    private void restoreDownloadQueue() {
        SharedPreferences preferences = getSharedPreferences(
                QUEUE_PREFERENCES,
                MODE_PRIVATE
        );
        String serialized = preferences.getString(KEY_DOWNLOAD_QUEUE, "[]");
        try {
            JSONArray array = new JSONArray(serialized);
            for (int index = 0; index < array.length(); index++) {
                JSONObject value = array.getJSONObject(index);
                String id = value.optString("id", "");
                AppCatalog.Entry entry;
                if ("custom".equals(id)) {
                    entry = AppCatalog.createCustom(
                            value.optString("name", "Свой APK"),
                            value.getString("url")
                    );
                } else {
                    entry = AppCatalog.findById(id);
                    String storedUrl = value.optString("url", "");
                    if (entry != null
                            && !entry.getSourceUri().toString().equals(storedUrl)) {
                        // A queued item from the private catalog must not be
                        // resumed after installing the GitHub edition.
                        entry = null;
                    }
                }
                if (entry != null
                        && entry.isDownloadable()
                        && !isQueued(entry)) {
                    downloadQueue.addLast(entry);
                }
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            downloadQueue.clear();
        }
        persistDownloadQueue();
    }

    private void startDownloadNow(AppCatalog.Entry entry) {
        try {
            downloadController.download(entry);
            showDownloadPanel();
            TextView statusView = findViewById(R.id.text_download_status);
            statusView.setText(
                    getString(R.string.download_started, entry.getDisplayName())
            );
        } catch (IllegalArgumentException | IllegalStateException error) {
            showDownloadFailure(error.getMessage());
            startNextQueuedDownload();
        }
    }

    private void renderDownloadStatus(ApkDownloadController.Status status) {
        View panel = findViewById(R.id.download_panel);
        TextView statusView = findViewById(R.id.text_download_status);
        ProgressBar bar = findViewById(R.id.progress_download);
        AppCatalog.Entry entry = status.getEntry();
        String appName = entry == null ? "APK" : entry.getDisplayName();
        String message = status.getMessage();

        if ((status.getState() == ApkDownloadController.State.RUNNING
                || status.getState() == ApkDownloadController.State.PAUSED)
                && status.getTotalBytes() > 0) {
            long percent = Math.min(
                    100,
                    status.getDownloadedBytes() * 100 / status.getTotalBytes()
            );
            message = String.format(
                    Locale.US,
                    getString(R.string.download_progress_status),
                    appName,
                    message,
                    percent
            );
        } else {
            message = getString(R.string.download_app_status, appName, message);
        }
        boolean shouldShowPanel =
                status.getState() != ApkDownloadController.State.IDLE
                        && status.getState() != ApkDownloadController.State.CANCELLED;
        if (shouldShowPanel) {
            showDownloadPanel();
        } else {
            panel.setVisibility(View.GONE);
        }
        statusView.setText(message);

        switch (status.getState()) {
            case ENQUEUED:
            case PENDING:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                startDownloadPolling();
                break;
            case RUNNING:
            case PAUSED:
                bar.setVisibility(View.VISIBLE);
                if (status.getTotalBytes() > 0) {
                    bar.setIndeterminate(false);
                    animateProgressTo(bar, (int) Math.min(
                            100,
                            status.getDownloadedBytes() * 100 / status.getTotalBytes()
                    ));
                } else {
                    bar.setIndeterminate(true);
                }
                startDownloadPolling();
                break;
            case VALIDATING:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                startDownloadPolling();
                break;
            case READY:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                animateProgressTo(bar, 100);
                stopDownloadPolling();
                break;
            case FAILED:
            case CANCELLED:
            case IDLE:
            default:
                bar.setVisibility(View.GONE);
                stopDownloadPolling();
                break;
        }

        findViewById(R.id.button_cancel_download).setVisibility(
                status.getState() == ApkDownloadController.State.ENQUEUED
                        || status.getState() == ApkDownloadController.State.PENDING
                        || status.getState() == ApkDownloadController.State.RUNNING
                        || status.getState() == ApkDownloadController.State.PAUSED
                        || status.getState() == ApkDownloadController.State.VALIDATING
                        ? View.VISIBLE
                        : View.GONE
        );

        statusView.setTextColor(
                status.getState() == ApkDownloadController.State.FAILED
                        ? getColor(R.color.status_missing)
                        : getColor(R.color.text_secondary)
        );

        if (status.getState() == ApkDownloadController.State.FAILED) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        if (status.getState() == ApkDownloadController.State.FAILED
                || status.getState() == ApkDownloadController.State.CANCELLED) {
            downloadPollHandler.post(this::startNextQueuedDownload);
        }
    }

    private void startDownloadPolling() {
        downloadPollHandler.removeCallbacks(downloadPollRunnable);
        downloadPollHandler.postDelayed(downloadPollRunnable, 700);
    }

    private void stopDownloadPolling() {
        downloadPollHandler.removeCallbacks(downloadPollRunnable);
    }

    private void startInventoryRefresh() {
        inventoryRefreshHandler.removeCallbacks(inventoryRefreshRunnable);
        inventoryRefreshHandler.post(inventoryRefreshRunnable);
    }

    private void stopInventoryRefresh() {
        inventoryRefreshHandler.removeCallbacks(inventoryRefreshRunnable);
        inventoryRefreshHandler.removeCallbacks(inventoryRetryRunnable);
    }

    private void scheduleCatalogRefresh(long delayMillis) {
        catalogRefreshHandler.removeCallbacks(catalogRefreshRunnable);
        AlertDialog dialog = activeCatalogDialog;
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        catalogRefreshHandler.postDelayed(
                catalogRefreshRunnable,
                Math.max(0L, delayMillis)
        );
    }

    private void stopCatalogRefresh() {
        catalogRefreshHandler.removeCallbacks(catalogRefreshRunnable);
    }

    private void showDownloadFailure(String reason) {
        String safeReason = reason == null || reason.trim().isEmpty()
                ? "неизвестная ошибка"
                : reason;
        String message = getString(R.string.download_failed, safeReason);
        showDownloadPanel();
        TextView statusView = findViewById(R.id.text_download_status);
        statusView.setText(message);
        statusView.setTextColor(getColor(R.color.status_missing));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void animateProgressTo(ProgressBar bar, int target) {
        if (bar.getProgress() == target) {
            return;
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), target);
        animator.setDuration(260);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.start();
    }

    private void showDownloadPanel() {
        downloadPollHandler.removeCallbacks(hideCompletedDownloadPanel);
        View panel = findViewById(R.id.download_panel);
        if (panel.getVisibility() == View.VISIBLE) {
            return;
        }
        panel.setAlpha(0f);
        panel.setTranslationY(dp(8));
        panel.setVisibility(View.VISIBLE);
        panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateScreenIn(View view) {
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void refreshStatuses() {
        LinearLayout container = findViewById(R.id.apps_management_container);
        if (!inventorySnapshotReady || !installedAppsSnapshotReady) {
            if (container.getChildCount() == 0) {
                addCatalogMessage(container, R.string.catalog_refreshing);
            }
            return;
        }
        renderMainAppSections(lastInventoryItems);
    }

    private void refreshApkInventory() {
        if (inventoryScanner == null) {
            return;
        }
        boolean started = inventoryScanner.scanAsync(this::renderApkInventory);
        if (!started && !destroyed) {
            inventoryRefreshHandler.removeCallbacks(inventoryRetryRunnable);
            inventoryRefreshHandler.postDelayed(
                    inventoryRetryRunnable,
                    CATALOG_BUSY_RETRY_MS
            );
        }
    }

    private void renderApkInventory(ApkInventoryScanner.ScanResult result) {
        inventoryRefreshHandler.removeCallbacks(inventoryRetryRunnable);
        lastInventoryResult = result;
        lastInventoryItems = result.getItems();
        inventorySnapshotReady = true;
        renderMainAppSections(lastInventoryItems);
    }

    private void renderMainAppSections(
            List<ApkInventoryScanner.Item> downloadedItems
    ) {
        if (!installedAppsSnapshotReady) {
            requestInstalledAppsRefreshIfNeeded();
            return;
        }
        String fingerprint = catalogFingerprint(
                downloadedItems,
                installedAppsSnapshot,
                knownInstalledCatalogPackagesSnapshot
        );
        if (fingerprint.equals(mainAppsFingerprint)) {
            return;
        }
        LinearLayout container = findViewById(R.id.apps_management_container);
        renderCatalogSections(
                container,
                LayoutInflater.from(this),
                null,
                downloadedItems,
                installedAppsSnapshot,
                knownInstalledCatalogPackagesSnapshot
        );
        mainAppsFingerprint = fingerprint;
    }

    private void prepareInventoryInstall(
            ApkInventoryScanner.Item item,
            Button button
    ) {
        button.setEnabled(false);
        Toast.makeText(
                this,
                R.string.inventory_preparing,
                Toast.LENGTH_SHORT
        ).show();
        inventoryScanner.prepareInstallAsync(
                item,
                new ApkInventoryScanner.PrepareListener() {
                    @Override
                    public void onPrepared(
                            ApkInventoryScanner.Item preparedItem,
                            Uri contentUri
                    ) {
                        button.setEnabled(true);
                        if (SplitPackageInstaller.isSplitArchiveName(
                                preparedItem.getFileName()
                        )) {
                            continuePreparedInstall(
                                    contentUri,
                                    preparedItem.getPackageName()
                            );
                        } else {
                            continueApkInstall(contentUri);
                        }
                    }

                    @Override
                    public void onFailure(
                            ApkInventoryScanner.Item failedItem,
                            String reason
                    ) {
                        button.setEnabled(true);
                        refreshApkInventory();
                        String message = getString(
                                R.string.inventory_prepare_failed,
                                reason
                        );
                        Toast.makeText(
                                MainActivity.this,
                                message,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private static boolean hasKnownInstalledPackage(
            List<String> packageNames,
            Set<String> knownInstalledPackages
    ) {
        for (String packageName : packageNames) {
            if (knownInstalledPackages.contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    private PackageInfo readPackageInfo(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        try {
            return getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static long packageVersionCode(PackageInfo info) {
        if (info == null) {
            return -1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private String safeVersionName(PackageInfo info) {
        if (info == null || info.versionName == null || info.versionName.trim().isEmpty()) {
            return getString(R.string.version_unknown);
        }
        return info.versionName;
    }

    private void refreshInstallBootstrap() {
        refreshInstallBootstrap(InstallEnvironment.inspect(this));
    }

    private void refreshInstallBootstrap(
            InstallEnvironment.Snapshot snapshot
    ) {
        TextView status = findViewById(R.id.text_install_environment_status);
        Button settingsButton = findViewById(R.id.button_install_settings);
        boolean installerReady =
                snapshot.installer.isKnownInstallerHelper();
        boolean filesReady = snapshot.documentsUi.isKnownDocumentsHelper();
        findViewById(R.id.button_open_dji_files).setVisibility(
                filesReady ? View.VISIBLE : View.GONE
        );
        if (snapshot.hasPolicyWarning()) {
            status.setText(R.string.install_environment_policy_warning);
            status.setTextColor(getColor(R.color.status_warning));
            settingsButton.setVisibility(View.GONE);
        } else if (installerReady) {
            status.setText(R.string.install_environment_delegated);
            status.setTextColor(getColor(R.color.status_success));
            settingsButton.setVisibility(View.GONE);
        } else {
            switch (snapshot.state) {
                case READY:
                    status.setText(R.string.install_environment_ready);
                    status.setTextColor(getColor(R.color.status_success));
                    settingsButton.setVisibility(View.GONE);
                    break;
                case NEEDS_SOURCE_PERMISSION:
                    status.setText(R.string.install_environment_needs_permission);
                    status.setTextColor(getColor(R.color.status_warning));
                    settingsButton.setVisibility(View.VISIBLE);
                    break;
                case POLICY_WARNING:
                    status.setText(R.string.install_environment_policy_warning);
                    status.setTextColor(getColor(R.color.status_warning));
                    settingsButton.setVisibility(View.GONE);
                    break;
                case LEGACY_SYSTEM_CONFIRMATION:
                    status.setText(R.string.install_environment_legacy);
                    status.setTextColor(getColor(R.color.status_success));
                    settingsButton.setVisibility(View.GONE);
                    break;
                case UNKNOWN:
                default:
                    status.setText(R.string.install_environment_unknown);
                    status.setTextColor(getColor(R.color.status_warning));
                    settingsButton.setVisibility(View.GONE);
                    break;
            }
        }

        TextView helperStatus = findViewById(R.id.text_install_helpers_status);
        if (installerReady && filesReady) {
            helperStatus.setText(getString(
                    R.string.install_helpers_ready,
                    snapshot.installer.versionName,
                    snapshot.documentsUi.versionName
            ));
        } else {
            StringBuilder missing = new StringBuilder();
            if (!installerReady) {
                missing.append(getString(R.string.install_helper_installer));
            }
            if (!filesReady) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(getString(R.string.install_helper_files));
            }
            helperStatus.setText(getString(
                    R.string.install_helpers_partial,
                    missing.toString()
            ));
        }

        boolean folderConfigured = lastInventoryResult != null
                && lastInventoryResult.isSafFolderConfigured();
        boolean folderConnected = lastInventoryResult != null
                && lastInventoryResult.isSafFolderAccessible();
        TextView folderStatus = findViewById(R.id.text_apk_folder_status);
        Button chooseFolder = findViewById(R.id.button_choose_apk_folder);
        Button forgetFolder = findViewById(R.id.button_forget_apk_folder);
        if (folderConnected) {
            int scanned = lastInventoryResult == null
                    ? 0
                    : lastInventoryResult.getScannedFiles();
            int ready = lastInventoryResult == null
                    ? 0
                    : lastInventoryResult.getItems().size();
            folderStatus.setText(getString(
                    R.string.install_folder_selected,
                    scanned,
                    ready
            ));
            chooseFolder.setText(R.string.install_change_folder);
            forgetFolder.setVisibility(View.VISIBLE);
        } else if (folderConfigured) {
            folderStatus.setText(R.string.install_folder_temporarily_unavailable);
            chooseFolder.setText(R.string.install_change_folder);
            forgetFolder.setVisibility(View.VISIBLE);
        } else {
            folderStatus.setText(R.string.install_folder_not_selected);
            chooseFolder.setText(R.string.install_choose_folder);
            forgetFolder.setVisibility(View.GONE);
        }
    }

    private void openUnknownSourcesSettings() {
        InstallEnvironment.Snapshot snapshot =
                InstallEnvironment.inspect(this);
        if (snapshot.hasPolicyWarning()) {
            showPolicyWarning(snapshot, this::launchUnknownSourcesSettings);
            return;
        }
        launchUnknownSourcesSettings();
    }

    private void launchUnknownSourcesSettings() {
        try {
            startActivityForResult(
                    InstallEnvironment.unknownSourcesSettingsIntent(this),
                    REQUEST_UNKNOWN_SOURCES
            );
            Toast.makeText(
                    this,
                    R.string.unknown_sources_required,
                    Toast.LENGTH_LONG
            ).show();
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(
                    this,
                    R.string.unknown_sources_denied,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void chooseApkFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        InstallEnvironment.Snapshot snapshot =
                InstallEnvironment.inspect(this);
        if (snapshot.documentsUi.isKnownDocumentsHelper()) {
            intent.setPackage(InstallEnvironment.DOCUMENTS_UI);
            if (startPicker(intent, REQUEST_PICK_APK_TREE)) {
                return;
            }
            intent.setPackage(null);
        }
        if (!startPicker(intent, REQUEST_PICK_APK_TREE)) {
            showPickerFailure(R.string.install_folder_picker_failed);
        }
    }

    private void connectApkFolder(Intent data) {
        Uri uri = data.getData();
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            Toast.makeText(
                    this,
                    R.string.install_folder_failed,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        try {
            safAccessExecutor.execute(() -> {
                try {
                    inventoryScanner.replaceSafTreeUri(uri);
                    runOnUiThread(() -> {
                        if (destroyed) {
                            return;
                        }
                        lastInventoryResult = null;
                        refreshInstallBootstrap();
                        refreshApkInventory();
                    });
                } catch (SecurityException
                         | IllegalArgumentException
                         | IllegalStateException error) {
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            Toast.makeText(
                                    this,
                                    R.string.install_folder_failed,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            Toast.makeText(
                    this,
                    R.string.install_folder_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void forgetApkFolder() {
        try {
            safAccessExecutor.execute(() -> {
                try {
                    inventoryScanner.releaseSafTreeUri();
                    runOnUiThread(() -> {
                        if (destroyed) {
                            return;
                        }
                        lastInventoryResult = null;
                        refreshInstallBootstrap();
                        refreshApkInventory();
                    });
                } catch (IllegalStateException error) {
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            Toast.makeText(
                                    this,
                                    R.string.install_folder_failed,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            Toast.makeText(
                    this,
                    R.string.install_folder_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void showPolicyWarning(
            InstallEnvironment.Snapshot snapshot,
            Runnable continueWithAndroid
    ) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.install_policy_title)
                .setMessage(getString(
                        R.string.install_policy_message,
                        snapshot.restrictionSummary()
                ))
                .setNegativeButton(R.string.install_policy_cancel, null);
        if (continueWithAndroid != null) {
            builder.setPositiveButton(
                    R.string.install_policy_continue,
                    (dialog, which) -> continueWithAndroid.run()
            );
        }
        builder.show();
    }

    private void chooseApkFromStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(APK_MIME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        InstallEnvironment.Snapshot snapshot =
                InstallEnvironment.inspect(this);
        if (snapshot.documentsUi.isKnownDocumentsHelper()) {
            intent.setPackage(InstallEnvironment.DOCUMENTS_UI);
            if (startPicker(intent, REQUEST_PICK_APK)) {
                return;
            }
            intent.setPackage(null);
        }
        if (startPicker(intent, REQUEST_PICK_APK)) {
            return;
        }

        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        fallback.setType(APK_MIME);
        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!startPicker(fallback, REQUEST_PICK_APK)) {
            showPickerFailure(R.string.choose_apk_failed);
        }
    }

    private void openDjiFiles() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(CATEGORY_APP_FILES);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        intent.setPackage(InstallEnvironment.DOCUMENTS_UI);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            showPickerFailure(R.string.install_open_dji_files_failed);
        }
    }

    private boolean startPicker(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }

    private void showPickerFailure(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void continueApkInstall(Uri uri) {
        continuePreparedInstall(uri, null);
    }

    private void continuePreparedInstall(Uri uri, String splitPackage) {
        InstallEnvironment.Snapshot snapshot =
                InstallEnvironment.inspect(this);
        refreshInstallBootstrap(snapshot);
        if (snapshot.hasPolicyWarning()) {
            showPolicyWarning(
                    snapshot,
                    () -> continueAfterPolicyWarning(
                            snapshot,
                            uri,
                            splitPackage
                    )
            );
            return;
        }
        if (snapshot.hasVerifiedInstallerHelper()) {
            launchPreparedInstaller(uri, splitPackage);
            return;
        }
        continueAfterPolicyWarning(snapshot, uri, splitPackage);
    }

    private void continueAfterPolicyWarning(
            InstallEnvironment.Snapshot snapshot,
            Uri uri,
            String splitPackage
    ) {
        if (snapshot.hasVerifiedInstallerHelper()) {
            launchPreparedInstaller(uri, splitPackage);
            return;
        }
        if (snapshot.needsSourcePermission()) {
            pendingApkUri = uri;
            pendingSplitPackage = splitPackage;
            launchUnknownSourcesSettings();
            return;
        }
        launchPreparedInstaller(uri, splitPackage);
    }

    private void launchPreparedInstaller(Uri uri, String splitPackage) {
        if (splitPackage == null || splitPackage.trim().isEmpty()) {
            launchPackageInstaller(uri);
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(
                    this,
                    R.string.discord_android_version_required,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        AppCatalog.Entry catalogEntry =
                findPrimaryCatalogEntry(splitPackage);
        long expectedVersion = catalogEntry == null
                ? -1L
                : catalogEntry.getExpectedVersionCode();
        String expectedSigner = catalogEntry == null
                ? null
                : catalogEntry.getExpectedSignerSha256();
        String expectedArchive = catalogEntry == null
                ? null
                : catalogEntry.getExpectedFileSha256();
        Toast.makeText(
                this,
                "Подготавливаю Discord к установке…",
                Toast.LENGTH_LONG
        ).show();
        SplitPackageInstaller.installAsync(
                this,
                uri,
                splitPackage,
                expectedVersion,
                expectedSigner,
                expectedArchive,
                new SplitInstallToastListener(this)
        );
    }

    private AppCatalog.Entry findPrimaryCatalogEntry(String packageName) {
        for (AppCatalog.Entry entry : AppCatalog.getEntries()) {
            if (packageName.equals(entry.getPackageName())) {
                return entry;
            }
        }
        return null;
    }

    private void launchPackageInstaller(Uri uri) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setData(uri);
        InstallEnvironment.Snapshot snapshot =
                InstallEnvironment.inspect(this);
        if (snapshot.hasVerifiedInstallerHelper()) {
            installIntent.setPackage(InstallEnvironment.PACKAGE_INSTALLER);
        }
        grantInstallerReadAccess(installIntent, uri);
        try {
            startActivity(installIntent);
        } catch (ActivityNotFoundException | SecurityException primaryError) {
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setDataAndType(uri, APK_MIME);
            grantInstallerReadAccess(fallback, uri);
            try {
                startActivity(fallback);
            } catch (ActivityNotFoundException | SecurityException fallbackError) {
                Toast.makeText(
                        this,
                        R.string.installer_failed,
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void grantInstallerReadAccess(Intent intent, Uri uri) {
        intent.setClipData(ClipData.newRawUri("AlikDrone APK", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
