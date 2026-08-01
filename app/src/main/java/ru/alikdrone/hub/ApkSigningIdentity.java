package ru.alikdrone.hub;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical signer identity for an installed package or APK archive.
 *
 * <p>Single-signer identities retain the ordered signing-certificate history
 * exposed on Android 9+, allowing a candidate signed with a valid forward key
 * rotation to be recognized as an update. Multi-signer packages have no
 * rotation lineage and therefore retain an order-independent signer set.</p>
 */
final class ApkSigningIdentity {
    private final Set<String> currentSigners;
    private final List<String> certificateHistory;
    private final boolean multipleSigners;

    private ApkSigningIdentity(
            Set<String> currentSigners,
            List<String> certificateHistory,
            boolean multipleSigners
    ) {
        this.currentSigners = Collections.unmodifiableSet(
                new TreeSet<>(currentSigners)
        );
        this.certificateHistory = Collections.unmodifiableList(
                new ArrayList<>(certificateHistory)
        );
        this.multipleSigners = multipleSigners;
    }

    static ApkSigningIdentity fromPackageInfo(PackageInfo info) {
        if (info == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return fromLegacySignatures(info.signatures);
        }

        SigningInfo signingInfo = info.signingInfo;
        if (signingInfo == null) {
            return null;
        }
        if (signingInfo.hasMultipleSigners()) {
            Signature[] current = signingInfo.getApkContentsSigners();
            Set<String> currentDigests = signerDigests(current);
            if (current == null
                    || currentDigests.size() != current.length
                    || currentDigests.size() < 2) {
                return null;
            }
            return multipleSigners(currentDigests);
        }

        Signature[] history = signingInfo.getSigningCertificateHistory();
        List<String> historyDigests = orderedSignerDigests(history);
        if (historyDigests.isEmpty()) {
            return null;
        }
        return singleSignerHistory(historyDigests);
    }

    private static ApkSigningIdentity fromLegacySignatures(
            Signature[] signatures
    ) {
        Set<String> digests = signerDigests(signatures);
        if (signatures == null
                || digests.isEmpty()
                || digests.size() != signatures.length) {
            return null;
        }
        if (digests.size() == 1) {
            return singleSignerHistory(new ArrayList<>(digests));
        }
        return multipleSigners(digests);
    }

    static ApkSigningIdentity singleSignerHistory(List<String> history) {
        List<String> normalizedHistory = normalizeHistory(history);
        if (normalizedHistory.isEmpty()
                || new HashSet<>(normalizedHistory).size()
                != normalizedHistory.size()) {
            throw new IllegalArgumentException("Invalid signer history");
        }
        String current = normalizedHistory.get(normalizedHistory.size() - 1);
        return new ApkSigningIdentity(
                Collections.singleton(current),
                normalizedHistory,
                false
        );
    }

    static ApkSigningIdentity multipleSigners(Set<String> signers) {
        Set<String> normalizedSigners = normalizeSigners(signers);
        if (normalizedSigners.size() < 2) {
            throw new IllegalArgumentException("Multiple signers required");
        }
        return new ApkSigningIdentity(
                normalizedSigners,
                Collections.emptyList(),
                true
        );
    }

    /**
     * Returns whether this candidate signer identity can update the installed
     * identity without moving backward in a single-signer rotation lineage.
     */
    boolean canUpgradeFrom(ApkSigningIdentity installed) {
        if (installed == null) {
            return false;
        }
        if (multipleSigners || installed.multipleSigners) {
            return multipleSigners
                    && installed.multipleSigners
                    && currentSigners.equals(installed.currentSigners);
        }
        if (currentSigners.size() != 1
                || installed.currentSigners.size() != 1) {
            return false;
        }
        String installedCurrent = installed.currentSigners.iterator().next();
        return certificateHistory.contains(installedCurrent);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApkSigningIdentity)) {
            return false;
        }
        ApkSigningIdentity identity = (ApkSigningIdentity) other;
        return multipleSigners == identity.multipleSigners
                && currentSigners.equals(identity.currentSigners)
                && certificateHistory.equals(identity.certificateHistory);
    }

    @Override
    public int hashCode() {
        int result = currentSigners.hashCode();
        result = 31 * result + certificateHistory.hashCode();
        result = 31 * result + (multipleSigners ? 1 : 0);
        return result;
    }

    private static Set<String> signerDigests(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) {
            if (signature == null) {
                return Collections.emptySet();
            }
            result.add(sha256(signature.toByteArray()));
        }
        return result;
    }

    private static List<String> orderedSignerDigests(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(signatures.length);
        for (Signature signature : signatures) {
            if (signature == null) {
                return Collections.emptyList();
            }
            result.add(sha256(signature.toByteArray()));
        }
        return result;
    }

    private static List<String> normalizeHistory(List<String> history) {
        if (history == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(history.size());
        for (String signer : history) {
            String normalized = normalizeSigner(signer);
            if (normalized == null) {
                return Collections.emptyList();
            }
            result.add(normalized);
        }
        return result;
    }

    private static Set<String> normalizeSigners(Set<String> signers) {
        if (signers == null) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String signer : signers) {
            String normalized = normalizeSigner(signer);
            if (normalized == null) {
                return Collections.emptySet();
            }
            result.add(normalized);
        }
        return result;
    }

    private static String normalizeSigner(String signer) {
        if (signer == null || signer.trim().isEmpty()) {
            return null;
        }
        return signer.trim().toLowerCase(Locale.US);
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) {
            result.append(String.format(Locale.US, "%02x", part & 0xff));
        }
        return result.toString();
    }
}
