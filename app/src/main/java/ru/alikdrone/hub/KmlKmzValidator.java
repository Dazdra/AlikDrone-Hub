package ru.alikdrone.hub;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

/**
 * Fail-closed structural KML/KMZ validator.
 *
 * <p>This class intentionally does not claim route compatibility or flight
 * safety. It only checks container/XML structure while defending against
 * traversal, oversized archives, suspicious compression ratios and XXE.</p>
 */
public final class KmlKmzValidator {
    static final int MAX_ENTRIES = 128;
    static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 32L * 1024L * 1024L;
    static final long MAX_KML_BYTES = 2L * 1024L * 1024L;
    static final long MAX_COMPRESSION_RATIO = 200L;
    static final int MAX_XML_ELEMENTS = 50_000;
    static final int MAX_XML_DEPTH = 256;

    public Result validate(String displayName, InputStream source, long compressedLength) {
        if (source == null) {
            return Result.invalid("Файл не открыт.");
        }
        String normalizedName = displayName == null
                ? ""
                : displayName.toLowerCase(Locale.ROOT);
        try {
            if (normalizedName.endsWith(".kmz")) {
                return validateKmz(source, compressedLength);
            }
            if (normalizedName.endsWith(".kml")) {
                return validateKml(readBounded(source, MAX_KML_BYTES), 1, -1L);
            }
            return Result.invalid("Поддерживаются только файлы .kml и .kmz.");
        } catch (LimitExceededException tooLarge) {
            return Result.invalid(tooLarge.getMessage());
        } catch (Exception invalid) {
            return Result.invalid("Не удалось проверить структуру: " + safeMessage(invalid));
        }
    }

