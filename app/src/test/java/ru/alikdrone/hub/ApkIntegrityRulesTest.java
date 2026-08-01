package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public final class ApkIntegrityRulesTest {
    @Test
    public void sha256ComparisonAcceptsOnlyExactHex() {
        byte[] digest = {(byte) 0xA5, 0x01, (byte) 0xFF};

        assertTrue(ApkIntegrityRules.constantTimeHexEquals(
                digest,
                "a501ff"
        ));
        assertTrue(ApkIntegrityRules.constantTimeHexEquals(
                digest,
                "A501FF"
        ));
        assertFalse(ApkIntegrityRules.constantTimeHexEquals(
                digest,
                "a50100"
        ));
        assertFalse(ApkIntegrityRules.constantTimeHexEquals(
                digest,
                "nothex"
        ));
    }

    @Test
    public void expectedSha256SupportsPinsAndUnpinnedDownloads() {
        byte[] digest = {(byte) 0xA5, 0x01, (byte) 0xFF};

        assertTrue(ApkIntegrityRules.matchesExpectedSha256(
                digest,
                "a501ff"
        ));
        assertTrue(ApkIntegrityRules.matchesExpectedSha256(
                digest,
                "A501FF"
        ));
        assertFalse(ApkIntegrityRules.matchesExpectedSha256(
                digest,
                "a50100"
        ));
        assertTrue(ApkIntegrityRules.matchesExpectedSha256(
                null,
                null
        ));
        assertFalse(ApkIntegrityRules.matchesExpectedSha256(
                null,
                "a501ff"
        ));
    }

    @Test
    public void nativeAbiIsReadOnlyFromLibraryEntries() {
        assertTrue("armeabi-v7a".equals(
                ApkIntegrityRules.nativeAbiFromZipEntry(
                        "lib/armeabi-v7a/libfoo.so"
                )
        ));
        assertNull(ApkIntegrityRules.nativeAbiFromZipEntry("classes.dex"));
        assertNull(ApkIntegrityRules.nativeAbiFromZipEntry("lib/armeabi-v7a/"));
    }

    @Test
    public void universalOrMatchingArchiveIsCompatible() {
        assertTrue(ApkIntegrityRules.supportsDeviceAbis(
                Collections.emptySet(),
                new String[]{"armeabi-v7a"}
        ));
        assertTrue(ApkIntegrityRules.supportsDeviceAbis(
                new HashSet<>(Arrays.asList("arm64-v8a", "armeabi-v7a")),
                new String[]{"armeabi-v7a", "armeabi"}
        ));
    }

    @Test
    public void arm64OnlyArchiveIsRejectedOn32BitController() {
        assertFalse(ApkIntegrityRules.supportsDeviceAbis(
                Collections.singleton("arm64-v8a"),
                new String[]{"armeabi-v7a", "armeabi"}
        ));
    }
}
