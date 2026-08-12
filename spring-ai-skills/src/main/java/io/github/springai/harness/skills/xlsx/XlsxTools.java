package io.github.springai.harness.skills.xlsx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI Tools providing Excel (XLSX/XLSM/CSV) creation, editing, preview, formula evaluation, and format conversion capabilities.
 * All file operations use the local filesystem ({@link Path}/{@link Files}).
 * For OSS files, materialize them first via {@code OssLocalFileTools#downloadOssFileToLocal}.
 * Designed with OOM prevention and pure Java Apache POI.
 *
 * @author ichaobuster
 */
@Slf4j
public class XlsxTools {

    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
    private static final int MAX_PREVIEW_ROWS_DEFAULT = 10;
    private static final int MAX_PREVIEW_ROWS_CAP = 50;
    private static final int MAX_READ_ROWS_CAP = 2000;
    private static final int SXSSF_THRESHOLD_ROWS = 10000;
    private static final int MAX_LOCATIONS_PER_ERROR = 100;

    private static final List<String> EXCEL_ERRORS = List.of(
            "#VALUE!", "#DIV/0!", "#REF!", "#NAME?", "#NULL!", "#NUM!", "#N/A"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public XlsxTools() {
    }

    @Tool(name = "readXlsxPreview", description = "Generate a quick markdown preview of an Excel spreadsheet (.xlsx, .xlsm), showing sheet names, dimensions, and top rows of data.")
    public String readXlsxPreview(
            @ToolParam(description = "Local filesystem path to the Excel file (absolute or relative)") String filePath,
            @ToolParam(description = "Maximum rows per sheet to preview (default 10, max 50)", required = false) Integer maxRowsPerSheet) {
        validateFileExistsAndSize(filePath);
        int maxRows = Math.min(maxRowsPerSheet != null && maxRowsPerSheet > 0 ? maxRowsPerSheet : MAX_PREVIEW_ROWS_DEFAULT, MAX_PREVIEW_ROWS_CAP);

        StringBuilder sb = new StringBuilder();
        sb.append("# Excel Preview: ").append(filePath).append("\n\n");

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             Workbook workbook = WorkbookFactory.create(is)) {
            int numberOfSheets = workbook.getNumberOfSheets();
            sb.append("Total Sheets: ").append(numberOfSheets).append("\n\n");

            for (int i = 0; i < numberOfSheets; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                int lastRowNum = sheet.getLastRowNum();
                int totalRows = lastRowNum >= 0 ? lastRowNum + 1 : 0;

                sb.append("## Sheet: ").append(sheet.getSheetName()).append("\n");
                sb.append("Total Rows: ").append(totalRows).append("\n\n");

                if (totalRows == 0) {
                    sb.append("*(Empty sheet)*\n\n");
                    continue;
                }

                int previewRows = Math.min(totalRows, maxRows);
                int maxCols = 0;
                for (int r = 0; r < previewRows; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null && row.getLastCellNum() > maxCols) {
                        maxCols = row.getLastCellNum();
                    }
                }

                if (maxCols == 0) {
                    sb.append("*(No cell content)*\n\n");
                    continue;
                }

                // Header / First row as table header
                sb.append("|");
                for (int c = 0; c < maxCols; c++) {
                    sb.append(" ").append(CellReference.convertNumToColString(c)).append(" |");
                }
                sb.append("\n|");
                for (int c = 0; c < maxCols; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");

                // Data rows
                for (int r = 0; r < previewRows; r++) {
                    Row row = sheet.getRow(r);
                    sb.append("|");
                    for (int c = 0; c < maxCols; c++) {
                        Cell cell = row != null ? row.getCell(c) : null;
                        String val = formatCellValue(cell, false);
                        sb.append(" ").append(val.replace("|", "\\|").replace("\n", " ")).append(" |");
                    }
                    sb.append("\n");
                }
                if (totalRows > previewRows) {
                    sb.append("\n*(... ").append(totalRows - previewRows).append(" more rows hidden)*\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.error("Failed to preview xlsx file {}", filePath, e);
            return "Error reading preview: " + e.getMessage();
        }

        return sb.toString();
    }

    @Tool(name = "readXlsxSheet", description = "Read cell values or formulas from a specific sheet in an Excel file with pagination.")
    public String readXlsxSheet(
            @ToolParam(description = "Local filesystem path to the Excel file (absolute or relative)") String filePath,
            @ToolParam(description = "Sheet name to read") String sheetName,
            @ToolParam(description = "Start row (1-indexed, default 1)", required = false) Integer startRow,
            @ToolParam(description = "End row (1-indexed, default 100, max range 2000)", required = false) Integer endRow,
            @ToolParam(description = "Set true to inspect raw formula strings instead of cached values", required = false) Boolean showFormulas) {
        validateFileExistsAndSize(filePath);
        int start = startRow != null && startRow > 0 ? startRow : 1;
        int end = endRow != null && endRow >= start ? endRow : start + 99;
        if (end - start + 1 > MAX_READ_ROWS_CAP) {
            end = start + MAX_READ_ROWS_CAP - 1;
        }

        boolean formulasMode = Boolean.TRUE.equals(showFormulas);

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                return "Error: Sheet '" + sheetName + "' not found in " + filePath;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Sheet: ").append(sheetName).append(" (Rows ").append(start).append(" to ").append(end).append(")\n\n");

            int startZeroIdx = start - 1;
            int endZeroIdx = end - 1;

            for (int r = startZeroIdx; r <= endZeroIdx && r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                short lastCellNum = row.getLastCellNum();
                if (lastCellNum <= 0) {
                    continue;
                }
                List<String> cellEntries = new ArrayList<>();
                for (int c = 0; c < lastCellNum; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() != CellType.BLANK) {
                        String cellRef = new CellReference(r, c).formatAsString();
                        String val = formatCellValue(cell, formulasMode);
                        cellEntries.add(cellRef + ": " + val);
                    }
                }
                if (!cellEntries.isEmpty()) {
                    sb.append("Row ").append(r + 1).append(" -> ").append(String.join(" | ", cellEntries)).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to read xlsx sheet {} from {}", sheetName, filePath, e);
            return "Error reading sheet: " + e.getMessage();
        }
    }

    @Tool(name = "createXlsx", description = "Create a new Excel file (.xlsx) with custom sheets, values, formulas, comments, and cell styles.")
    public String createXlsx(
            @ToolParam(description = "Local filesystem path to save the new .xlsx file (absolute or relative)") String filePath,
            @ToolParam(description = "List of SheetSpec objects specifying sheets, cells, formulas, and formatting") List<SheetSpec> sheets) {
        try {
            if (sheets == null || sheets.isEmpty()) {
                return "Error: sheets parameter cannot be empty.";
            }

            int totalRows = 0;
            for (SheetSpec spec : sheets) {
                if (spec.cells() != null) {
                    totalRows += spec.cells().size();
                }
            }

            boolean useStreaming = totalRows > SXSSF_THRESHOLD_ROWS;
            Workbook workbook = useStreaming ? new SXSSFWorkbook(100) : new XSSFWorkbook();

            Map<String, CellStyle> styleCache = new HashMap<>();

            for (SheetSpec sheetSpec : sheets) {
                String sheetName = sheetSpec.sheetName() != null ? sheetSpec.sheetName() : "Sheet1";
                Sheet sheet = workbook.createSheet(sheetName);

                // Freeze panes if configured
                if (sheetSpec.freezeCol() != null || sheetSpec.freezeRow() != null) {
                    int fc = sheetSpec.freezeCol() != null ? sheetSpec.freezeCol() : 0;
                    int fr = sheetSpec.freezeRow() != null ? sheetSpec.freezeRow() : 0;
                    sheet.createFreezePane(fc, fr);
                }

                // Column widths
                if (sheetSpec.columnWidths() != null) {
                    for (Map.Entry<String, Integer> entry : sheetSpec.columnWidths().entrySet()) {
                        int colIdx = CellReference.convertColStringToIndex(entry.getKey());
                        if (colIdx >= 0) {
                            sheet.setColumnWidth(colIdx, entry.getValue() * 256);
                        }
                    }
                }

                // Populate cells
                if (sheetSpec.cells() != null) {
                    Drawing<?> drawing = null;
                    for (CellSpec cellSpec : sheetSpec.cells()) {
                        if (cellSpec.cellRef() == null) {
                            continue;
                        }
                        CellReference ref = new CellReference(cellSpec.cellRef());
                        Row row = sheet.getRow(ref.getRow());
                        if (row == null) {
                            row = sheet.createRow(ref.getRow());
                        }
                        Cell cell = row.createCell(ref.getCol());

                        applyCellSpec(cell, cellSpec, workbook, styleCache);

                        if (cellSpec.comment() != null && !cellSpec.comment().isBlank()) {
                            if (drawing == null) {
                                drawing = sheet.createDrawingPatriarch();
                            }
                            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                            anchor.setCol1(cell.getColumnIndex());
                            anchor.setCol2(cell.getColumnIndex() + 2);
                            anchor.setRow1(cell.getRowIndex());
                            anchor.setRow2(cell.getRowIndex() + 3);
                            Comment comment = drawing.createCellComment(anchor);
                            comment.setString(new XSSFRichTextString(cellSpec.comment()));
                            cell.setCellComment(comment);
                        }
                    }
                }
            }

            saveWorkbookToFile(filePath, workbook);

            if (workbook instanceof SXSSFWorkbook sxssf) {
                sxssf.dispose();
            }
            workbook.close();

            return "Successfully created XLSX file: " + filePath;

        } catch (Exception e) {
            log.error("Failed to create xlsx file {}", filePath, e);
            return "Error creating XLSX: " + e.getMessage();
        }
    }

    @Tool(name = "editXlsxCells", description = "Edit existing cell values, formulas, or formatting in an Excel file without destroying untouched cells.")
    public String editXlsxCells(
            @ToolParam(description = "Local filesystem path to an existing .xlsx file (absolute or relative)") String filePath,
            @ToolParam(description = "List of SheetSpec objects specifying cell edits for each sheet") List<SheetSpec> sheets) {
        validateFileExistsAndSize(filePath);

        try {
            if (sheets == null || sheets.isEmpty()) {
                return "Error: sheets parameter cannot be empty.";
            }

            try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
                 Workbook workbook = new XSSFWorkbook(is)) {

                Map<String, CellStyle> styleCache = new HashMap<>();

                for (SheetSpec sheetSpec : sheets) {
                    String sheetName = sheetSpec.sheetName();
                    Sheet sheet = sheetName != null ? workbook.getSheet(sheetName) : workbook.getSheetAt(0);
                    if (sheet == null) {
                        sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet1");
                    }

                    if (sheetSpec.cells() != null) {
                        Drawing<?> drawing = null;
                        for (CellSpec cellSpec : sheetSpec.cells()) {
                            if (cellSpec.cellRef() == null) {
                                continue;
                            }
                            CellReference ref = new CellReference(cellSpec.cellRef());
                            Row row = sheet.getRow(ref.getRow());
                            if (row == null) {
                                row = sheet.createRow(ref.getRow());
                            }
                            Cell cell = row.getCell(ref.getCol());
                            if (cell == null) {
                                cell = row.createCell(ref.getCol());
                            }

                            applyCellSpec(cell, cellSpec, workbook, styleCache);

                            if (cellSpec.comment() != null) {
                                if (drawing == null) {
                                    drawing = sheet.createDrawingPatriarch();
                                }
                                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                                anchor.setCol1(cell.getColumnIndex());
                                anchor.setCol2(cell.getColumnIndex() + 2);
                                anchor.setRow1(cell.getRowIndex());
                                anchor.setRow2(cell.getRowIndex() + 3);
                                Comment comment = drawing.createCellComment(anchor);
                                comment.setString(new XSSFRichTextString(cellSpec.comment()));
                                cell.setCellComment(comment);
                            }
                        }
                    }
                }

                saveWorkbookToFile(filePath, workbook);
            }

            return "Successfully edited XLSX file: " + filePath;

        } catch (Exception e) {
            log.error("Failed to edit xlsx cells in {}", filePath, e);
            return "Error editing XLSX: " + e.getMessage();
        }
    }

    @Tool(name = "evaluateXlsxFormulas", description = "Recalculate all formulas in an Excel workbook using POI FormulaEvaluator, update cached results without replacing formulas, and detect formula errors.")
    public String evaluateXlsxFormulas(
            @ToolParam(description = "Local filesystem path to the .xlsx file (absolute or relative)") String filePath,
            @ToolParam(description = "Whether to save the workbook with updated cached formula results back to storage while keeping formulas intact (default true)", required = false) Boolean writeBack) {
        validateFileExistsAndSize(filePath);
        boolean saveInPlace = writeBack == null || Boolean.TRUE.equals(writeBack);

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             Workbook workbook = new XSSFWorkbook(is)) {

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();

            int totalFormulas = 0;
            int totalErrors = 0;
            Map<String, List<String>> errorLocationsMap = new HashMap<>();
            for (String err : EXCEL_ERRORS) {
                errorLocationsMap.put(err, new ArrayList<>());
            }

            // evaluateAll() updates cached formula results while keeping cells as FORMULA.
            // Do NOT call evaluateInCell(): it replaces formulas with literal values.
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.FORMULA) {
                            totalFormulas++;
                        }

                        // Check for error values from cached formula results / string cells
                        String cellCoord = sheetName + "!" + cell.getAddress().formatAsString();
                        CellType targetType = cell.getCellType();
                        if (targetType == CellType.FORMULA) {
                            targetType = cell.getCachedFormulaResultType();
                        }

                        if (targetType == CellType.ERROR) {
                            byte errCode = cell.getErrorCellValue();
                            String errStr = FormulaErrorName(errCode);
                            errorLocationsMap.computeIfAbsent(errStr, k -> new ArrayList<>()).add(cellCoord);
                            totalErrors++;
                        } else if (targetType == CellType.STRING) {
                            String strVal = cell.getStringCellValue();
                            for (String errStr : EXCEL_ERRORS) {
                                if (strVal != null && strVal.contains(errStr)) {
                                    errorLocationsMap.get(errStr).add(cellCoord);
                                    totalErrors++;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (saveInPlace) {
                saveWorkbookToFile(filePath, workbook);
            }

            Map<String, FormulaEvaluationResult.ErrorGroup> summary = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : errorLocationsMap.entrySet()) {
                List<String> locs = entry.getValue();
                if (!locs.isEmpty()) {
                    int count = locs.size();
                    List<String> shown = locs.subList(0, Math.min(count, MAX_LOCATIONS_PER_ERROR));
                    Integer truncated = count > MAX_LOCATIONS_PER_ERROR ? count - MAX_LOCATIONS_PER_ERROR : null;
                    summary.put(entry.getKey(), new FormulaEvaluationResult.ErrorGroup(count, shown, truncated));
                }
            }

            FormulaEvaluationResult result = FormulaEvaluationResult.success(totalFormulas, totalErrors, summary);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);

        } catch (Exception e) {
            log.error("Failed to evaluate formulas in {}", filePath, e);
            try {
                return objectMapper.writeValueAsString(FormulaEvaluationResult.error("Evaluation error: " + e.getMessage()));
            } catch (Exception ex) {
                return "{\"error\": \"" + e.getMessage() + "\"}";
            }
        }
    }

    @Tool(name = "convertCsvToXlsx", description = "Convert a CSV or TSV file to Excel format (.xlsx) using streaming SXSSF write for OOM safety.")
    public String convertCsvToXlsx(
            @ToolParam(description = "Local filesystem path to the input CSV/TSV file (absolute or relative)") String csvFilePath,
            @ToolParam(description = "Local filesystem path to save the output .xlsx file (absolute or relative)") String xlsxFilePath,
            @ToolParam(description = "Field delimiter character (e.g. ',' or '\\t')", required = false) String delimiter,
            @ToolParam(description = "Sheet name for the converted output", required = false) String sheetName) {
        validateFileExistsAndSize(csvFilePath);
        String sepStr = delimiter != null && !delimiter.isEmpty() ? delimiter : ",";
        char sep = sepStr.equals("\\t") || sepStr.equals("\t") ? '\t' : sepStr.charAt(0);
        String sName = sheetName != null && !sheetName.isBlank() ? sheetName : "Data";

        try (InputStream is = Files.newInputStream(resolveLocalPath(csvFilePath));
             CSVReader csvReader = new CSVReaderBuilder(new InputStreamReader(is, StandardCharsets.UTF_8))
                     .withCSVParser(new CSVParserBuilder().withSeparator(sep).build())
                     .build();
             SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {

            Sheet sheet = workbook.createSheet(sName);
            String[] nextLine;
            int rowIndex = 0;

            while ((nextLine = csvReader.readNext()) != null) {
                Row row = sheet.createRow(rowIndex++);
                for (int colIndex = 0; colIndex < nextLine.length; colIndex++) {
                    Cell cell = row.createCell(colIndex);
                    String raw = nextLine[colIndex];
                    tryParseAndSetCell(cell, raw);
                }
            }

            saveWorkbookToFile(xlsxFilePath, workbook);
            workbook.dispose();

            return "Successfully converted CSV to XLSX: " + xlsxFilePath + " (" + rowIndex + " rows)";

        } catch (Exception e) {
            log.error("Failed to convert CSV {} to XLSX", csvFilePath, e);
            return "Error converting CSV to XLSX: " + e.getMessage();
        }
    }

    // Helper methods

    private Path resolveLocalPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath must not be blank");
        }
        return Path.of(filePath).toAbsolutePath().normalize();
    }

    private void validateFileExistsAndSize(String filePath) {
        Path path = resolveLocalPath(filePath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File does not exist on local filesystem: " + path);
        }
        final long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not determine file size for: " + path, e);
        }
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("File size exceeds 50MB safety limit: " + path);
        }
    }

    private void saveWorkbookToFile(String filePath, Workbook workbook) throws IOException {
        Path path = resolveLocalPath(filePath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            workbook.write(os);
        }
    }

    private String formatCellValue(Cell cell, boolean showFormulas) {
        if (cell == null) {
            return "";
        }
        if (showFormulas && cell.getCellType() == CellType.FORMULA) {
            return "=" + cell.getCellFormula();
        }

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }

        switch (type) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == (long) num) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case ERROR:
                return FormulaErrorName(cell.getErrorCellValue());
            case BLANK:
            default:
                return "";
        }
    }

    private void applyCellSpec(Cell cell, CellSpec cellSpec, Workbook workbook, Map<String, CellStyle> styleCache) {
        if (cellSpec.formula() != null && !cellSpec.formula().isBlank()) {
            String formulaStr = cellSpec.formula().trim();
            if (formulaStr.startsWith("=")) {
                formulaStr = formulaStr.substring(1);
            }
            cell.setCellFormula(formulaStr);
        } else if (cellSpec.value() != null) {
            Object val = cellSpec.value();
            if (val instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (val instanceof Boolean b) {
                cell.setCellValue(b);
            } else {
                cell.setCellValue(val.toString());
            }
        }

        if (cellSpec.style() != null) {
            CellStyle style = getOrCreateStyle(workbook, cellSpec.style(), styleCache);
            cell.setCellStyle(style);
        }
    }

    private CellStyle getOrCreateStyle(Workbook workbook, CellStyleSpec spec, Map<String, CellStyle> cache) {
        String cacheKey = spec.toString();
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        CellStyle style = workbook.createCellStyle();

        // Font
        if (spec.fontName() != null || spec.fontSize() != null || spec.bold() != null || spec.italic() != null || spec.fontColorRgb() != null) {
            Font font = workbook.createFont();
            if (spec.fontName() != null) {
                font.setFontName(spec.fontName());
            }
            if (spec.fontSize() != null) {
                font.setFontHeightInPoints(spec.fontSize());
            }
            if (Boolean.TRUE.equals(spec.bold())) {
                font.setBold(true);
            }
            if (Boolean.TRUE.equals(spec.italic())) {
                font.setItalic(true);
            }
            if (spec.fontColorRgb() != null && !spec.fontColorRgb().isBlank()) {
                XSSFColor fontColor = parseColor(spec.fontColorRgb());
                if (fontColor != null && font instanceof XSSFFont xssfFont) {
                    xssfFont.setColor(fontColor);
                }
            }
            style.setFont(font);
        }

        // Fill Color
        if (spec.fillColorRgb() != null && !spec.fillColorRgb().isBlank() && style instanceof XSSFCellStyle xssfStyle) {
            XSSFColor color = parseColor(spec.fillColorRgb());
            if (color != null) {
                xssfStyle.setFillForegroundColor(color);
                xssfStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
        }

        // Data Format
        if (spec.dataFormat() != null && !spec.dataFormat().isBlank()) {
            DataFormat df = workbook.createDataFormat();
            style.setDataFormat(df.getFormat(spec.dataFormat()));
        }

        // Alignment
        if (spec.alignment() != null) {
            try {
                style.setAlignment(HorizontalAlignment.valueOf(spec.alignment().toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        if (spec.verticalAlignment() != null) {
            try {
                style.setVerticalAlignment(VerticalAlignment.valueOf(spec.verticalAlignment().toUpperCase()));
            } catch (Exception ignored) {
            }
        }

        cache.put(cacheKey, style);
        return style;
    }

    private XSSFColor parseColor(String rgbStr) {
        try {
            String[] parts = rgbStr.split(",");
            if (parts.length == 3) {
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
            }
            if (rgbStr.startsWith("#")) {
                java.awt.Color color = java.awt.Color.decode(rgbStr);
                return new XSSFColor(color, null);
            }
        } catch (Exception e) {
            log.debug("Failed to parse color {}", rgbStr, e);
        }
        return null;
    }

    private void tryParseAndSetCell(Cell cell, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.matches("-?\\d+")) {
                cell.setCellValue(Long.parseLong(trimmed));
                return;
            }
            if (trimmed.matches("-?\\d+\\.\\d+")) {
                cell.setCellValue(Double.parseDouble(trimmed));
                return;
            }
        } catch (NumberFormatException ignored) {
        }

        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            cell.setCellValue(Boolean.parseBoolean(trimmed));
            return;
        }

        cell.setCellValue(raw);
    }

    private String FormulaErrorName(byte errorCode) {
        return switch (errorCode) {
            case 0x00 -> "#NULL!";
            case 0x07 -> "#DIV/0!";
            case 0x0F -> "#VALUE!";
            case 0x17 -> "#REF!";
            case 0x1D -> "#NAME?";
            case 0x24 -> "#NUM!";
            case 0x2A -> "#N/A";
            default -> "#ERROR:" + errorCode;
        };
    }
}
