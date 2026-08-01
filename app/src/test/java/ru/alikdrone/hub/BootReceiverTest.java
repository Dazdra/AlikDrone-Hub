package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public final class BootReceiverTest {
    @Test
    public void acceptsSupportedRestoreActions() {
        assertTrue(BootReceiver.isRestoreAction(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        assertTrue(BootReceiver.isRestoreAction(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(BootReceiver.isRestoreAction(Intent.ACTION_USER_UNLOCKED));
        assertTrue(BootReceiver.isRestoreAction(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertTrue(BootReceiver.isRestoreAction(
                BootReceiver.ACTION_RESTORE_TASKBAR
        ));
    }

    @Test
    public void rejectsUnrelatedActions() {
        assertFalse(BootReceiver.isRestoreAction(Intent.ACTION_PACKAGE_ADDED));
        assertFalse(BootReceiver.isRestoreAction(null));
    }
}
