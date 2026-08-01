package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

public final class ApkSigningIdentityTest {
    @Test
    public void sameSignerIsCompatibleForUpdate() {
        ApkSigningIdentity installed = single("current");
        ApkSigningIdentity candidate = single("current");

        assertTrue(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                candidate
        ));
    }

    @Test
    public void forwardSignerRotationIsCompatibleForUpdate() {
        ApkSigningIdentity installed = single("old");
        ApkSigningIdentity candidate = single("old", "current");

        assertTrue(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                candidate
        ));
    }

    @Test
    public void unrelatedSignerIsNotCompatibleForUpdate() {
        ApkSigningIdentity installed = single("installed");
        ApkSigningIdentity candidate = single("unrelated");

        assertFalse(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                candidate
        ));
    }

    @Test
    public void reverseSignerRotationIsNotCompatibleForUpdate() {
        ApkSigningIdentity installed = single("old", "current");
        ApkSigningIdentity candidate = single("old");

        assertFalse(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                candidate
        ));
    }

    @Test
    public void multipleSignersRequireTheExactCurrentSet() {
        ApkSigningIdentity installed = multiple("first", "second");

        assertTrue(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                multiple("second", "first")
        ));
        assertFalse(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                multiple("first", "third")
        ));
        assertFalse(ApkInventoryRules.signingCompatibleForUpdate(
                installed,
                single("first", "second")
        ));
    }

    @Test
    public void artifactMatchRequiresBothDigestAndSignerIdentity() {
        ApkSigningIdentity signer = single("signer");

        assertTrue(ApkInventoryRules.sameArtifact(
                "content-sha256",
                signer,
                "content-sha256",
                single("signer")
        ));
        assertFalse(ApkInventoryRules.sameArtifact(
                "content-sha256",
                signer,
                "different-content",
                single("signer")
        ));
        assertFalse(ApkInventoryRules.sameArtifact(
                "content-sha256",
                signer,
                "content-sha256",
                single("different-signer")
        ));
        assertFalse(ApkInventoryRules.sameArtifact(
                "content-sha256",
                single("old", "signer"),
                "content-sha256",
                single("signer")
        ));
    }

    private static ApkSigningIdentity single(String... history) {
        return ApkSigningIdentity.singleSignerHistory(
                Arrays.asList(history)
        );
    }

    private static ApkSigningIdentity multiple(String... signers) {
        return ApkSigningIdentity.multipleSigners(
                new HashSet<>(Arrays.asList(signers))
        );
    }
}
