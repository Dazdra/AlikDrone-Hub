package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class MissionLibraryLifecycleTest {
    private static final byte[] VALID_KML = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
                    + "<Document/></kml>"
    ).getBytes(StandardCharsets.UTF_8);

    private Context context;
    private BlockingInputStream source;

    @Before
    public void resetLibrary() throws Exception {
        context = RuntimeEnvironment.getApplication();
        waitUntil(() -> !MissionLibraryActivity.isOperationRunningForTesting());
        context.getSharedPreferences(
                "mission_library_local",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        deleteRecursively(new File(context.getFilesDir(), "mission-library"));
        MissionLibraryActivity.resetOperationsForTesting();
    }

    @After
    public void finishOperation() throws Exception {
        if (source != null) {
            source.release();
        }
        waitUntil(() -> !MissionLibraryActivity.isOperationRunningForTesting());
        MissionLibraryActivity.resetOperationsForTesting();
        deleteRecursively(new File(context.getFilesDir(), "mission-library"));
    }

    @Test
    public void importFinishesIntoRecreatedActivityWithoutUpdatingDestroyedOne()
            throws Exception {
        Uri uri = Uri.parse("content://mission.test/route.kml");
        source = new BlockingInputStream(VALID_KML);
        shadowOf(context.getContentResolver()).registerInputStream(uri, source);

        ActivityController<MissionLibraryActivity> firstController =
                Robolectric.buildActivity(MissionLibraryActivity.class)
                        .setup();
        MissionLibraryActivity first = firstController.get();
        TextView oldStatus = first.findViewById(
                R.id.mission_library_status
        );
        first.onActivityResult(
                MissionLibraryActivity.REQUEST_MISSION,
                Activity.RESULT_OK,
                new Intent().setData(uri)
        );
        assertTrue(source.awaitRead());
        shadowOf(Looper.getMainLooper()).idle();
        String oldStatusAtDestroy = oldStatus.getText().toString();

        Bundle savedState = new Bundle();
        firstController.saveInstanceState(savedState)
                .pause()
                .stop()
                .destroy();

        ActivityController<MissionLibraryActivity> secondController =
                Robolectric.buildActivity(MissionLibraryActivity.class)
                        .create(savedState)
                        .start()
                        .resume()
                        .visible();
        MissionLibraryActivity second = secondController.get();
        shadowOf(Looper.getMainLooper()).idle();

        Button importButton = second.findViewById(
                R.id.button_mission_library_import
        );
        TextView newStatus = second.findViewById(
                R.id.mission_library_status
        );
        assertFalse(importButton.isEnabled());
        assertTrue(newStatus.getText().toString().contains("route.kml"));

        source.release();
        waitUntil(() -> {
            shadowOf(Looper.getMainLooper()).idle();
            return !MissionLibraryActivity.isOperationRunningForTesting();
        });
        shadowOf(Looper.getMainLooper()).idle();

        LinearLayout rows = second.findViewById(
                R.id.mission_library_rows
        );
        assertEquals(1, new MissionLibraryStore(context).list().size());
        assertEquals(1, rows.getChildCount());
        assertTrue(importButton.isEnabled());
        assertEquals(oldStatusAtDestroy, oldStatus.getText().toString());
        secondController.pause().stop().destroy();
    }

    @Test
    public void removalFinishesAfterRecreationWithoutUpdatingDestroyedActivity()
            throws Exception {
        File directory = new File(context.getFilesDir(), "mission-library");
        assertTrue(directory.mkdirs());
        File localCopy = new File(
                directory,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.kml"
        );
        try (FileOutputStream output = new FileOutputStream(localCopy)) {
            output.write(VALID_KML);
        }
        KmlKmzValidator.Result valid = KmlKmzValidator.Result.valid(
                "Структурно корректен, не проверка безопасности полёта.",
                Collections.<String>emptyList(),
                1,
                VALID_KML.length
        );
        new MissionLibraryStore(context).add(
                "remove-me.kml",
                localCopy.getAbsolutePath(),
                valid,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        ActivityController<MissionLibraryActivity> firstController =
                Robolectric.buildActivity(MissionLibraryActivity.class)
                        .setup();
        MissionLibraryActivity first = firstController.get();
        LinearLayout firstRows = first.findViewById(
                R.id.mission_library_rows
        );
        assertEquals(1, firstRows.getChildCount());
        firstRows.getChildAt(0)
                .findViewById(R.id.button_mission_library_remove)
                .performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        Bundle savedState = new Bundle();
        firstController.saveInstanceState(savedState)
                .pause()
                .stop()
                .destroy();
        TextView oldStatus = first.findViewById(
                R.id.mission_library_status
        );
        String oldStatusAtDestroy = oldStatus.getText().toString();

        ActivityController<MissionLibraryActivity> secondController =
                Robolectric.buildActivity(MissionLibraryActivity.class)
                        .create(savedState)
                        .start()
                        .resume()
                        .visible();
        MissionLibraryActivity second = secondController.get();
        waitUntil(() -> {
            shadowOf(Looper.getMainLooper()).idle();
            return !MissionLibraryActivity.isOperationRunningForTesting();
        });
        shadowOf(Looper.getMainLooper()).idle();

        LinearLayout secondRows = second.findViewById(
                R.id.mission_library_rows
        );
        assertTrue(new MissionLibraryStore(context).list().isEmpty());
        assertFalse(localCopy.exists());
        assertEquals(0, secondRows.getChildCount());
        assertTrue(((Button) second.findViewById(
                R.id.button_mission_library_import
        )).isEnabled());
        assertEquals(oldStatusAtDestroy, oldStatus.getText().toString());
        secondController.pause().stop().destroy();
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!condition.isSatisfied() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.isSatisfied());
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private interface Condition {
        boolean isSatisfied() throws Exception;
    }

    private static final class BlockingInputStream extends InputStream {
        private final byte[] bytes;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private int offset;

        BlockingInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean awaitRead() throws InterruptedException {
            return readStarted.await(5L, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int count = read(oneByte, 0, 1);
            return count == -1 ? -1 : oneByte[0] & 0xff;
        }

        @Override
        public int read(byte[] destination, int destinationOffset, int length)
                throws IOException {
            if (offset == 0) {
                readStarted.countDown();
                try {
                    if (!released.await(5L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release input");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            if (offset >= bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(
                    bytes,
                    offset,
                    destination,
                    destinationOffset,
                    count
            );
            offset += count;
            return count;
        }
    }
}
