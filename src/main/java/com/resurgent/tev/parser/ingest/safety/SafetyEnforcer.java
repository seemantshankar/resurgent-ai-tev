package com.resurgent.tev.parser.ingest.safety;

import com.resurgent.tev.parser.config.ParserConfig;
import com.resurgent.tev.parser.ingest.FileType;
import com.resurgent.tev.parser.ingest.IngestRejectionException;
import com.resurgent.tev.parser.ingest.RejectionReason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Pre-parse safety gate for workbook intake.
 *
 * <p>Runs cheap, defensive checks before the expensive normalization pipeline:
 * ZIP-bomb expansion ratios, dimensional ceilings, password protection,
 * embedded OLE/ActiveX payloads, DDE links, and malformed-package guards.
 *
 * <p>All rejections surface as {@link IngestRejectionException} with stable
 * {@link RejectionReason} codes so callers can persist them durably.
 */
public final class SafetyEnforcer {

    private static final int ZIP_MAGIC_LEN = 4;
    private static final byte[] PK0304 = new byte[] {'P', 'K', 0x03, 0x04};
    private static final byte[] PK0506 = new byte[] {'P', 'K', 0x05, 0x06};
    private static final byte[] PK0708 = new byte[] {'P', 'K', 0x07, 0x08};

    /**
     * Verify that {@code input} satisfies the safety policy in {@code config}.
     *
     * @param input    the file to inspect
     * @param fileType the declared file type (CSV inputs pass through untouched)
     * @param config   effective parser configuration
     * @throws IngestRejectionException if any safety limit is exceeded
     * @throws IOException              if an I/O error occurs while reading the file
     */
    public void check(Path input, FileType fileType, ParserConfig config)
            throws IngestRejectionException, IOException {
        if (fileType == FileType.FM_CSV) {
            return;
        }

        long compressedSize = Math.max(1, Files.size(input));

        if (isZipBased(input)) {
            checkExpansionRatio(input, compressedSize, config);
        }

        try (Workbook workbook = openWorkbook(input, config)) {
            checkDimensions(workbook, config);
            checkOleDde(workbook, config);
        }
    }

    private boolean isZipBased(Path input) throws IOException {
        byte[] magic = new byte[ZIP_MAGIC_LEN];
        try (InputStream in = Files.newInputStream(input)) {
            if (in.read(magic) != ZIP_MAGIC_LEN) {
                return false;
            }
        }
        return startsWith(magic, PK0304)
                || startsWith(magic, PK0506)
                || startsWith(magic, PK0708);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void checkExpansionRatio(Path input, long compressedSize, ParserConfig config)
            throws IOException {
        if (config.maxZipExpansionRatio() <= 0) {
            return;
        }

        long allowed = Math.multiplyExact(config.maxZipExpansionRatio(), compressedSize);

        long uncompressed;
        try {
            uncompressed = totalUncompressedSize(input, allowed);
        } catch (ZipException e) {
            throw malformedPackage("not a valid ZIP package", e);
        }

        if (uncompressed > allowed) {
            double ratio = (double) uncompressed / compressedSize;
            throw new IngestRejectionException(
                    RejectionReason.EXPANSION_RATIO_EXCEEDED,
                    config.maxZipExpansionRatio(),
                    ratio,
                    "uncompressed size " + uncompressed + " bytes exceeds expansion ratio limit "
                            + config.maxZipExpansionRatio() + "x against compressed size "
                            + compressedSize + " bytes (ratio=" + ratio + ")");
        }
    }

    private long totalUncompressedSize(Path input, long allowed) throws IOException {
        long total = 0;
        try (ZipFile zf = new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                long size = entry.getSize();
                if (size < 0) {
                    try (InputStream in = zf.getInputStream(entry)) {
                        size = boundedCopySize(in, allowed - total);
                    }
                }
                total = Math.addExact(total, size);
                if (total > allowed) {
                    return total;
                }
            }
        }
        return total;
    }

