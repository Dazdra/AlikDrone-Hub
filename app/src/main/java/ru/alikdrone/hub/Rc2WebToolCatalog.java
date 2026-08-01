package ru.alikdrone.hub;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fixed, reviewed web destinations shown by the Hub.
 *
 * <p>This is intentionally not a general-purpose browser or a remote catalog.
 * Keeping both entries and hosts in the APK prevents a catalog response or
 * user-controlled string from becoming an arbitrary URL launcher.</p>
 */
public final class Rc2WebToolCatalog {
    public enum Compatibility {
        RC2_HANDOFF("RC 2: передача файлов вручную"),
        WEB_PLANNING("Веб-планирование; не управляет через RC 2"),
        WEB_PROCESSING("Обработка данных на ПК/веб"),
        DESKTOP_PROCESSING("Настольная обработка; не приложение RC 2"),
        DESKTOP_PLANNING("Настольное планирование; не приложение RC 2"),
        ENTERPRISE_ONLY("DJI Enterprise; не для потребительского RC 2");

        private final String label;

        Compatibility(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class Entry {
        private final String id;
        private final String title;
        private final String platform;
        private final Compatibility compatibility;
        private final String description;
        private final String url;

        Entry(
                String id,
                String title,
                String platform,
                Compatibility compatibility,
                String description,
                String url
        ) {
            this.id = id;
            this.title = title;
            this.platform = platform;
            this.compatibility = compatibility;
            this.description = description;
            this.url = url;
            if (!isAllowedUrl(url)) {
                throw new IllegalArgumentException("Web tool URL is not allowlisted");
            }
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getPlatform() {
            return platform;
        }

        public Compatibility getCompatibility() {
            return compatibility;
        }

        public String getDescription() {
            return description;
        }

        public String getUrl() {
            return url;
        }
    }

    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "app.airdata.com",
                    "www.dronelogbook.com",
                    "hub.flylitchi.com",
                    "www.dronelink.com",
                    "www.mapsmadeeasy.com",
                    "enterprise.dji.com",
                    "www.pix4d.com",
                    "www.agisoft.com",
                    "www.dronedeploy.com",
                    "www.sphengineering.com"
            ))
    );

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(
            Arrays.asList(
                    new Entry(
                            "airdata",
                            "AirData UAV",
                            "Веб",
                            Compatibility.RC2_HANDOFF,
                            "Загрузка TXT-журналов FlightRecord, история полётов "
                                    + "и обслуживание. RC 2 передаёт журналы вручную.",
                            "https://app.airdata.com/"
                    ),
                    new Entry(
                            "dronelogbook",
                            "DroneLogbook",
                            "Веб",
                            Compatibility.RC2_HANDOFF,
                            "Журнал пилота и флота. Используйте экспортированные "
                                    + "файлы, а не доступ к аккаунту DJI из Hub.",
                            "https://www.dronelogbook.com/"
                    ),
                    new Entry(
                            "litchi_hub",
                            "Litchi Mission Hub",
                            "Веб",
                            Compatibility.WEB_PLANNING,
                            "Создание и просмотр маршрутов. Наличие маршрута не "
                                    + "означает поддержку управления через DJI RC 2.",
                            "https://hub.flylitchi.com/"
                    ),
                    new Entry(
                            "dronelink",
                            "Dronelink",
                            "Веб",
                            Compatibility.WEB_PLANNING,
                            "Планирование миссий и экспорт. DJI RC 2 не является "
                                    + "поддерживаемым контроллером Dronelink.",
                            "https://www.dronelink.com/"
                    ),
                    new Entry(
                            "maps_made_easy",
                            "Maps Made Easy",
                            "Веб",
                            Compatibility.WEB_PROCESSING,
                            "Обработка набора фотографий после полёта. Hub только "
                                    + "помогает подготовить и передать исходные файлы.",
                            "https://www.mapsmadeeasy.com/"
                    ),
                    new Entry(
                            "flighthub_2",
                            "DJI FlightHub 2",
                            "Веб",
                            Compatibility.ENTERPRISE_ONLY,
                            "Официальная облачная платформа DJI Enterprise. Не "
                                    + "подменяет DJI Fly и не добавляет RC 2 поддержку.",
                            "https://enterprise.dji.com/flighthub-2"
                    ),
                    new Entry(
                            "dji_terra",
                            "DJI Terra",
                            "Windows · DJI Enterprise",
                            Compatibility.DESKTOP_PROCESSING,
                            "Фотограмметрия и реконструкция на мощном ПК. "
                                    + "Открывается официальная страница загрузок; "
                                    + "это не APK и не запускается на RC 2.",
                            "https://enterprise.dji.com/dji-terra/downloads"
                    ),
                    new Entry(
                            "dji_modify",
                            "DJI Modify",
                            "Windows · DJI Enterprise",
                            Compatibility.DESKTOP_PROCESSING,
                            "Редактирование облаков точек и 3D-моделей на ПК. "
                                    + "Hub не переносит алгоритмы DJI и не скачивает "
                                    + "установщик автоматически.",
                            "https://enterprise.dji.com/modify/downloads"
                    ),
                    new Entry(
                            "dji_reality",
                            "DJI Reality",
                            "Windows · DJI Enterprise",
                            Compatibility.DESKTOP_PROCESSING,
                            "Реконструкция и просмотр моделей на ПК. Официальная "
                                    + "загрузка размещена вместе с DJI Modify/Terra.",
                            "https://enterprise.dji.com/modify/downloads"
                    ),
                    new Entry(
                            "pix4dmatic",
                            "PIX4Dmatic",
                            "Windows",
                            Compatibility.DESKTOP_PROCESSING,
                            "Обработка больших наборов аэрофотоснимков и подготовка "
                                    + "геоданных на компьютере.",
                            "https://www.pix4d.com/product/"
                                    + "pix4dmatic-large-scale-photogrammetry-software/"
                    ),
                    new Entry(
                            "pix4dmapper",
                            "PIX4Dmapper",
                            "Windows",
                            Compatibility.DESKTOP_PROCESSING,
                            "Классическая фотограмметрия, ортомозаики и 3D-модели. "
                                    + "Фотографии передаются с пульта на ПК отдельно.",
                            "https://www.pix4d.com/product/"
                                    + "pix4dmapper-photogrammetry-software"
                    ),
                    new Entry(
                            "agisoft_metashape",
                            "Agisoft Metashape",
                            "Windows · macOS · Linux",
                            Compatibility.DESKTOP_PROCESSING,
                            "Настольная фотограмметрия. Ссылка ведёт на официальные "
                                    + "установщики и сведения о лицензии.",
                            "https://www.agisoft.com/downloads/installer/"
                    ),
                    new Entry(
                            "dronedeploy",
                            "DroneDeploy Aerial",
                            "Веб / облако",
                            Compatibility.WEB_PROCESSING,
                            "Загрузка и обработка отснятого набора. Эта карточка не "
                                    + "обещает управление дроном через DJI RC 2.",
                            "https://www.dronedeploy.com/product/dronedeploy-aerial"
                    ),
                    new Entry(
                            "ugcs",
                            "UgCS",
                            "Windows · macOS · Linux",
                            Compatibility.DESKTOP_PLANNING,
                            "Профессиональное планирование миссий на компьютере. "
                                    + "Наличие DJI в списке UgCS не означает поддержку "
                                    + "потребительского пульта RC 2.",
                            "https://www.sphengineering.com/flight-planning/ugcs"
                    )
            )
    );

    private Rc2WebToolCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static boolean isAllowed(Entry entry) {
        return entry != null
                && ENTRIES.contains(entry)
                && isAllowedUrl(entry.getUrl());
    }

    static boolean isAllowedUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.US);
            return ALLOWED_HOSTS.contains(host);
        } catch (URISyntaxException error) {
            return false;
        }
    }
}
