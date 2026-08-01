package ru.alikdrone.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

public final class OperationsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_operations);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));

        findViewById(R.id.button_operations_back).setOnClickListener(
                ignored -> finish()
        );
        findViewById(R.id.button_operations_flight_records).setOnClickListener(
                ignored -> showFlightRecordExportGuide()
        );
        findViewById(R.id.button_operations_web_tools).setOnClickListener(
                ignored -> open(WebToolsActivity.class)
        );
        findViewById(R.id.button_operations_support_report).setOnClickListener(
                ignored -> open(SupportReportActivity.class)
        );
        findViewById(R.id.button_operations_files).setOnClickListener(
                ignored -> open(ControllerFilesActivity.class)
        );
        findViewById(R.id.button_operations_missions).setOnClickListener(
                ignored -> open(MissionLibraryActivity.class)
        );
        findViewById(R.id.button_operations_maintenance).setOnClickListener(
                ignored -> open(MaintenanceActivity.class)
        );
    }

    private void open(Class<? extends Activity> activityClass) {
        startActivity(new Intent(this, activityClass));
    }

    private void showFlightRecordExportGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.operations_flight_records_title)
                .setMessage(R.string.operations_flight_records_pc_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