    /**
     * Reads {@code in} to exhaustion but aborts as soon as the running byte
     * count exceeds {@code remainingAllowance}, so a hostile entry with an
     * unreliable declared size cannot force unbounded decompression.
     */
    private static long boundedCopySize(InputStream in, long remainingAllowance) throws IOException {
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            count += read;
            if (count > remainingAllowance) {
                return count;
            }
        }
        return count;
    }

    private Workbook openWorkbook(Path input, ParserConfig config) throws IOException {
        try {
            return WorkbookFactory.create(input.toFile(), null, true);
        } catch (EncryptedDocumentException e) {
            if (config.rejectPasswordProtected()) {
                throw new IngestRejectionException(
                        RejectionReason.PASSWORD_PROTECTED,
                        config.rejectPasswordProtected(),
                        true,
                        "password-protected workbook rejected before content extraction");
            }
            throw new IOException("encrypted workbook and rejectPasswordProtected is disabled", e);
        } catch (IOException | POIXMLException e) {
            throw malformedPackage("failed to open workbook package", e);
        }
    }

    private void checkDimensions(Workbook workbook, ParserConfig config) {
        int sheetCount = workbook.getNumberOfSheets();
        if (sheetCount > config.maxSheetCount()) {
            throw new IngestRejectionException(
                    RejectionReason.SHEET_COUNT_EXCEEDED,
                    config.maxSheetCount(),
                    sheetCount,
                    "sheet count " + sheetCount + " exceeds configured limit "
                            + config.maxSheetCount());
        }

        long totalCells = 0;
        int maxRowsObserved = 0;
        int maxColumnsObserved = 0;

        for (int i = 0; i < sheetCount; i++) {
            Sheet sheet = workbook.getSheetAt(i);

            int rowCount = rowCount(sheet);
            if (rowCount > maxRowsObserved) {
                maxRowsObserved = rowCount;
            }
            if (rowCount > config.maxRowCount()) {
                throw new IngestRejectionException(
                        RejectionReason.ROW_COUNT_EXCEEDED,
                        config.maxRowCount(),
                        rowCount,
                        "sheet '" + sheet.getSheetName() + "' row count " + rowCount
                                + " exceeds configured limit " + config.maxRowCount());
            }

            int columnCount = columnCount(sheet);
            if (columnCount > maxColumnsObserved) {
                maxColumnsObserved = columnCount;
            }
            if (columnCount > config.maxColumnCount()) {
                throw new IngestRejectionException(
                        RejectionReason.COLUMN_COUNT_EXCEEDED,
                        config.maxColumnCount(),
                        columnCount,
                        "sheet '" + sheet.getSheetName() + "' column count " + columnCount
                                + " exceeds configured limit " + config.maxColumnCount());
            }

            totalCells += physicalCellCount(sheet);
        }

        if (totalCells > config.maxCellCount()) {
            throw new IngestRejectionException(
                    RejectionReason.CELL_COUNT_EXCEEDED,
                    config.maxCellCount(),
                    totalCells,
                    "total occupied cell count " + totalCells + " exceeds configured limit "
                            + config.maxCellCount());
        }
    }

    private static int rowCount(Sheet sheet) {
        int last = sheet.getLastRowNum();
        return last < 0 ? 0 : last + 1;
    }

    private static int columnCount(Sheet sheet) {
        int max = 0;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            short lastCell = row.getLastCellNum();
            if (lastCell > max) {
                max = lastCell;
            }
        }
        return max;
    }

    private static long physicalCellCount(Sheet sheet) {
        long count = 0;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                count += row.getPhysicalNumberOfCells();
            }
        }
        return count;
    }

    private void checkOleDde(Workbook workbook, ParserConfig config) throws IOException {
        if (!config.rejectActiveXOleDde()) {
            return;
        }
        if (workbook instanceof XSSFWorkbook xssf) {
            checkXssfOleDde(xssf);
        } else if (workbook instanceof HSSFWorkbook hssf) {
            checkHssfOleDde(hssf);
        }
    }

    private void checkXssfOleDde(XSSFWorkbook workbook) throws IOException {
        OPCPackage pkg = workbook.getPackage();
        if (pkg == null) {
            return;
        }
        try {
            for (PackagePart part : pkg.getParts()) {
                String name = part.getPartName().getName();
                String contentType = part.getContentType();

                if (name.contains("/embeddings/")
                        || name.contains("/activeX/")
                        || (contentType != null && contentType.contains("oleObject"))) {
                    throw oleObjectRejected("embedded OLE/ActiveX object detected in " + name);
                }

                if (name.startsWith("/xl/externalLinks/") && isDdeLinkPart(part)) {
                    throw ddeLinkRejected("DDE link detected in " + name);
                }
            }
        } catch (InvalidFormatException e) {
            throw malformedPackage("failed to read OOXML package parts", e);
        }
    }

    private static boolean isDdeLinkPart(PackagePart part) throws IOException {
        try (InputStream in = part.getInputStream()) {
            byte[] prefix = in.readNBytes(4096);
            String text = new String(prefix, java.nio.charset.StandardCharsets.UTF_8);
            return text.contains("<ddeLink") || text.contains(":ddeLink");
        }
    }

    private void checkHssfOleDde(HSSFWorkbook workbook) {
        if (!workbook.getAllEmbeddedObjects().isEmpty()) {
            throw oleObjectRejected("embedded OLE object detected in legacy .xls workbook");
        }
    }

    private static IngestRejectionException oleObjectRejected(String explanation) {
        return new IngestRejectionException(
                RejectionReason.OLE_OBJECT_REJECTED,
                true,
                true,
                explanation);
    }

    private static IngestRejectionException ddeLinkRejected(String explanation) {
        return new IngestRejectionException(
                RejectionReason.DDE_LINK_REJECTED,
                true,
                true,
                explanation);
    }

    private static IngestRejectionException malformedPackage(String explanation, Throwable cause) {
        IngestRejectionException rejection = new IngestRejectionException(
                RejectionReason.MALFORMED_PACKAGE,
                null,
                cause != null ? cause.getClass().getName() : null,
                explanation);
        rejection.initCause(cause);
        return rejection;
    }
}