    private Result validateKmz(InputStream source, long compressedLength) throws Exception {
        int entries = 0;
        long totalUncompressed = 0L;
        byte[] primaryKml = null;
        String primaryName = null;
        boolean docKmlFound = false;
        List<String> warnings = new ArrayList<>();

        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new LimitExceededException(
                            "KMZ содержит слишком много элементов (максимум " + MAX_ENTRIES + ")."
                    );
                }
                String name = entry.getName();
                if (!isSafeArchivePath(name)) {
                    return Result.invalid("KMZ содержит небезопасный путь: " + name);
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String lowerName = name.toLowerCase(Locale.ROOT);
                boolean kmlEntry = lowerName.endsWith(".kml");
                byte[] content = kmlEntry
                        ? readBounded(zip, MAX_KML_BYTES)
                        : null;
                long entryBytes = content == null
                        ? consumeBounded(zip, MAX_ENTRY_BYTES)
                        : content.length;
                totalUncompressed += entryBytes;
                if (totalUncompressed > MAX_TOTAL_BYTES) {
                    throw new LimitExceededException(
                            "Распакованный KMZ превышает безопасный лимит 32 МБ."
                    );
                }
                long compressedSize = entry.getCompressedSize();
                if (compressedSize > 0L
                        && entryBytes > compressedSize * MAX_COMPRESSION_RATIO) {
                    return Result.invalid(
                            "KMZ имеет подозрительную степень сжатия и отклонён как zip-bomb."
                    );
                }
                if (kmlEntry) {
                    if (content.length > MAX_KML_BYTES) {
                        throw new LimitExceededException(
                                "KML внутри KMZ превышает безопасный лимит 2 МБ."
                        );
                    }
                    boolean isDoc = "doc.kml".equals(lowerName)
                            || lowerName.endsWith("/doc.kml");
                    if (primaryKml == null || (isDoc && !docKmlFound)) {
                        primaryKml = content;
                        primaryName = name;
                        docKmlFound = isDoc;
                    }
                }
                zip.closeEntry();
            }
        }

        if (compressedLength > 0L
                && totalUncompressed > compressedLength * MAX_COMPRESSION_RATIO) {
            return Result.invalid(
                    "KMZ имеет подозрительную общую степень сжатия и отклонён как zip-bomb."
            );
        }
        if (primaryKml == null) {
            return Result.invalid("В KMZ не найден KML-документ.");
        }
        if (!docKmlFound) {
            warnings.add("В архиве нет стандартного doc.kml; проверен " + primaryName + ".");
        }
        Result kmlResult = validateKml(primaryKml, entries, totalUncompressed);
        if (!kmlResult.valid) {
            return kmlResult;
        }
        List<String> mergedWarnings = new ArrayList<>(warnings);
        mergedWarnings.addAll(kmlResult.warnings);
        return Result.valid(
                "KMZ структурно корректен. Это не проверка совместимости или безопасности полёта.",
                mergedWarnings,
                entries,
                totalUncompressed
        );
    }

    private Result validateKml(byte[] xml, int entries, long uncompressedBytes) throws Exception {
        // Android's Expat/Harmony SAX implementation does not recognize all
        // Xerces/Apache feature URIs. Reject a DTD before constructing the
        // parser so XXE/entity expansion is blocked independently of those
        // optional implementation-specific switches.
        if (containsDoctype(xml)) {
            return Result.invalid("KML содержит запрещённый DOCTYPE.");
        }

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        setOptionalFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setOptionalFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);

        XMLReader reader = factory.newSAXParser().getXMLReader();
        setOptionalFeature(reader, "http://xml.org/sax/features/external-general-entities", false);
        setOptionalFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External entities are disabled.");
        });
        KmlStructureHandler handler = new KmlStructureHandler();
        reader.setContentHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        if (!handler.rootSeen) {
            return Result.invalid("KML не содержит корневого элемента.");
        }
        if (!"kml".equalsIgnoreCase(handler.rootName)) {
            return Result.invalid("Корневой элемент должен называться kml.");
        }

        List<String> warnings = new ArrayList<>();
        if (handler.rootNamespace == null
                || !handler.rootNamespace.contains("opengis.net/kml")) {
            warnings.add("Пространство имён KML нестандартное или отсутствует.");
        }
        if (handler.networkLinkFound) {
            warnings.add("Файл содержит NetworkLink; внешние ресурсы Hub не загружает.");
        }
        return Result.valid(
                "KML структурно корректен. Это не проверка совместимости или безопасности полёта.",
                warnings,
                entries,
                uncompressedBytes < 0L ? xml.length : uncompressedBytes
        );
    }

    private void setOptionalFeature(
            SAXParserFactory factory,
            String feature,
            boolean value
    ) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception | LinkageError unsupported) {
            // The raw DOCTYPE pre-scan and EntityResolver remain the
            // fail-closed controls on Android parsers without this feature.
        }
    }

    private void setOptionalFeature(
            XMLReader reader,
            String feature,
            boolean value
    ) {
        try {
            reader.setFeature(feature, value);
        } catch (SAXException | LinkageError unsupported) {
            // See the factory overload above.
        }
    }

    static boolean containsDoctype(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return false;
        }
        byte[] token = new byte[] {
                '<', '!', 'D', 'O', 'C', 'T', 'Y', 'P', 'E'
        };
        return containsEncodedToken(xml, token, 1, 0)
                || containsEncodedToken(xml, token, 2, 0)
                || containsEncodedToken(xml, token, 2, 1)
                || containsEncodedToken(xml, token, 4, 0)
                || containsEncodedToken(xml, token, 4, 1)
                || containsEncodedToken(xml, token, 4, 2)
                || containsEncodedToken(xml, token, 4, 3);
    }

    private static boolean containsEncodedToken(
            byte[] data,
            byte[] token,
            int stride,
            int byteOffset
    ) {
        int encodedLength = (token.length - 1) * stride + 1;
        for (int start = byteOffset; start + encodedLength <= data.length; start += stride) {
            boolean matches = true;
            for (int index = 0; index < token.length; index++) {
                if (data[start + index * stride] != token[index]) {
                    matches = false;
                    break;
                }
                for (int padding = 1; padding < stride; padding++) {
                    int paddingIndex = start + index * stride + padding;
                    if (paddingIndex < data.length
                            && padding != byteOffset
                            && data[paddingIndex] != 0) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) {
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private byte[] readBounded(InputStream source, long limit)
            throws IOException, LimitExceededException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Validation cancelled");
            }
            total += read;
            if (total > limit) {
                throw new LimitExceededException(
                        "Файл превышает безопасный лимит "
                                + Math.max(1L, limit / (1024L * 1024L))
                                + " МБ."
                );
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private long consumeBounded(InputStream source, long limit)
            throws IOException, LimitExceededException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0L;
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Validation cancelled");
            }
            total += read;
            if (total > limit) {
                throw new LimitExceededException(
                        "Файл превышает безопасный лимит "
                                + Math.max(1L, limit / (1024L * 1024L))
                                + " МБ."
                );
            }
        }
        return total;
    }

    static boolean isSafeArchivePath(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static final class LimitExceededException extends Exception {
        LimitExceededException(String message) {
            super(message);
        }
    }

    private static final class KmlStructureHandler extends DefaultHandler {
        private int elementCount;
        private int depth;
        private boolean rootSeen;
        private String rootName = "";
        private String rootNamespace = "";
        private boolean networkLinkFound;

        @Override
        public void startElement(
                String uri,
                String localName,
                String qualifiedName,
                Attributes attributes
        ) throws SAXException {
            elementCount++;
            depth++;
            if (elementCount > MAX_XML_ELEMENTS) {
                throw new SAXException(
                        "KML содержит слишком много XML-элементов (максимум "
                                + MAX_XML_ELEMENTS + ")."
                );
            }
            if (depth > MAX_XML_DEPTH) {
                throw new SAXException(
                        "KML содержит слишком глубокую XML-структуру (максимум "
                                + MAX_XML_DEPTH + " уровней)."
                );
            }
            String name = localName == null || localName.isEmpty()
                    ? qualifiedName
                    : localName;
            if (!rootSeen) {
                rootSeen = true;
                rootName = name == null ? "" : name;
                rootNamespace = uri == null ? "" : uri;
            }
            if ("NetworkLink".equalsIgnoreCase(name)) {
                networkLinkFound = true;
            }
        }

        @Override
        public void endElement(
                String uri,
                String localName,
                String qualifiedName
        ) {
            depth = Math.max(0, depth - 1);
        }
    }

    public static final class Result {
        public final boolean valid;
        public final String summary;
        public final List<String> warnings;
        public final int archiveEntries;
        public final long uncompressedBytes;

        private Result(
                boolean valid,
                String summary,
                List<String> warnings,
                int archiveEntries,
                long uncompressedBytes
        ) {
            this.valid = valid;
            this.summary = summary;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.archiveEntries = archiveEntries;
            this.uncompressedBytes = Math.max(0L, uncompressedBytes);
        }

        static Result valid(
                String summary,
                List<String> warnings,
                int entries,
                long bytes
        ) {
            return new Result(true, summary, warnings, entries, bytes);
        }

        static Result invalid(String summary) {
            return new Result(false, summary, Collections.<String>emptyList(), 0, 0L);
        }
    }
}
