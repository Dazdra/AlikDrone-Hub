package ru.alikdrone.hub;

import java.util.ArrayList;
import java.util.List;

/** Public catalog: only broadly useful apps mirrored by AlikDrone. */
final class CatalogSource {
    private CatalogSource() {
    }

    static boolean isPublicEdition() {
        return true;
    }

    static boolean allowsCustomDownloads() {
        return false;
    }

    static List<AppCatalog.Entry> createEntries() {
        List<AppCatalog.Entry> entries = new ArrayList<>();
        entries.add(AppCatalog.downloadEntry(
                "telegram",
                "Telegram",
                "org.telegram.messenger.web",
                "https://alik-drone.ru/downloads/apps/telegram.apk",
                "Telegram.apk",
                69669,
                "49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1",
                "496309bb30d5c3880d090e5895e76c0420218fe3bbc85c2aae827e4e3bb12ee8",
                "org.telegram.messenger",
                "org.thunderdog.challegram"
        ));
        entries.add(AppCatalog.downloadEntry(
                "discord",
                "Discord",
                "com.discord",
                "https://alik-drone.ru/downloads/apps/discord.xapk",
                "Discord-336.10-RC2.xapk",
                336010,
                "3c39d23cf9367849a5c699395647fe0e5bfea5a1f1f40d8c717ddc70f8bfa113",
                "b2c68f88b585d00a101895ae9352db899b1f87e0b04ef437189386bb96ae05bd"
        ));
        entries.add(AppCatalog.downloadEntry(
                "youtube",
                "YouTube",
                "com.google.android.youtube",
                "https://alik-drone.ru/downloads/apps/youtube.apk",
                "YouTube.apk",
                1561185100L,
                "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
                "7d8c0a630cd857441b96d715935dcb2ded8c831f2c4ee3deca7bd54e186e3b1a",
                "com.google.android.youtube.tv"
        ));
        entries.add(AppCatalog.downloadEntry(
                "rutube",
                "RUTUBE",
                "ru.rutube.app",
                "https://alik-drone.ru/downloads/apps/rutube.apk",
                "RUTUBE.apk",
                3103003,
                "e907019f373b17cb2efc3525d738d636a99347ee3e0bc683dfba466cad1841d9",
                "b740173ca2daf548b7c3b1947b7444875656afab6453cb4655bb4557493365e3",
                "rtb.mobile.android"
        ));
        entries.add(AppCatalog.downloadEntry(
                "chrome",
                "Cromite — Chromium-браузер",
                "org.cromite.cromite",
                "https://alik-drone.ru/downloads/apps/chrome.apk",
                "Cromite.apk",
                777816800,
                "633fa41d8211d6d0916a819b89668c6de92e64232da67f9d16fd81c3b7e923ff",
                "a5eb0bbb4754fb1b029c37f54f7741329717b962670f2d579550670ded89410b",
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.chrome.canary",
                "org.chromium.chrome",
                "com.android.browser"
        ));
        entries.add(AppCatalog.downloadEntry(
                "happ",
                "Happ",
                "su.happ.proxyutility",
                "https://alik-drone.ru/downloads/apps/happ.apk",
                "Happ.apk",
                1567,
                "397e98abed03c5f263ffaf4e8a1043b2a59f538b99e75cf152fa05a7e8007d8d",
                "9a0e44dee2c77eaf580af16feb279455b9adfe7c2d5c1e9934caa56017e7ea96"
        ));
        entries.add(AppCatalog.downloadEntry(
                "shareit",
                "SHAREit",
                "com.lenovo.anyshare.gps",
                "https://alik-drone.ru/downloads/apps/shareit.apk",
                "SHAREit.apk",
                4765468L,
                "bd102a94669896078db9c665b21bbd3d76691ff66ae97519a3634c34c3a02542",
                "023062cf6f831d01ab5e6f3fdcc4f5127ba80e9689ac9085eacd575985d552ba"
        ));
        entries.add(AppCatalog.downloadEntry(
                "windy",
                "Windy",
                "com.windyty.android",
                "https://alik-drone.ru/downloads/apps/windy.apk",
                "Windy-50.1.1.apk",
                5001010L,
                "487e861a84064f74742069e3062759f273f3909ed7ff242d37e8206aa3082663",
                "4b4a8657b2ce1b4004cee54c9ecc1ffe955c3648211db7d03f3a663105291fe0"
        ));
        return entries;
    }
}
