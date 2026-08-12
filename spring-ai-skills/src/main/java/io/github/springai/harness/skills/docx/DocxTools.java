package io.github.springai.harness.skills.docx;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFComments;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkup;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTParaRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTString;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGridCol;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring AI Tools for Word (.docx) create / read / edit / merge-runs / comments / accept tracked changes.
 * All file operations use the local filesystem ({@link Path}/{@link Files}).
 * For OSS/remote workspace files, materialize them first via {@code OssLocalFileTools#downloadOssFileToLocal}.
 * Pure Java Apache POI implementation with OOM guards.
 *
 * @author ichaobuster
 */
@Slf4j
public class DocxTools {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_PREVIEW_BLOCKS_DEFAULT = 20;
    private static final int MAX_PREVIEW_BLOCKS_CAP = 100;
    private static final int MAX_CONTENT_BLOCKS_CAP = 500;
    private static final int DEFAULT_CONTENT_BLOCKS = 100;

    // A4 in DXA
    private static final long DEFAULT_PAGE_WIDTH_DXA = 11906L;
    private static final long DEFAULT_PAGE_HEIGHT_DXA = 16838L;
    private static final long DEFAULT_MARGIN_DXA = 1440L;

    public DocxTools() {
    }

    @Tool(name = "readDocxPreview", description = "Generate a quick markdown preview of a Word document (.docx): paragraph/table counts, heading sample, and the first N body blocks.")
    public String readDocxPreview(
            @ToolParam(description = "Local filesystem path to the .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "Maximum body blocks to preview (default 20, max 100)", required = false) Integer maxBlocks) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        int limit = Math.min(
                maxBlocks != null && maxBlocks > 0 ? maxBlocks : MAX_PREVIEW_BLOCKS_DEFAULT,
                MAX_PREVIEW_BLOCKS_CAP);

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            List<IBodyElement> elements = doc.getBodyElements();
            int paragraphs = 0;
            int tables = 0;
            List<String> headings = new ArrayList<>();
            for (IBodyElement el : elements) {
                if (el instanceof XWPFParagraph p) {
                    paragraphs++;
                    String style = p.getStyle();
                    if (style != null && style.toLowerCase(Locale.ROOT).startsWith("heading")) {
                        String t = safeText(p.getText());
                        if (!t.isBlank()) {
                            headings.add(style + ": " + truncate(t, 120));
                        }
                    }
                } else if (el instanceof XWPFTable) {
                    tables++;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Word Preview: ").append(filePath).append("\n\n");
            sb.append("- Body blocks: ").append(elements.size()).append("\n");
            sb.append("- Paragraphs: ").append(paragraphs).append("\n");
            sb.append("- Tables: ").append(tables).append("\n");
            if (!headings.isEmpty()) {
                sb.append("\n## Headings (sample)\n");
                int hLimit = Math.min(headings.size(), 20);
                for (int i = 0; i < hLimit; i++) {
                    sb.append("- ").append(headings.get(i)).append("\n");
                }
            }
            sb.append("\n## First ").append(Math.min(limit, elements.size())).append(" body blocks\n\n");
            appendBodyBlocks(sb, elements, 0, limit);
            if (elements.size() > limit) {
                sb.append("\n_… truncated; use readDocxContent for pagination._\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to preview docx {}", filePath, e);
            return "Error previewing DOCX: " + e.getMessage();
        }
    }

    @Tool(name = "readDocxContent", description = "Read paginated body content from a Word document (.docx). Paragraphs include style+text; tables render as markdown.")
    public String readDocxContent(
            @ToolParam(description = "Local filesystem path to the .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "1-based start body block index (default 1)", required = false) Integer startBlock,
            @ToolParam(description = "Maximum body blocks to return (default 100, max 500)", required = false) Integer maxBlocks) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        int start = startBlock != null && startBlock > 0 ? startBlock : 1;
        int limit = Math.min(
                maxBlocks != null && maxBlocks > 0 ? maxBlocks : DEFAULT_CONTENT_BLOCKS,
                MAX_CONTENT_BLOCKS_CAP);
        int fromIndex = start - 1;

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            List<IBodyElement> elements = doc.getBodyElements();
            if (fromIndex >= elements.size()) {
                return "Error: startBlock " + start + " is beyond document length (" + elements.size() + " blocks).";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# Word Content: ").append(filePath).append("\n");
            sb.append("Blocks ").append(start).append("-")
                    .append(Math.min(start + limit - 1, elements.size()))
                    .append(" of ").append(elements.size()).append("\n\n");
            appendBodyBlocks(sb, elements, fromIndex, limit);
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to read docx content {}", filePath, e);
            return "Error reading DOCX: " + e.getMessage();
        }
    }

    @Tool(name = "createDocx", description = "Create a new Word .docx from ordered block specs (paragraphs, tables, page breaks) with optional page setup (default A4).")
    public String createDocx(
            @ToolParam(description = "Local filesystem path to save the new .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "Ordered list of DocxBlockSpec (type=paragraph|table|pageBreak)") List<DocxBlockSpec> blocks,
            @ToolParam(description = "Optional page setup (default A4 portrait, 1-inch margins)", required = false) DocxPageSetupSpec pageSetup) {
        if (blocks == null || blocks.isEmpty()) {
            return "Error: blocks parameter cannot be empty.";
        }
        if (filePath == null || filePath.isBlank()) {
            return "Error: filePath cannot be empty.";
        }

        try (XWPFDocument doc = new XWPFDocument()) {
            applyPageSetup(doc, pageSetup);
            for (DocxBlockSpec block : blocks) {
                if (block == null || block.type() == null) {
                    continue;
                }
                String type = block.type().trim().toLowerCase(Locale.ROOT);
                switch (type) {
                    case "paragraph" -> {
                        if (block.paragraph() == null) {
                            return "Error: paragraph block missing paragraph payload.";
                        }
                        applyParagraph(doc.createParagraph(), block.paragraph());
                    }
                    case "table" -> {
                        if (block.table() == null || block.table().rows() == null || block.table().rows().isEmpty()) {
                            return "Error: table block missing rows.";
                        }
                        createTable(doc, block.table());
                    }
                    case "pagebreak", "page_break", "page-break" -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.createRun().addBreak(BreakType.PAGE);
                    }
                    default -> {
                        return "Error: unsupported block type '" + block.type() + "'. Use paragraph, table, or pageBreak.";
                    }
                }
            }
            saveDocument(filePath, doc);
            return "Successfully created DOCX file: " + filePath + " (" + blocks.size() + " blocks)";
        } catch (Exception e) {
            log.error("Failed to create docx {}", filePath, e);
            return "Error creating DOCX: " + e.getMessage();
        }
    }

    @Tool(name = "replaceDocxText", description = "Find and replace plain text in a Word document body (paragraphs and tables). Prefer mergeDocxRuns first when text may be split across runs.")
    public String replaceDocxText(
            @ToolParam(description = "Local filesystem path to an existing .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "Plain text to find") String find,
            @ToolParam(description = "Replacement text") String replace,
            @ToolParam(description = "Replace all occurrences (default true). If false, only the first match is replaced.", required = false) Boolean replaceAll) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (find == null || find.isEmpty()) {
            return "Error: find cannot be empty.";
        }
        String replacement = replace != null ? replace : "";
        boolean all = replaceAll == null || Boolean.TRUE.equals(replaceAll);

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            int replacements = 0;
            for (XWPFParagraph para : collectParagraphs(doc)) {
                int n = replaceInParagraph(para, find, replacement, all);
                replacements += n;
                if (!all && replacements > 0) {
                    break;
                }
            }
            saveDocument(filePath, doc);
            return "Successfully replaced text in " + filePath + " (" + replacements + " replacement(s), replaceAll=" + all + ")";
        } catch (Exception e) {
            log.error("Failed to replace text in {}", filePath, e);
            return "Error replacing text: " + e.getMessage();
        }
    }

    @Tool(name = "mergeDocxRuns", description = "Merge adjacent identically-formatted runs in the document body so find/replace is reliable. Optionally write to outputPath; otherwise overwrites input.")
    public String mergeDocxRuns(
            @ToolParam(description = "Local filesystem path to an existing .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "Optional local filesystem output path; default overwrites filePath", required = false) String outputPath) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        String out = (outputPath != null && !outputPath.isBlank()) ? outputPath : filePath;

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            int merged = 0;
            for (XWPFParagraph para : collectParagraphs(doc)) {
                merged += mergeRunsInParagraph(para);
            }
            // Strip proofErr markers from body paragraphs
            for (XWPFParagraph para : collectParagraphs(doc)) {
                stripProofErr(para.getCTP());
            }
            saveDocument(out, doc);
            return "Merged " + merged + " runs; wrote " + out;
        } catch (Exception e) {
            log.error("Failed to merge runs in {}", filePath, e);
            return "Error merging runs: " + e.getMessage();
        }
    }

    @Tool(name = "addDocxComment", description = "Add a Word comment via POI. Optional anchorText places range markers on the first matching body text; without anchor, comment exists but is not visibly anchored.")
    public String addDocxComment(
            @ToolParam(description = "Local filesystem path to an existing .docx file (absolute or relative)") String filePath,
            @ToolParam(description = "Comment text") String text,
            @ToolParam(description = "Author name (default 'Codex')", required = false) String author,
            @ToolParam(description = "Author initials (default 'C')", required = false) String initials,
            @ToolParam(description = "Optional plain text to anchor the comment range on (first match)", required = false) String anchorText,
            @ToolParam(description = "Optional local filesystem output path; default overwrites filePath", required = false) String outputPath) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (text == null || text.isBlank()) {
            return "Error: comment text cannot be empty.";
        }
        String out = (outputPath != null && !outputPath.isBlank()) ? outputPath : filePath;
        String authorName = author != null && !author.isBlank() ? author : "Codex";
        String initialsVal = initials != null && !initials.isBlank() ? initials : "C";

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            XWPFComments comments = doc.getDocComments();
            if (comments == null) {
                comments = doc.createComments();
            }
            BigInteger id = nextCommentId(comments);
            XWPFComment comment = comments.createComment(id);
            comment.setAuthor(authorName);
            comment.setInitials(initialsVal);
            comment.setDate(Calendar.getInstance());
            XWPFParagraph cp = comment.createParagraph();
            cp.createRun().setText(text);

            boolean anchored = false;
            if (anchorText != null && !anchorText.isBlank()) {
                // Improve match odds when text is split across runs
                for (XWPFParagraph para : collectParagraphs(doc)) {
                    mergeRunsInParagraph(para);
                }
                for (XWPFParagraph para : collectParagraphs(doc)) {
                    if (anchorCommentOnParagraph(para, id, anchorText)) {
                        anchored = true;
                        break;
                    }
                }
            }

            saveDocument(out, doc);
            StringBuilder sb = new StringBuilder();
            sb.append("Added comment id=").append(id)
                    .append(" author=").append(authorName)
                    .append("; wrote ").append(out);
            if (anchored) {
                sb.append("; anchored to first match of anchorText");
            } else if (anchorText != null && !anchorText.isBlank()) {
                sb.append("; WARNING: anchorText not found — comment is defined but not visibly anchored. ")
                        .append("Add markers in document.xml: commentRangeStart/End id=")
                        .append(id).append(" and commentReference.");
            } else {
                sb.append("; no anchorText provided — comment is defined but not visibly anchored.");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to add comment to {}", filePath, e);
            return "Error adding comment: " + e.getMessage();
        }
    }

    @Tool(name = "acceptDocxTrackedChanges", description = "Accept all tracked insertions and remove tracked deletions in the document body (POI CT-level). Writes to outputPath (or overwrites input if omitted).")
    public String acceptDocxTrackedChanges(
            @ToolParam(description = "Local filesystem path to input .docx with tracked changes (absolute or relative)") String filePath,
            @ToolParam(description = "Local filesystem output path for clean document; default overwrites filePath", required = false) String outputPath) {
        try {
            validateFileExistsAndSize(filePath);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        String out = (outputPath != null && !outputPath.isBlank()) ? outputPath : filePath;

        try (InputStream is = Files.newInputStream(resolveLocalPath(filePath));
             XWPFDocument doc = new XWPFDocument(is)) {
            int changes = 0;
            for (XWPFParagraph para : collectParagraphs(doc)) {
                changes += acceptTrackedInParagraph(para.getCTP());
            }
            saveDocument(out, doc);
            return "Accepted tracked changes (" + changes + " revision node(s) processed); wrote " + out;
        } catch (Exception e) {
            log.error("Failed to accept tracked changes in {}", filePath, e);
            return "Error accepting tracked changes: " + e.getMessage();
        }
    }

    // ---------------- helpers ----------------

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

    private void saveDocument(String filePath, XWPFDocument doc) throws IOException {
        Path path = resolveLocalPath(filePath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream os = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            doc.write(os);
        }
    }

    private void appendBodyBlocks(StringBuilder sb, List<IBodyElement> elements, int fromIndex, int limit) {
        int end = Math.min(elements.size(), fromIndex + limit);
        for (int i = fromIndex; i < end; i++) {
            IBodyElement el = elements.get(i);
            int displayIndex = i + 1;
            if (el instanceof XWPFParagraph p) {
                String style = p.getStyle() != null ? p.getStyle() : "Normal";
                String text = safeText(p.getText());
                boolean pageBreak = false;
                for (XWPFRun run : p.getRuns()) {
                    // Detect page break runs
                    if (run.getCTR() != null && run.getCTR().sizeOfBrArray() > 0) {
                        for (int b = 0; b < run.getCTR().sizeOfBrArray(); b++) {
                            if (run.getCTR().getBrArray(b).getType() != null
                                    && "page".equalsIgnoreCase(String.valueOf(run.getCTR().getBrArray(b).getType()))) {
                                pageBreak = true;
                            }
                        }
                    }
                }
                if (pageBreak && text.isBlank()) {
                    sb.append(displayIndex).append(". [PAGE_BREAK]\n\n");
                } else {
                    sb.append(displayIndex).append(". [P style=").append(style).append("] ")
                            .append(text.isEmpty() ? "*(empty)*" : text).append("\n\n");
                }
            } else if (el instanceof XWPFTable table) {
                sb.append(displayIndex).append(". [TABLE ").append(table.getNumberOfRows()).append(" rows]\n");
                sb.append(tableToMarkdown(table)).append("\n");
            } else {
                sb.append(displayIndex).append(". [").append(el.getElementType()).append("]\n\n");
            }
        }
    }

    private String tableToMarkdown(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "*(empty table)*\n";
        }
        int cols = 0;
        for (XWPFTableRow row : rows) {
            cols = Math.max(cols, row.getTableCells().size());
        }
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            sb.append("|");
            List<XWPFTableCell> cells = row.getTableCells();
            for (int c = 0; c < cols; c++) {
                String cellText = c < cells.size() ? safeText(cells.get(c).getText()).replace("|", "\\|") : "";
                sb.append(" ").append(cellText).append(" |");
            }
            sb.append("\n");
            if (r == 0) {
                sb.append("|");
                for (int c = 0; c < cols; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private List<XWPFParagraph> collectParagraphs(XWPFDocument doc) {
        List<XWPFParagraph> result = new ArrayList<>();
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph p) {
                result.add(p);
            } else if (el instanceof XWPFTable table) {
                collectTableParagraphs(table, result);
            }
        }
        return result;
    }

    private void collectTableParagraphs(XWPFTable table, List<XWPFParagraph> out) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                out.addAll(cell.getParagraphs());
                for (XWPFTable nested : cell.getTables()) {
                    collectTableParagraphs(nested, out);
                }
            }
        }
    }

    private void applyPageSetup(XWPFDocument doc, DocxPageSetupSpec setup) {
        CTBody body = doc.getDocument().getBody();
        CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz pageSz = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        long width = setup != null && setup.widthDxa() != null ? setup.widthDxa().longValue() : DEFAULT_PAGE_WIDTH_DXA;
        long height = setup != null && setup.heightDxa() != null ? setup.heightDxa().longValue() : DEFAULT_PAGE_HEIGHT_DXA;
        boolean landscape = setup != null && Boolean.TRUE.equals(setup.landscape());
        if (landscape) {
            long tmp = width;
            width = height;
            height = tmp;
            pageSz.setOrient(STPageOrientation.LANDSCAPE);
        } else {
            pageSz.setOrient(STPageOrientation.PORTRAIT);
        }
        pageSz.setW(BigInteger.valueOf(width));
        pageSz.setH(BigInteger.valueOf(height));

        CTPageMar mar = sectPr.isSetPgMar() ? sectPr.getPgMar() : sectPr.addNewPgMar();
        long top = setup != null && setup.marginTopDxa() != null ? setup.marginTopDxa() : DEFAULT_MARGIN_DXA;
        long bottom = setup != null && setup.marginBottomDxa() != null ? setup.marginBottomDxa() : DEFAULT_MARGIN_DXA;
        long left = setup != null && setup.marginLeftDxa() != null ? setup.marginLeftDxa() : DEFAULT_MARGIN_DXA;
        long right = setup != null && setup.marginRightDxa() != null ? setup.marginRightDxa() : DEFAULT_MARGIN_DXA;
        mar.setTop(BigInteger.valueOf(top));
        mar.setBottom(BigInteger.valueOf(bottom));
        mar.setLeft(BigInteger.valueOf(left));
        mar.setRight(BigInteger.valueOf(right));
    }

    private void applyParagraph(XWPFParagraph paragraph, DocxParagraphSpec spec) {
        if (spec.style() != null && !spec.style().isBlank()) {
            paragraph.setStyle(normalizeStyle(spec.style()));
        }
        if (spec.alignment() != null && !spec.alignment().isBlank()) {
            try {
                paragraph.setAlignment(ParagraphAlignment.valueOf(spec.alignment().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        List<DocxRunSpec> runs = spec.runs();
        if (runs == null || runs.isEmpty()) {
            return;
        }
        for (DocxRunSpec runSpec : runs) {
            applyRun(paragraph.createRun(), runSpec);
        }
    }

    private String normalizeStyle(String style) {
        String s = style.trim();
        // Accept "Heading 1" / "heading1" → Heading1
        if (s.matches("(?i)heading\\s*\\d+")) {
            return "Heading" + s.replaceAll("(?i)heading\\s*", "");
        }
        return s;
    }

    private void applyRun(XWPFRun run, DocxRunSpec spec) {
        if (spec == null) {
            return;
        }
        if (Boolean.TRUE.equals(spec.bold())) {
            run.setBold(true);
        }
        if (Boolean.TRUE.equals(spec.italic())) {
            run.setItalic(true);
        }
        if (Boolean.TRUE.equals(spec.underline())) {
            run.setUnderline(UnderlinePatterns.SINGLE);
        }
        if (spec.fontName() != null && !spec.fontName().isBlank()) {
            run.setFontFamily(spec.fontName());
        }
        if (spec.fontSize() != null && spec.fontSize() > 0) {
            run.setFontSize(spec.fontSize());
        }
        String colorHex = toHexColor(spec.color());
        if (colorHex != null) {
            run.setColor(colorHex);
        }
        if (spec.breakType() != null) {
            String bt = spec.breakType().trim().toUpperCase(Locale.ROOT);
            if ("PAGE".equals(bt)) {
                run.addBreak(BreakType.PAGE);
            } else if ("TEXT".equals(bt) || "LINE".equals(bt)) {
                run.addBreak(BreakType.TEXT_WRAPPING);
            }
        }
        if (spec.imagePath() != null && !spec.imagePath().isBlank()) {
            embedImage(run, spec);
        }
        if (spec.text() != null) {
            run.setText(spec.text());
        }
    }

    private void embedImage(XWPFRun run, DocxRunSpec spec) {
        String path = spec.imagePath();
        try {
            validateFileExistsAndSize(path);
            int widthPx = spec.imageWidthPx() != null && spec.imageWidthPx() > 0 ? spec.imageWidthPx() : 320;
            int heightPx = spec.imageHeightPx() != null && spec.imageHeightPx() > 0 ? spec.imageHeightPx() : 240;
            PictureType pictureType = guessPictureType(path);
            try (InputStream is = Files.newInputStream(resolveLocalPath(path))) {
                run.addPicture(is, pictureType, path, Units.toEMU(widthPx), Units.toEMU(heightPx));
            }
        } catch (Exception e) {
            log.warn("Failed to embed image {}: {}", path, e.getMessage());
            run.setText("[image missing: " + path + "]");
        }
    }

    private PictureType guessPictureType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return PictureType.PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return PictureType.JPEG;
        }
        if (lower.endsWith(".gif")) {
            return PictureType.GIF;
        }
        if (lower.endsWith(".bmp")) {
            return PictureType.BMP;
        }
        if (lower.endsWith(".emf")) {
            return PictureType.EMF;
        }
        if (lower.endsWith(".wmf")) {
            return PictureType.WMF;
        }
        return PictureType.PNG;
    }

    private void createTable(XWPFDocument doc, DocxTableSpec tableSpec) {
        List<DocxTableRowSpec> rows = tableSpec.rows();
        int rowCount = rows.size();
        int colCount = 0;
        for (DocxTableRowSpec row : rows) {
            if (row != null && row.cells() != null) {
                colCount = Math.max(colCount, row.cells().size());
            }
        }
        if (colCount == 0) {
            colCount = 1;
        }
        XWPFTable table = doc.createTable(rowCount, colCount);
        List<Integer> widths = tableSpec.columnWidthsDxa();
        if (widths != null && !widths.isEmpty()) {
            applyTableWidths(table, widths, colCount);
        }
        for (int r = 0; r < rowCount; r++) {
            DocxTableRowSpec rowSpec = rows.get(r);
            XWPFTableRow row = table.getRow(r);
            List<DocxTableCellSpec> cells = rowSpec != null && rowSpec.cells() != null ? rowSpec.cells() : List.of();
            for (int c = 0; c < colCount; c++) {
                XWPFTableCell cell = row.getCell(c);
                if (c < cells.size() && cells.get(c) != null) {
                    applyCell(cell, cells.get(c), widths != null && c < widths.size() ? widths.get(c) : null);
                } else {
                    // ensure empty cell has a paragraph
                    if (cell.getParagraphs().isEmpty()) {
                        cell.addParagraph();
                    }
                }
            }
        }
    }

    private void applyTableWidths(XWPFTable table, List<Integer> widths, int colCount) {
        long total = 0;
        for (int i = 0; i < colCount; i++) {
            total += i < widths.size() && widths.get(i) != null ? widths.get(i) : 2000;
        }
        CTTbl ctTbl = table.getCTTbl();
        CTTblPr tblPr = ctTbl.getTblPr() != null ? ctTbl.getTblPr() : ctTbl.addNewTblPr();
        CTTblWidth tblW = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblW.setType(STTblWidth.DXA);
        tblW.setW(BigInteger.valueOf(total));

        CTTblGrid grid = ctTbl.getTblGrid() != null ? ctTbl.getTblGrid() : ctTbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int i = 0; i < colCount; i++) {
            int w = i < widths.size() && widths.get(i) != null ? widths.get(i) : 2000;
            CTTblGridCol col = grid.addNewGridCol();
            col.setW(BigInteger.valueOf(w));
        }
    }

    private void applyCell(XWPFTableCell cell, DocxTableCellSpec spec, Integer fallbackWidth) {
        // Clear default empty paragraph content carefully
        while (cell.getParagraphs().size() > 1) {
            cell.removeParagraph(cell.getParagraphs().size() - 1);
        }
        Integer width = spec.widthDxa() != null ? spec.widthDxa() : fallbackWidth;
        if (width != null) {
            CTTc ctTc = cell.getCTTc();
            CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
            CTTblWidth tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
            tcW.setType(STTblWidth.DXA);
            tcW.setW(BigInteger.valueOf(width.longValue()));
        }
        if (spec.shading() != null) {
            String hex = toHexColor(spec.shading());
            if (hex != null) {
                CTTc ctTc = cell.getCTTc();
                CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
                CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
                shd.setVal(STShd.CLEAR);
                shd.setFill(hex);
            }
        }
        List<DocxParagraphSpec> paragraphs = spec.paragraphs();
        if (paragraphs == null || paragraphs.isEmpty()) {
            // leave single empty paragraph
            if (cell.getParagraphs().isEmpty()) {
                cell.addParagraph();
            } else {
                // clear text of first paragraph
                XWPFParagraph p0 = cell.getParagraphs().get(0);
                clearRuns(p0);
            }
            return;
        }
        // Reuse first paragraph, create rest
        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph p;
            if (i == 0) {
                p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
                clearRuns(p);
            } else {
                p = cell.addParagraph();
            }
            applyParagraph(p, paragraphs.get(i));
        }
    }

    private void clearRuns(XWPFParagraph paragraph) {
        while (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
    }

    private int replaceInParagraph(XWPFParagraph paragraph, String find, String replace, boolean replaceAll) {
        // First try per-run replace
        int count = 0;
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            return 0;
        }

        boolean foundInRuns = false;
        for (XWPFRun run : runs) {
            String text = run.text();
            if (text == null || !text.contains(find)) {
                continue;
            }
            foundInRuns = true;
            if (replaceAll) {
                int occurrences = countOccurrences(text, find);
                run.setText(text.replace(find, replace), 0);
                count += occurrences;
            } else {
                run.setText(text.replaceFirst(Pattern.quote(find), Matcher.quoteReplacement(replace)), 0);
                count += 1;
                return count;
            }
        }
        if (foundInRuns && replaceAll) {
            return count;
        }
        if (!replaceAll && foundInRuns) {
            return count;
        }

        // Cross-run: merge runs then replace on combined text as single run (formatting may collapse)
        String full = paragraph.getText();
        if (full == null || !full.contains(find)) {
            return 0;
        }
        mergeRunsInParagraph(paragraph);
        runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            XWPFRun run = paragraph.createRun();
            if (replaceAll) {
                int occurrences = countOccurrences(full, find);
                run.setText(full.replace(find, replace), 0);
                return occurrences;
            }
            run.setText(full.replaceFirst(Pattern.quote(find), Matcher.quoteReplacement(replace)), 0);
            return 1;
        }
        XWPFRun first = runs.get(0);
        String text = paragraph.getText();
        if (replaceAll) {
            int occurrences = countOccurrences(text, find);
            // collapse to first run
            first.setText(text.replace(find, replace), 0);
            while (paragraph.getRuns().size() > 1) {
                paragraph.removeRun(1);
            }
            return occurrences;
        } else {
            first.setText(text.replaceFirst(Pattern.quote(find), Matcher.quoteReplacement(replace)), 0);
            while (paragraph.getRuns().size() > 1) {
                paragraph.removeRun(1);
            }
            return 1;
        }
    }

    private int countOccurrences(String text, String find) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(find, idx)) >= 0) {
            count++;
            idx += Math.max(find.length(), 1);
        }
        return count;
    }

    private int mergeRunsInParagraph(XWPFParagraph paragraph) {
        int merged = 0;
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.size() < 2) {
            return 0;
        }
        int i = 0;
        while (i < paragraph.getRuns().size() - 1) {
            XWPFRun current = paragraph.getRuns().get(i);
            XWPFRun next = paragraph.getRuns().get(i + 1);
            if (canMergeRuns(current, next)) {
                String combined = nullToEmpty(current.text()) + nullToEmpty(next.text());
                // Preserve xml:space by using setText
                current.setText(combined, 0);
                // Copy non-text children already in current; drop next
                paragraph.removeRun(i + 1);
                merged++;
            } else {
                i++;
            }
        }
        return merged;
    }

    private boolean canMergeRuns(XWPFRun a, XWPFRun b) {
        // Do not merge if either has pictures or breaks
        if (!a.getEmbeddedPictures().isEmpty() || !b.getEmbeddedPictures().isEmpty()) {
            return false;
        }
        if (a.getCTR().sizeOfBrArray() > 0 || b.getCTR().sizeOfBrArray() > 0) {
            return false;
        }
        if (a.getCTR().sizeOfDrawingArray() > 0 || b.getCTR().sizeOfDrawingArray() > 0) {
            return false;
        }
        CTRPr rpr1 = a.getCTR().isSetRPr() ? a.getCTR().getRPr() : null;
        CTRPr rpr2 = b.getCTR().isSetRPr() ? b.getCTR().getRPr() : null;
        if (rpr1 == null && rpr2 == null) {
            return true;
        }
        if (rpr1 == null || rpr2 == null) {
            return false;
        }
        return rpr1.xmlText().equals(rpr2.xmlText());
    }

    private void stripProofErr(CTP ctp) {
        while (ctp.sizeOfProofErrArray() > 0) {
            ctp.removeProofErr(0);
        }
    }

    private BigInteger nextCommentId(XWPFComments comments) {
        BigInteger max = BigInteger.valueOf(-1);
        for (XWPFComment c : comments.getComments()) {
            try {
                BigInteger id = new BigInteger(c.getId());
                if (id.compareTo(max) > 0) {
                    max = id;
                }
            } catch (Exception ignored) {
            }
        }
        return max.add(BigInteger.ONE);
    }

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /**
     * Place comment range markers around the first occurrence of {@code anchorText} in the paragraph.
     * Prefers isolating the match into its own run(s), then inserts
     * {@code commentRangeStart} / {@code commentRangeEnd} / {@code commentReference} as siblings of those runs.
     */
    private boolean anchorCommentOnParagraph(XWPFParagraph paragraph, BigInteger commentId, String anchorText) {
        if (paragraph == null || anchorText == null || anchorText.isEmpty()) {
            return false;
        }
        String full = paragraph.getText();
        if (full == null) {
            return false;
        }
        int matchStart = full.indexOf(anchorText);
        if (matchStart < 0) {
            return false;
        }
        int matchEnd = matchStart + anchorText.length();

        if (!isolateCharRangeAsRuns(paragraph, matchStart, matchEnd)) {
            return false;
        }

        int[] runIdx = findRunIndicesCoveringExactRange(paragraph, matchStart, matchEnd);
        if (runIdx == null) {
            return false;
        }
        return insertCommentMarkersAroundRunRange(paragraph, runIdx[0], runIdx[1], commentId);
    }

    /**
     * Split runs so that characters [{@code from}, {@code to}) are exactly covered by whole run boundaries.
     */
    private boolean isolateCharRangeAsRuns(XWPFParagraph paragraph, int from, int to) {
        if (from < 0 || to < from) {
            return false;
        }
        // Split at end first so earlier indices stay stable, then at start.
        if (!splitParagraphAtCharOffset(paragraph, to)) {
            return false;
        }
        return splitParagraphAtCharOffset(paragraph, from);
    }

    /**
     * Ensure a run boundary exists at the given character offset within the paragraph text.
     */
    private boolean splitParagraphAtCharOffset(XWPFParagraph paragraph, int offset) {
        if (offset < 0) {
            return false;
        }
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null) {
            return false;
        }
        int pos = 0;
        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String text = nullToEmpty(run.text());
            int runStart = pos;
            int runEnd = pos + text.length();
            if (offset == runStart || offset == runEnd) {
                return true;
            }
            if (offset > runStart && offset < runEnd) {
                int local = offset - runStart;
                String left = text.substring(0, local);
                String right = text.substring(local);
                run.setText(left, 0);
                XWPFRun newRun = paragraph.insertNewRun(i + 1);
                copyRunFormatting(run, newRun);
                newRun.setText(right, 0);
                return true;
            }
            pos = runEnd;
        }
        // offset == total length is OK (end boundary)
        return offset == pos;
    }

    private int[] findRunIndicesCoveringExactRange(XWPFParagraph paragraph, int from, int to) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        int pos = 0;
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < runs.size(); i++) {
            String text = nullToEmpty(runs.get(i).text());
            int runStart = pos;
            int runEnd = pos + text.length();
            if (startIdx < 0 && from == runStart) {
                startIdx = i;
            }
            if (startIdx >= 0 && to == runEnd) {
                endIdx = i;
                break;
            }
            // Match strictly requires boundaries after isolateCharRangeAsRuns
            if (startIdx >= 0 && from < runEnd && to > runStart) {
                // still covering
                endIdx = i;
            }
            pos = runEnd;
        }
        if (startIdx < 0 || endIdx < 0) {
            return null;
        }
        // Verify exact coverage
        pos = 0;
        int coverStart = -1;
        int coverEnd = -1;
        for (int i = 0; i < runs.size(); i++) {
            String text = nullToEmpty(runs.get(i).text());
            if (i == startIdx) {
                coverStart = pos;
            }
            if (i == endIdx) {
                coverEnd = pos + text.length();
            }
            pos += text.length();
        }
        if (coverStart != from || coverEnd != to) {
            return null;
        }
        return new int[]{startIdx, endIdx};
    }

    private void copyRunFormatting(XWPFRun source, XWPFRun target) {
        if (source == null || target == null) {
            return;
        }
        CTRPr srcPr = source.getCTR().isSetRPr() ? source.getCTR().getRPr() : null;
        if (srcPr != null) {
            target.getCTR().setRPr((CTRPr) srcPr.copy());
        }
    }

    /**
     * Insert commentRangeStart before start run, commentRangeEnd + commentReference after end run.
     */
    private boolean insertCommentMarkersAroundRunRange(XWPFParagraph paragraph, int startRunIdx, int endRunIdx,
                                                       BigInteger commentId) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || startRunIdx < 0 || endRunIdx < startRunIdx || endRunIdx >= runs.size()) {
            return false;
        }
        try {
            CTR startCtr = runs.get(startRunIdx).getCTR();
            CTR endCtr = runs.get(endRunIdx).getCTR();
            Node startNode = startCtr.getDomNode();
            Node endNode = endCtr.getDomNode();
            Node parent = startNode.getParentNode();
            if (parent == null || endNode.getParentNode() != parent) {
                return false;
            }
            Document dom = startNode.getOwnerDocument();
            String id = commentId.toString();

            Element rangeStart = dom.createElementNS(W_NS, "w:commentRangeStart");
            rangeStart.setAttributeNS(W_NS, "w:id", id);
            parent.insertBefore(rangeStart, startNode);

            Element rangeEnd = dom.createElementNS(W_NS, "w:commentRangeEnd");
            rangeEnd.setAttributeNS(W_NS, "w:id", id);
            Node afterEnd = endNode.getNextSibling();
            if (afterEnd != null) {
                parent.insertBefore(rangeEnd, afterEnd);
            } else {
                parent.appendChild(rangeEnd);
            }

            Element refRun = dom.createElementNS(W_NS, "w:r");
            Element rPr = dom.createElementNS(W_NS, "w:rPr");
            Element rStyle = dom.createElementNS(W_NS, "w:rStyle");
            rStyle.setAttributeNS(W_NS, "w:val", "CommentReference");
            rPr.appendChild(rStyle);
            refRun.appendChild(rPr);
            Element commentRef = dom.createElementNS(W_NS, "w:commentReference");
            commentRef.setAttributeNS(W_NS, "w:id", id);
            refRun.appendChild(commentRef);

            Node afterRangeEnd = rangeEnd.getNextSibling();
            if (afterRangeEnd != null) {
                parent.insertBefore(refRun, afterRangeEnd);
            } else {
                parent.appendChild(refRun);
            }
            return true;
        } catch (Exception e) {
            log.debug("Failed to insert comment markers: {}", e.getMessage());
            return false;
        }
    }

    private int acceptTrackedInParagraph(CTP ctp) {
        int count = 0;

        // Paragraph mark revisions
        if (ctp.isSetPPr()) {
            CTPPr pPr = ctp.getPPr();
            if (pPr.isSetRPr()) {
                CTParaRPr rPr = pPr.getRPr();
                if (rPr.isSetDel()) {
                    rPr.unsetDel();
                    count++;
                }
                if (rPr.isSetIns()) {
                    rPr.unsetIns();
                    count++;
                }
            }
        }

        // Remove deletions (discard deleted content)
        while (ctp.sizeOfDelArray() > 0) {
            removeXmlObject(ctp.getDelArray(0));
            count++;
        }

        // Unwrap insertions (keep inserted content)
        while (ctp.sizeOfInsArray() > 0) {
            CTRunTrackChange ins = ctp.getInsArray(0);
            unwrapXmlObject(ins);
            count++;
        }

        // Also walk via cursor for any residual w:ins/w:del nested oddly
        count += acceptTrackedViaCursor(ctp);
        return count;
    }

    private int acceptTrackedViaCursor(XmlObject root) {
        int count = 0;
        // Process descendants named ins/del under wordprocessingml namespace
        XmlCursor cursor = root.newCursor();
        try {
            cursor.selectPath("declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:ins | .//w:del");
            List<XmlObject> nodes = new ArrayList<>();
            while (cursor.toNextSelection()) {
                nodes.add(cursor.getObject());
            }
            for (XmlObject node : nodes) {
                Node dom = node.getDomNode();
                if (dom == null) {
                    continue;
                }
                String local = dom.getLocalName();
                if ("del".equals(local)) {
                    removeXmlObject(node);
                    count++;
                } else if ("ins".equals(local)) {
                    unwrapXmlObject(node);
                    count++;
                }
            }
        } catch (Exception e) {
            log.debug("Cursor-based accept tracked changes partial failure: {}", e.getMessage());
        } finally {
            cursor.dispose();
        }
        return count;
    }

    private void unwrapXmlObject(XmlObject wrapper) {
        XmlCursor cursor = wrapper.newCursor();
        try {
            // Destination is the wrapper itself (insert before wrapper)
            XmlCursor dest = wrapper.newCursor();
            try {
                if (cursor.toFirstChild()) {
                    while (true) {
                        XmlCursor child = cursor.newCursor();
                        try {
                            child.moveXml(dest);
                        } finally {
                            child.dispose();
                        }
                        // After moving the first child away, the next former sibling becomes first child
                        if (!cursor.toFirstChild()) {
                            break;
                        }
                    }
                }
            } finally {
                dest.dispose();
            }
        } finally {
            cursor.dispose();
        }
        removeXmlObject(wrapper);
    }

    private void removeXmlObject(XmlObject obj) {
        XmlCursor c = obj.newCursor();
        try {
            c.removeXml();
        } finally {
            c.dispose();
        }
    }

    private String toHexColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String c = color.trim();
        if (c.startsWith("#")) {
            String hex = c.substring(1);
            if (hex.length() == 6) {
                return hex.toUpperCase(Locale.ROOT);
            }
            return null;
        }
        String[] parts = c.split(",");
        if (parts.length == 3) {
            try {
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                return String.format(Locale.ROOT, "%02X%02X%02X", r, g, b);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (c.matches("(?i)[0-9a-f]{6}")) {
            return c.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace("\r", "").trim();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
