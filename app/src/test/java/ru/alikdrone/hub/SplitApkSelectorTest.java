package ru.alikdrone.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class SplitApkSelectorTest {
    @Test
    public void selectsBaseArmV7aDensityAndRussianLocale() {
        List<String> entries = Arrays.asList(
                "manifest.json",
                "base.apk",
                "split_config.arm64_v8a.apk",
                "split_config.armeabi_v7a.apk",
                "split_config.hdpi.apk",
                "split_config.xxhdpi.apk",
                "split_config.ru.apk",
                "split_config.nl.apk"
        );

        SplitApkSelector.Selection result = SplitApkSelector.select(
                entries,
                Arrays.asList("armeabi-v7a", "armeabi"),
                480,
                Collections.singletonList("ru-RU")
        );

        assertEquals(
                "split_config.armeabi_v7a.apk",
                result.getAbiEntry()
        );
        assertEquals("split_config.xxhdpi.apk", result.getDensityEntry());
        assertEquals(
                Collections.singletonList("split_config.ru.apk"),
                result.getLocaleEntries()
        );
        assertEquals(Arrays.asList(
                "base.apk",
                "split_config.armeabi_v7a.apk",
                "split_config.xxhdpi.apk",
                "split_config.ru.apk"
        ), result.getEntries());
        assertFalse(result.getEntries().contains(
                "split_config.arm64_v8a.apk"
        ));
    }

    @Test
    public void rejectsArm64OnlyBundleOnArmV7aDevice() {
        try {
            SplitApkSelector.select(
                    Arrays.asList(
                            "base.apk",
                            "split_config.arm64_v8a.apk"
                    ),
                    Collections.singletonList("armeabi-v7a"),
                    320,
                    Collections.singletonList("ru")
            );
            fail("Expected incompatible ABI failure");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("CPU architecture"));
        }
    }

    @Test
    public void selectsActualDiscordRc2ArchiveNames() {
        SplitApkSelector.Selection result = SplitApkSelector.select(
                Arrays.asList(
                        "base.apk",
                        "config.armeabi_v7a.apk",
                        "config.en.apk",
                        "config.hdpi.apk",
                        "config.ru.apk",
                        "config.xhdpi.apk"
                ),
                Arrays.asList("armeabi-v7a", "armeabi"),
                240,
                Collections.singletonList("ru-RU")
        );

        assertEquals(Arrays.asList(
                "base.apk",
                "config.armeabi_v7a.apk",
                "config.hdpi.apk",
                "config.ru.apk"
        ), result.getEntries());
    }

    @Test
    public void monolithicBaseDoesNotRequireAbiSplit() {
        SplitApkSelector.Selection result = SplitApkSelector.select(
                Collections.singletonList("base.apk"),
                Collections.singletonList("armeabi-v7a"),
                320,
                Collections.singletonList("ru")
        );

        assertNull(result.getAbiEntry());
        assertNull(result.getDensityEntry());
        assertEquals(
                Collections.singletonList("base.apk"),
                result.getEntries()
        );
    }

    @Test
    public void choosesClosestDensityAndKeepsRequiredFeature() {
        SplitApkSelector.Selection result = SplitApkSelector.select(
                Arrays.asList(
                        "base.apk",
                        "voice_feature.apk",
                        "split_config.hdpi.apk",
                        "split_config.xhdpi.apk"
                ),
                Collections.singletonList("armeabi-v7a"),
                280,
                Collections.singletonList("en-US")
        );

        assertEquals("split_config.xhdpi.apk", result.getDensityEntry());
        assertEquals(Arrays.asList(
                "base.apk",
                "voice_feature.apk",
                "split_config.xhdpi.apk"
        ), result.getEntries());
    }

    @Test
    public void unsafeZipPathIsNeverSelected() {
        SplitApkSelector.Selection result = SplitApkSelector.select(
                Arrays.asList(
                        "base.apk",
                        "../split_config.armeabi_v7a.apk",
                        "nested/split_config.xxhdpi.apk"
                ),
                Collections.singletonList("armeabi-v7a"),
                480,
                Collections.singletonList("ru")
        );

        assertEquals(
                Collections.singletonList("base.apk"),
                result.getEntries()
        );
        assertFalse(SplitApkSelector.isSafeFlatApkEntry("../base.apk"));
        assertFalse(SplitApkSelector.isSafeFlatApkEntry("nested/base.apk"));
    }
}
