package ru.alikdrone.hub;

import java.security.MessageDigest;
import java.util.Set;

/**
 * Pure APK integrity and ABI rules shared by the Android controller and tests.
 */
final class ApkIntegrityRules {
    private ApkIntegrityRules() {
    }

    static boolean constantTimeHexEquals(byte[] actual, String expectedHex) {
        if (actual == null
                || expectedHex == null
                || expectedHex.length() != actual.length * 2) {
            return false;
        }
        byte[] expected = new byte[actual.length];
        for (int index = 0; index < expected.length; index++) {
            int high = Character.digit(expectedHex.charAt(index * 2), 16);
            int low = Character.digit(expectedHex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return false;
            }
            expected[index] = (byte) ((high << 4) | low);
        }
        return MessageDigest.isEqual(actual, expected);
    }

    static boolean matchesExpectedSha256(
            byte[] actual,
            String expectedHex
    ) {
        return expectedHex == null
                || constantTimeHexEquals(actual, expectedHex);
    }

    static String nativeAbiFromZipEntry(String entryName) {
        if (entryName == null || !entryName.startsWith("lib/")) {
            return null;
        }
        int separator = entryName.indexOf('/', 4);
        if (separator <= 4 || separator == entryName.length() - 1) {
            return null;
        }
        return entryName.substring(4, separator);
    }

    static boolean supportsDeviceAbis(
            Set<String> archiveAbis,
            String[] supportedDeviceAbis
    ) {
        if (archiveAbis == null || archiveAbis.isEmpty()) {
            return true;
        }
        if (supportedDeviceAbis == null || supportedDeviceAbis.length == 0) {
            return false;
        }
        for (String deviceAbi : supportedDeviceAbis) {
            if (deviceAbi != null && archiveAbis.contains(deviceAbi)) {
                return true;
            }
        }
        return false;
    }
}
