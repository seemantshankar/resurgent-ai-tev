package com.resurgent.tev.parser.redact;

import com.resurgent.tev.parser.db.WorkspaceDatabase;
import com.resurgent.tev.parser.db.WorkspaceRepository;
import com.resurgent.tev.parser.ingest.CellNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Application service behind {@code tev-parse redact}: opens the original
 * client workbook, replaces numeric literals with shape-preserving dummies, and
 * writes a redacted copy.
 */
public final class RedactService {

    public RedactSummary redact(Path input, long mandateId, Path dbPath, String sheetName,
            Path outputDir) throws IOException, SQLException, RedactException {
        SheetSelection selection = workbook -> {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new RedactException("sheet not found: " + sheetName);
            }
            return List.of(sheet);
        };
        return redactSelectedSheets(input, mandateId, dbPath, outputDir, selection, sheetName);
    }

    public RedactSummary redactAllSheets(Path input, long mandateId, Path dbPath, Path outputDir)
            throws IOException, SQLException, RedactException {
        SheetSelection selection = workbook -> {
            List<Sheet> sheets = new ArrayList<>(workbook.getNumberOfSheets());
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheets.add(workbook.getSheetAt(i));
            }
            return sheets;
        };
        return redactSelectedSheets(input, mandateId, dbPath, outputDir, selection, null);
    }

    @FunctionalInterface
    private interface SheetSelection {
        List<Sheet> select(Workbook workbook) throws RedactException;
    }

    private RedactSummary redactSelectedSheets(Path input, long mandateId, Path dbPath,
            Path outputDir, SheetSelection selection, String sheetLabel)
            throws IOException, SQLException, RedactException {
        if (!Files.isRegularFile(input)) {
            throw new IOException("input file not found: " + input);
        }
        String fileName = input.getFileName().toString();
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new RedactException("redact v1 supports .xlsx and .xls only: " + fileName);
        }

        String fileHash = sha256(input);
        try (WorkspaceDatabase db = WorkspaceDatabase.open(dbPath)) {
            WorkspaceRepository repo = new WorkspaceRepository(db.connection());
            if (repo.findSourceFileId(mandateId, fileHash) == null) {
                throw new RedactException(
                        "file has not been ingested for mandate " + mandateId
                                + "; run tev-parse ingest first");
            }
        }

        Files.createDirectories(outputDir);
        Path outputPath = outputDir.resolve(redactedFileName(fileName));

        List<RedactedCell> redactions = new ArrayList<>();
        int sheetsProcessed;
        try (InputStream in = Files.newInputStream(input);
                Workbook workbook = WorkbookFactory.create(in)) {
            List<Sheet> sheets = selection.select(workbook);
            sheetsProcessed = sheets.size();
            for (Sheet sheet : sheets) {
                redactions.addAll(redactSheet(sheet));
            }
            for (Sheet sheet : sheets) {
                recalculateFormulaCaches(sheet, workbook);
            }
            workbook.setForceFormulaRecalculation(true);
            try (OutputStream out = Files.newOutputStream(outputPath)) {
                workbook.write(out);
            }
        }

        return new RedactSummary(fileName, sheetLabel, outputPath, redactions.size(),
                sheetsProcessed, redactions);
    }

    private static List<RedactedCell> redactSheet(Sheet sheet) {
        Set<String> mergedParticipants = mergedParticipantCoords(sheet);
        List<RedactedCell> redactions = new ArrayList<>();
        String sheetName = sheet.getSheetName();
        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell == null) {
                    continue;
                }
                String coord = CellReference.convertNumToColString(cell.getColumnIndex())
                        + (cell.getRowIndex() + 1);
                if (mergedParticipants.contains(coord)) {
                    continue;
                }
                redactCell(cell, coord, sheetName).ifPresent(redactions::add);
            }
        }
        return redactions;
    }

    private static Optional<RedactedCell> redactCell(Cell cell, String coord, String sheetName) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            return Optional.empty();
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return Optional.empty();
            }
            double original = cell.getNumericCellValue();
            if (original == 0.0d) {
                return Optional.empty();
            }
            double dummy = DummyValueMapper.dummyNumeric(original, coord);
            cell.setCellValue(dummy);
            return Optional.of(new RedactedCell(
                    sheetName, coord, formatNumeric(original), formatNumeric(dummy), "numeric"));
        }
        if (type == CellType.STRING) {
            String text = cell.getStringCellValue();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            BigDecimal parsed = CellNormalizer.coerceNumericText(text.trim());
            if (parsed == null || parsed.signum() == 0) {
                return Optional.empty();
            }
            String dummy = DummyValueMapper.dummyAmountText(text, coord);
            cell.setCellValue(dummy);
            return Optional.of(new RedactedCell(sheetName, coord, text, dummy, "amount_text"));
        }
        return Optional.empty();
    }

    private static String formatNumeric(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Recomputes cached formula results on the redacted tab after literal
     * replacement so cells like {@code =D18} display the dummy in {@code D18}.
     * Scoped per sheet so broken external links elsewhere in the workbook do not
     * abort the run.
     */
    private static void recalculateFormulaCaches(Sheet sheet, Workbook workbook) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell == null || cell.getCellType() != CellType.FORMULA) {
                    continue;
                }
                try {
                    evaluator.evaluateFormulaCell(cell);
                } catch (RuntimeException ignored) {
                    // External refs or unsupported functions keep their prior cache.
                }
            }
        }
    }

    private static Set<String> mergedParticipantCoords(Sheet sheet) {
        Set<String> participants = new HashSet<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            for (int row = region.getFirstRow(); row <= region.getLastRow(); row++) {
                for (int col = region.getFirstColumn(); col <= region.getLastColumn(); col++) {
                    if (row == region.getFirstRow() && col == region.getFirstColumn()) {
                        continue;
                    }
                    participants.add(CellReference.convertNumToColString(col) + (row + 1));
                }
            }
        }
        return participants;
    }

    private static String redactedFileName(String fileName) {
        if (fileName.toLowerCase().endsWith(".xls") && !fileName.toLowerCase().endsWith(".xlsx")) {
            String stem = fileName.replaceFirst("(?i)\\.xls$", "");
            return stem + "-redacted.xls";
        }
        String stem = fileName.replaceFirst("(?i)\\.xlsx$", "");
        return stem + "-redacted.xlsx";
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
