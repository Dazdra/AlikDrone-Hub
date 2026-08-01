package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InstallEnvironmentTest {
    @Test
    public void explicitInstallRestrictionIsReportedAsWarning() {
        assertEquals(
                InstallEnvironment.InstallState.POLICY_WARNING,
                InstallEnvironment.evaluate(
                        30,
                        false,
                        true,
                        false,
                        false,
                        true
                )
        );
    }

    @Test
    public void perUserUnknownSourceRestrictionIsReportedAsWarning() {
        assertEquals(
                InstallEnvironment.InstallState.POLICY_WARNING,
                InstallEnvironment.evaluate(
                        30,
                        false,
                        false,
                        true,
                        false,
                        true
                )
        );
    }

    @Test
    public void globalUnknownSourceRestrictionIsReportedAsWarning() {
        assertEquals(
                InstallEnvironment.InstallState.POLICY_WARNING,
                InstallEnvironment.evaluate(
                        30,
                        false,
                        false,
                        false,
                        true,
                        true
                )
        );
    }

    @Test
    public void androidORequiresPerSourcePermission() {
        assertEquals(
                InstallEnvironment.InstallState.NEEDS_SOURCE_PERMISSION,
                InstallEnvironment.evaluate(
                        30,
                        false,
                        false,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    public void androidOIsReadyWhenSourceIsAllowed() {
        assertEquals(
                InstallEnvironment.InstallState.READY,
                InstallEnvironment.evaluate(
                        30,
                        false,
                        false,
                        false,
                        false,
                        true
                )
        );
    }

    @Test
    public void legacyAndroidUsesSystemConfirmation() {
        assertEquals(
                InstallEnvironment.InstallState.LEGACY_SYSTEM_CONFIRMATION,
                InstallEnvironment.evaluate(
                        23,
                        false,
                        false,
                        false,
                        false,
                        null
                )
        );
    }

    @Test
    public void failedInspectionDoesNotPretendDeviceIsReady() {
        assertEquals(
                InstallEnvironment.InstallState.UNKNOWN,
                InstallEnvironment.evaluate(
                        30,
                        true,
                        false,
                        false,
                        false,
                        true
                )
        );
    }

    @Test
    public void stockSystemPackagesDoNotPretendHelpersWereInstalled() {
        InstallEnvironment.PackageComponent stockInstaller =
                new InstallEnvironment.PackageComponent(
                        true,
                        "11",
                        30L,
                        true,
                        false,
                        false
                );
        InstallEnvironment.PackageComponent stockFiles =
                new InstallEnvironment.PackageComponent(
                        true,
                        "0.20",
                        121L,
                        true,
                        false,
                        false
                );

        assertFalse(stockInstaller.isKnownInstallerHelper());
        assertFalse(stockFiles.isKnownDocumentsHelper());
    }

    @Test
    public void updatedDjiSystemPackagesConfirmBothHelpers() {
        InstallEnvironment.PackageComponent updatedInstaller =
                new InstallEnvironment.PackageComponent(
                        true,
                        "11",
                        30L,
                        true,
                        true,
                        true
                );
        InstallEnvironment.PackageComponent updatedFiles =
                new InstallEnvironment.PackageComponent(
                        true,
                        "0.20.12.23",
                        121L,
                        true,
                        true,
                        true
                );

        assertTrue(updatedInstaller.isKnownInstallerHelper());
        assertTrue(updatedFiles.isKnownDocumentsHelper());
    }

    @Test
    public void disabledUpdatedPackageIsNotReportedReady() {
        InstallEnvironment.PackageComponent disabledInstaller =
                new InstallEnvironment.PackageComponent(
                        true,
                        "11",
                        30L,
                        true,
                        true,
                        false
                );

        assertFalse(disabledInstaller.isKnownInstallerHelper());
    }

    @Test
    public void verifiedInstallerHelperIsVisibleEvenWhenPolicyProbeWarns() {
        InstallEnvironment.PackageComponent verifiedInstaller =
                new InstallEnvironment.PackageComponent(
                        true,
                        "11",
                        30L,
                        true,
                        true,
                        true
                );
        InstallEnvironment.PackageComponent files =
                new InstallEnvironment.PackageComponent(
                        true,
                        "0.20.12.23",
                        121L,
                        true,
                        true,
                        true
                );
        InstallEnvironment.Snapshot snapshot =
                new InstallEnvironment.Snapshot(
                        30,
                        InstallEnvironment.InstallState.POLICY_WARNING,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        verifiedInstaller,
                        files
                );

        assertTrue(snapshot.hasVerifiedInstallerHelper());
        assertTrue(snapshot.needsSourcePermission());
    }
}
