package ru.alikdrone.hub;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class MessagingAppWarningsTest {
    @Test
    public void warnsForCatalogTelegramAndDiscord() {
        assertTrue(MessagingAppWarnings.requiresWarning(
                AppCatalog.findById("telegram")
        ));
        assertTrue(MessagingAppWarnings.requiresWarning(
                AppCatalog.findById("discord")
        ));
    }

    @Test
    public void warnsForAllKnownTelegramPackagesAndDiscord() {
        assertTrue(MessagingAppWarnings.requiresWarning(
                "org.telegram.messenger.web"
        ));
        assertTrue(MessagingAppWarnings.requiresWarning(
                "org.telegram.messenger"
        ));
        assertTrue(MessagingAppWarnings.requiresWarning(
                "org.thunderdog.challegram"
        ));
        assertTrue(MessagingAppWarnings.requiresWarning("com.discord"));
    }

    @Test
    public void doesNotWarnForOtherOrMissingPackages() {
        assertFalse(MessagingAppWarnings.requiresWarning(
                AppCatalog.findById("windy")
        ));
        assertFalse(MessagingAppWarnings.requiresWarning(
                "com.windyty.android"
        ));
        assertFalse(MessagingAppWarnings.requiresWarning((String) null));
        assertFalse(MessagingAppWarnings.requiresWarning(""));
    }
}
