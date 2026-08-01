package ru.alikdrone.hub;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class KmlKmzValidatorTest {
    private final KmlKmzValidator validator = new KmlKmzValidator();

    @Test
    public void acceptsStructuralKmlButDoesNotClaimFlightSafety() {
        KmlKmzValidator.Result result = validator.validate(
                "route.kml",
                bytes("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document/></kml>"),
                64L
        );

        assertTrue(result.valid);
        assertTrue(result.summary.contains("структурно"));
        assertTrue(result.summary.contains("не проверка"));
    }

    @Test
    public void rejectsDoctypeAndExternalEntity() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE kml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><name>&xxe;</name></kml>";

        KmlKmzValidator.Result result = validator.validate(
                "malicious.kml",
                bytes(xxe),
                xxe.length()
        );

        assertFalse(result.valid);
    }

    @Test
    public void rejectsUtf16DoctypeBeforePlatformParserFeaturesAreUsed() {
        String xxe = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
                + "<!DOCTYPE kml [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><name>&xxe;</name></kml>";
        byte[] utf16 = xxe.getBytes(StandardCharsets.UTF_16LE);

        assertTrue(KmlKmzValidator.containsDoctype(utf16));
        KmlKmzValidator.Result result = validator.validate(
                "malicious-utf16.kml",
                new ByteArrayInputStream(utf16),
                utf16.length
        );

        assertFalse(result.valid);
    }

    @Test
    public void rejectsTraversalInsideKmz() throws Exception {
        byte[] archive = zip(
                "../doc.kml",
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\"/>".getBytes(StandardCharsets.UTF_8)
        );

        KmlKmzValidator.Result result = validator.validate(
                "route.kmz",
                new ByteArrayInputStream(archive),
                archive.length
        );

        assertFalse(result.valid);
        assertTrue(result.summary.contains("небезопасный путь"));
    }

    @Test
    public void rejectsSuspiciousCompressionRatio() throws Exception {
        byte[] repetitive = new byte[2 * 1024 * 1024];
        byte[] archive = zip("payload.bin", repetitive);

        KmlKmzValidator.Result result = validator.validate(
                "route.kmz",
                new ByteArrayInputStream(archive),
                archive.length
        );

        assertFalse(result.valid);
        assertTrue(result.summary.contains("zip-bomb"));
    }

    @Test
    public void acceptsKmzContainingDocKml() throws Exception {
        byte[] archive = zip(
                "doc.kml",
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Placemark/></kml>"
                        .getBytes(StandardCharsets.UTF_8)
        );

        KmlKmzValidator.Result result = validator.validate(
                "route.kmz",
                new ByteArrayInputStream(archive),
                archive.length
        );

        assertTrue(result.valid);
    }

    @Test
    public void streamsLargeNonKmlAssetsAndKeepsAccurateByteCount()
            throws Exception {
        byte[] kml = "<kml xmlns=\"http://www.opengis.net/kml/2.2\"/>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[4 * 1024 * 1024];
        new Random(20260731L).nextBytes(image);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(kml);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("files/map.jpg"));
            zip.write(image);
            zip.closeEntry();
        }

        byte[] archive = bytes.toByteArray();
        KmlKmzValidator.Result result = validator.validate(
                "route.kmz",
                new ByteArrayInputStream(archive),
                archive.length
        );

        assertTrue(result.valid);
        assertEquals(2, result.archiveEntries);
        assertEquals(kml.length + image.length, result.uncompressedBytes);
    }

    @Test
    public void pathPolicyRejectsWindowsAndBackslashTraversal() {
        assertFalse(KmlKmzValidator.isSafeArchivePath("C:/temp/doc.kml"));
        assertFalse(KmlKmzValidator.isSafeArchivePath("folder\\..\\doc.kml"));
        assertTrue(KmlKmzValidator.isSafeArchivePath("files/images/preview.png"));
    }

    @Test
    public void rejectsXmlWithExcessiveElementCount() {
        StringBuilder xml = new StringBuilder(
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
        );
        for (int index = 0; index <= KmlKmzValidator.MAX_XML_ELEMENTS; index++) {
            xml.append("<a/>");
        }
        xml.append("</kml>");

        KmlKmzValidator.Result result = validator.validate(
                "many-elements.kml",
                bytes(xml.toString()),
                xml.length()
        );

        assertFalse(result.valid);
        assertTrue(result.summary.contains("слишком много XML-элементов"));
    }

    @Test
    public void rejectsExcessiveXmlDepth() {
        StringBuilder xml = new StringBuilder(
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">"
        );
        for (int index = 0; index <= KmlKmzValidator.MAX_XML_DEPTH; index++) {
            xml.append("<Folder>");
        }
        for (int index = 0; index <= KmlKmzValidator.MAX_XML_DEPTH; index++) {
            xml.append("</Folder>");
        }
        xml.append("</kml>");

        KmlKmzValidator.Result result = validator.validate(
                "too-deep.kml",
                bytes(xml.toString()),
                xml.length()
        );

        assertFalse(result.valid);
        assertTrue(result.summary.contains("глубокую XML-структуру"));
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
