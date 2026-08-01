package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

public final class Rc2SupportReportModelTest {
    @Test
    public void rendersOnlySelectedSections() {
        Rc2SupportReportModel.Snapshot snapshot =
                new Rc2SupportReportModel.Builder("31.07.2026 12:00")
                        .add(
                                Rc2SupportReportModel.Section.DEVICE,
                                "Модель",
                                "DJI RC 2"
                        )
                        .add(
                                Rc2SupportReportModel.Section.NETWORK,
                                "Подключение",
                                "интернет доступен · Wi‑Fi"
                        )
                        .build();

        String report = Rc2SupportReportModel.render(
                snapshot,
                EnumSet.of(Rc2SupportReportModel.Section.DEVICE)
        );

        assertTrue(report.contains("Модель: DJI RC 2"));
        assertFalse(report.contains("интернет доступен"));
    }

    @Test
    public void omitsSensitiveLabelsAndRedactsInlineSecrets() {
        Rc2SupportReportModel.Snapshot snapshot =
                new Rc2SupportReportModel.Builder("31.07.2026 12:00")
                        .add(
                                Rc2SupportReportModel.Section.DEVICE,
                                "Серийный номер",
                                "F7F372E2"
                        )
                        .add(
                                Rc2SupportReportModel.Section.NETWORK,
                                "Локальный IP",
                                "192.168.1.44"
                        )
                        .add(
                                Rc2SupportReportModel.Section.SOFTWARE,
                                "Диагностика",
                                "token=secret123 endpoint=10.0.0.8"
                        )
                        .add(
                                Rc2SupportReportModel.Section.SOFTWARE,
                                "Путь сборки",
                                "C:\\Users\\Owner\\private\\hub.apk"
                        )
                        .build();

        String report = Rc2SupportReportModel.render(
                snapshot,
                Rc2SupportReportModel.defaultSections()
        );

        assertFalse(report.contains("F7F372E2"));
        assertFalse(report.contains("192.168.1.44"));
        assertFalse(report.contains("secret123"));
        assertFalse(report.contains("Owner"));
        assertTrue(report.contains("token=<скрыто>"));
        assertTrue(report.contains("<IP скрыт>"));
    }

    @Test
    public void emptySelectionDoesNotAccidentallyIncludeEverything() {
        Rc2SupportReportModel.Snapshot snapshot =
                new Rc2SupportReportModel.Builder("")
                        .add(
                                Rc2SupportReportModel.Section.DEVICE,
                                "Модель",
                                "DJI RC 2"
                        )
                        .build();

        String report = Rc2SupportReportModel.render(
                snapshot,
                EnumSet.noneOf(Rc2SupportReportModel.Section.class)
        );

        assertTrue(report.contains("Разделы для отчёта не выбраны"));
        assertFalse(report.contains("Модель: DJI RC 2"));

        String fromGenericEmptySet = Rc2SupportReportModel.render(
                snapshot,
                Collections.emptySet()
        );
        assertTrue(fromGenericEmptySet.contains("Разделы для отчёта не выбраны"));
    }

    @Test
    public void wifiCoordinatesPersonalFilesAndLogContentsNeverEnterReport() {
        Rc2SupportReportModel.Snapshot snapshot =
                new Rc2SupportReportModel.Builder("31.07.2026 12:00")
                        .add(
                                Rc2SupportReportModel.Section.NETWORK,
                                "SSID",
                                "OwnerHomeWiFi"
                        )
                        .add(
                                Rc2SupportReportModel.Section.DEVICE,
                                "GPS координаты",
                                "55.7558, 37.6173"
                        )
                        .add(
                                Rc2SupportReportModel.Section.SOFTWARE,
                                "Содержимое лога",
                                "private flight data"
                        )
                        .add(
                                Rc2SupportReportModel.Section.SOFTWARE,
                                "Имя файла",
                                "client-name-flight.txt"
                        )
                        .add(
                                Rc2SupportReportModel.Section.NETWORK,
                                "Диагностика",
                                "adapter=AA:BB:CC:DD:EE:FF password=qwerty"
                        )
                        .build();

        String report = Rc2SupportReportModel.render(
                snapshot,
                Rc2SupportReportModel.defaultSections()
        );

        assertFalse(report.contains("OwnerHomeWiFi"));
        assertFalse(report.contains("55.7558"));
        assertFalse(report.contains("private flight data"));
        assertFalse(report.contains("client-name-flight"));
        assertFalse(report.contains("AA:BB:CC:DD:EE:FF"));
        assertFalse(report.contains("qwerty"));
        assertTrue(report.contains("<MAC скрыт>"));
        assertTrue(report.contains("password=<скрыто>"));
    }

    @Test
    public void bearerTokensAndIpv6AreRedacted() {
        Rc2SupportReportModel.Snapshot snapshot =
                new Rc2SupportReportModel.Builder("31.07.2026 12:00")
                        .add(
                                Rc2SupportReportModel.Section.SOFTWARE,
                                "Диагностика",
                                "Authorization: Bearer eyJhbGciOiJIUzI1Ni.test.signature"
                        )
                        .add(
                                Rc2SupportReportModel.Section.NETWORK,
                                "Состояние",
                                "route via fe80::1234:abcd"
                        )
                        .build();

        String report = Rc2SupportReportModel.render(
                snapshot,
                Rc2SupportReportModel.defaultSections()
        );

        assertFalse(report.contains("eyJhbGci"));
        assertFalse(report.contains("fe80::1234"));
        assertTrue(report.contains("Authorization=<скрыто>"));
        assertTrue(report.contains("<IPv6 скрыт>"));
    }
}
