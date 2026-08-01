package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ControllerFilesPolicyTest {
    @Test
    public void acceptsOnlySafContentUris() {
        assertTrue(ControllerFilesPolicy.isSafContentUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        ));
        assertFalse(ControllerFilesPolicy.isSafContentUri("file:///sdcard/Download"));
        assertFalse(ControllerFilesPolicy.isSafContentUri("/sdcard/Download"));
        assertFalse(ControllerFilesPolicy.isSafContentUri(null));
    }

    @Test
    public void deleteRequiresConfirmationAndCurrentListingMembership() {
        assertFalse(ControllerFilesPolicy.canDelete(false, true));
        assertFalse(ControllerFilesPolicy.canDelete(true, false));
        assertTrue(ControllerFilesPolicy.canDelete(true, true));
    }

    @Test
    public void scopeNoticeExplicitlyDisclaimsPrivateStorageBypass() {
        String notice = ControllerFilesPolicy.scopeNotice();
        assertTrue(notice.contains("Android/data"));
        assertTrue(notice.contains("Android 11"));
        assertTrue(notice.contains("Download"));
        assertTrue(notice.contains("корень SD-карты"));
        assertTrue(notice.contains("root"));
    }
}
