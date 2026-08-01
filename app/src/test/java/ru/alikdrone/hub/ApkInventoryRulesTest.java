package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ApkInventoryRulesTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingPackageIsOffered() {
        assertTrue(ApkInventoryRules.shouldOffer(null, 42L));
    }

    @Test
    public void newerPackageIsOffered() {
        assertTrue(ApkInventoryRules.shouldOffer(41L, 42L));
    }

    @Test
    public void sameOrOlderPackageIsSkipped() {
        assertFalse(ApkInventoryRules.shouldOffer(42L, 42L));
        assertFalse(ApkInventoryRules.shouldOffer(43L, 42L));
        assertFalse(ApkInventoryRules.shouldOffer(null, -1L));
    }

    @Test
    public void materializedScanCacheRequiresKnownUnchangedMtime() {
        assertTrue(ApkInventoryRules.canReuseMaterializedScan(
                100L,
                2_000L,
                100L,
                1_000L,
                2_500L,
                1_000L
        ));
        assertFalse(ApkInventoryRules.canReuseMaterializedScan(
                100L,
                2_000L,
                100L,
                0L,
                2_500L,
                1_000L
        ));
        assertFalse(ApkInventoryRules.canReuseMaterializedScan(
                99L,
                2_000L,
                100L,
                1_000L,
                2_500L,
                1_000L
        ));
        assertFalse(ApkInventoryRules.canReuseMaterializedScan(
                100L,
                999L,
                100L,
                1_000L,
                2_500L,
                1_000L
        ));
        assertFalse(ApkInventoryRules.canReuseMaterializedScan(
                100L,
                2_000L,
                100L,
                1_000L,
                3_001L,
                1_000L
        ));
    }

    @Test
    public void validatesApkFileNames() {
        assertTrue(ApkInventoryRules.isSafeApkFileName("Telegram 11.2.3.apk"));
        assertTrue(ApkInventoryRules.isSafeApkFileName("app-release.APK"));

        assertFalse(ApkInventoryRules.isSafeApkFileName(null));
        assertFalse(ApkInventoryRules.isSafeApkFileName("../escape.apk"));
        assertFalse(ApkInventoryRules.isSafeApkFileName("nested/app.apk"));
        assertFalse(ApkInventoryRules.isSafeApkFileName("notes.txt"));
    }

    @Test
    public void validatesSplitPackageFileNames() {
        assertTrue(ApkInventoryRules.isSafePackageFileName(
                "Discord-338.13.xapk"
        ));
        assertFalse(ApkInventoryRules.isSafePackageFileName(
                "Discord-release.apks"
        ));
        assertFalse(ApkInventoryRules.isSafePackageFileName(
                "../Discord.xapk"
        ));
        assertFalse(ApkInventoryRules.isSafePackageFileName(
                "Discord.zip"
        ));
    }

    @Test
    public void confinesFilesAndDirectoriesToAllowedRoot() throws Exception {
        File root = temporaryFolder.newFolder("inbox");
        File nested = new File(root, "nested");
        assertTrue(nested.mkdir());
        File apk = new File(nested, "candidate.apk");
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(1);
        }

        File outside = temporaryFolder.newFile("outside.apk");

        assertTrue(ApkInventoryRules.isSafeChildDirectory(root, nested));
        assertTrue(ApkInventoryRules.isSafeChild(root, apk));
        assertFalse(ApkInventoryRules.isSafeChild(root, outside));
        assertFalse(ApkInventoryRules.isSafeChildDirectory(root, root));
    }

    @Test
    public void dedupPrefersHighestVersion() {
        assertTrue(ApkInventoryRules.preferCandidate(
                10L,
                200L,
                "current",
                11L,
                100L,
                "candidate"
        ));
        assertFalse(ApkInventoryRules.preferCandidate(
                11L,
                100L,
                "current",
                10L,
                200L,
                "candidate"
        ));
    }

    @Test
    public void dedupPrefersNewestThenStableIdentity() {
        assertTrue(ApkInventoryRules.preferCandidate(
                10L,
                100L,
                "b",
                10L,
                200L,
                "z"
        ));
        assertTrue(ApkInventoryRules.preferCandidate(
                10L,
                200L,
                "b",
                10L,
                200L,
                "a"
        ));
        assertFalse(ApkInventoryRules.preferCandidate(
                10L,
                200L,
                "a",
                10L,
                200L,
                "b"
        ));
    }
}
