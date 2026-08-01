package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class ControllerFilesSafRepositoryTest {
    @Test
    public void deleteSnapshotMustMatchCurrentProviderMetadata() {
        ControllerFileItem original = item(42L, 1_000L);

        assertTrue(ControllerFilesSafRepository.sameMetadata(
                original,
                item(42L, 1_000L)
        ));
        assertFalse(ControllerFilesSafRepository.sameMetadata(
                original,
                item(43L, 1_000L)
        ));
        assertFalse(ControllerFilesSafRepository.sameMetadata(
                original,
                item(42L, 2_000L)
        ));
    }

    private static ControllerFileItem item(long size, long modifiedAt) {
        return new ControllerFileItem(
                Uri.parse("content://provider/tree/root/document/root%3Afile"),
                "record.mp4",
                "video/mp4",
                size,
                modifiedAt,
                false,
                true
        );
    }
}
