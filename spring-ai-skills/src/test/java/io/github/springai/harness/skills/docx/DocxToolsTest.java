package io.github.springai.harness.skills.docx;

import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.util.SkillUtil;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTParaRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRunTrackChange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STProofErr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocxToolsTest {

    private DocxTools docxTools;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        this.docxTools = new DocxTools();
    }

    private String path(String name) {
        return tempDir.resolve(name).toAbsolutePath().toString();
    }

    private DocxRunSpec run(String text) {
        return new DocxRunSpec(text, null, null, null, null, null, null, null, null, null, null);
    }

    private DocxRunSpec run(String text, Boolean bold, Boolean italic, Boolean underline,
                            String font, Integer size, String color, String breakType,
                            String imagePath, Integer w, Integer h) {
        return new DocxRunSpec(text, bold, italic, underline, font, size, color, breakType, imagePath, w, h);
    }

    private DocxBlockSpec para(String style, String align, DocxRunSpec... runs) {
        return new DocxBlockSpec("paragraph",
                new DocxParagraphSpec(style, align, runs == null ? null : Arrays.asList(runs)),
                null);
    }

    private void createSimple(String filePath, String text) {
        String result = docxTools.createDocx(filePath, List.of(para(null, null, run(text))), null);
        assertThat(result).contains("Successfully created");
        assertThat(Files.exists(Path.of(filePath))).isTrue();
    }

    private void writeDocx(String filePath, XWPFDocument doc) throws Exception {
        Path p = Path.of(filePath);
        Files.createDirectories(p.getParent());
        try (OutputStream os = Files.newOutputStream(p)) {
            doc.write(os);
        }
    }

    @Test
    void testNoArgConstructor() {
        assertThat(new DocxTools()).isNotNull();
    }

    @Test
    void testCreateDocxAndPreviewAndContent() {
        String target = path("report.docx");
        List<DocxBlockSpec> blocks = List.of(
                para("Heading1", "LEFT", run("Quarterly Report", true, null, null, "Arial", 16, "0,0,0", null, null, null, null)),
                para("Normal", null, run("Hello world body.", null, null, null, "Arial", 11, null, null, null, null, null)),
                new DocxBlockSpec("table", null,
                        new DocxTableSpec(List.of(2000, 2000), List.of(
                                new DocxTableRowSpec(List.of(
                                        new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("Item", true, null, null, null, null, null, null, null, null, null)))), null, null),
                                        new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("Qty", true, null, null, null, null, null, null, null, null, null)))), null, null)
                                )),
                                new DocxTableRowSpec(List.of(
                                        new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("Apples")))), null, null),
                                        new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("3")))), null, null)
                                ))
                        ))),
                new DocxBlockSpec("pageBreak", null, null),
                para("Normal", "CENTER", run("Page two", null, true, null, null, null, null, null, null, null, null))
        );

        assertThat(docxTools.createDocx(target, blocks, null)).contains("Successfully created DOCX");
        assertThat(Files.exists(Path.of(target))).isTrue();

        String preview = docxTools.readDocxPreview(target, 20);
        assertThat(preview).contains("Quarterly Report");
        assertThat(preview).contains("Tables:");
        assertThat(preview).contains("Apples");

        String content = docxTools.readDocxContent(target, 1, 50);
        assertThat(content).contains("Hello world body.");
        assertThat(content).contains("[TABLE");
        assertThat(content).contains("Page two");
    }

    @Test
    void testCreateWithRichFormattingPageSetupAndImages() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(img, "png", pngOut);
        byte[] png = pngOut.toByteArray();
        Files.write(tempDir.resolve("logo.png"), png);
        Files.write(tempDir.resolve("photo.jpg"), png);
        Files.write(tempDir.resolve("a.gif"), png);
        Files.write(tempDir.resolve("a.bmp"), png);
        Files.write(tempDir.resolve("a.emf"), png);
        Files.write(tempDir.resolve("a.wmf"), png);
        Files.write(tempDir.resolve("a.unknown"), png);

        String logo = path("logo.png");
        DocxPageSetupSpec page = new DocxPageSetupSpec(12240, 15840, 720, 720, 720, 720, true);
        List<DocxBlockSpec> blocks = List.of(
                para("Heading 1", "JUSTIFY", run("Styled", true, true, true, "Times New Roman", 14, "#FF0000", null, null, null, null)),
                para(null, "INVALID", run("line", null, null, null, null, null, "AABBCC", "TEXT", null, null, null)),
                para(null, null, run(null, null, null, null, null, 0, "not-a-color", "PAGE", null, null, null)),
                para(null, null, run("with image", null, null, null, null, null, "1,2,3,4", null, logo, 10, 10)),
                para(null, null, run(null, null, null, null, null, null, null, null, path("missing-img.png"), null, null)),
                para(null, null, run("jpg", null, null, null, null, null, null, null, path("photo.jpg"), 8, 8)),
                para(null, null, run("gif", null, null, null, null, null, null, null, path("a.gif"), 8, 8)),
                para(null, null, run("bmp", null, null, null, null, null, null, null, path("a.bmp"), 8, 8)),
                para(null, null, run("emf", null, null, null, null, null, null, null, path("a.emf"), 8, 8)),
                para(null, null, run("wmf", null, null, null, null, null, null, null, path("a.wmf"), 8, 8)),
                para(null, null, run("unk", null, null, null, null, null, null, null, path("a.unknown"), 8, 8)),
                para(null, null),
                new DocxBlockSpec("page_break", null, null),
                new DocxBlockSpec("page-break", null, null),
                new DocxBlockSpec("table", null, new DocxTableSpec(null, List.of(
                        new DocxTableRowSpec(List.of(
                                new DocxTableCellSpec(
                                        List.of(
                                                new DocxParagraphSpec(null, null, List.of(run("c1"))),
                                                new DocxParagraphSpec(null, null, List.of(run("c1b")))
                                        ),
                                        1500,
                                        "#EEEEEE"
                                ),
                                new DocxTableCellSpec(null, null, "255,255,0"),
                                new DocxTableCellSpec(List.of(), null, "bad-shade")
                        )),
                        new DocxTableRowSpec(null),
                        new DocxTableRowSpec(List.of())
                )))
        );

        assertThat(docxTools.createDocx(path("rich.docx"), blocks, page)).contains("Successfully created");
        String preview = docxTools.readDocxPreview(path("rich.docx"), null);
        assertThat(preview).contains("Styled");
        assertThat(preview).contains("[image missing:");
    }

    @Test
    void testCreateDocxValidationBranches() {
        assertThat(docxTools.createDocx(null, List.of(para(null, null, run("x"))), null)).contains("filePath cannot be empty");
        assertThat(docxTools.createDocx("  ", List.of(para(null, null, run("x"))), null)).contains("filePath cannot be empty");
        assertThat(docxTools.createDocx(path("x.docx"), null, null)).contains("cannot be empty");
        assertThat(docxTools.createDocx(path("x.docx"), List.of(), null)).contains("cannot be empty");

        List<DocxBlockSpec> withSkips = new ArrayList<>();
        withSkips.add(null);
        withSkips.add(new DocxBlockSpec(null, null, null));
        withSkips.add(para(null, null, run("ok")));
        assertThat(docxTools.createDocx(path("skip.docx"), withSkips, null)).contains("Successfully created");

        assertThat(docxTools.createDocx(path("bad-p.docx"),
                List.of(new DocxBlockSpec("paragraph", null, null)), null))
                .contains("paragraph block missing paragraph payload");
        assertThat(docxTools.createDocx(path("bad-t.docx"),
                List.of(new DocxBlockSpec("table", null, null)), null))
                .contains("table block missing rows");
        assertThat(docxTools.createDocx(path("bad-t2.docx"),
                List.of(new DocxBlockSpec("table", null, new DocxTableSpec(List.of(1000), null))), null))
                .contains("table block missing rows");
        assertThat(docxTools.createDocx(path("bad-t3.docx"),
                List.of(new DocxBlockSpec("table", null, new DocxTableSpec(List.of(1000), List.of()))), null))
                .contains("table block missing rows");
        assertThat(docxTools.createDocx(path("bad-type.docx"),
                List.of(new DocxBlockSpec("chart", null, null)), null))
                .contains("unsupported block type");
    }

    @Test
    void testPreviewAndContentPaginationAndDefaults() {
        List<DocxBlockSpec> blocks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            blocks.add(para(i == 0 ? "Heading2" : null, null, run("Block " + i + " " + "x".repeat(i == 1 ? 200 : 1))));
        }
        blocks.add(para("Heading3", null, run("   ")));
        String file = path("many.docx");
        docxTools.createDocx(file, blocks, null);

        assertThat(docxTools.readDocxPreview(file, null)).contains("truncated");
        assertThat(docxTools.readDocxPreview(file, 0)).contains("Word Preview");
        assertThat(docxTools.readDocxPreview(file, 1000)).contains("Block 0");
        assertThat(docxTools.readDocxContent(file, null, null)).contains("Blocks 1-");
        assertThat(docxTools.readDocxContent(file, 0, 0)).contains("Block 0");
        assertThat(docxTools.readDocxContent(file, 999, 10)).contains("beyond document length");
        assertThat(docxTools.readDocxPreview(path("missing.docx"), 5)).contains("does not exist");
        assertThat(docxTools.readDocxContent(path("missing.docx"), 1, 5)).contains("does not exist");
        String nullPathErr = docxTools.readDocxPreview(null, 5);
        assertThat(nullPathErr.toLowerCase()).containsAnyOf("blank", "empty");
        String blankPathErr = docxTools.readDocxPreview("  ", 5);
        assertThat(blankPathErr.toLowerCase()).containsAnyOf("blank", "empty");
    }

    @Test
    void testPreviewWithoutTablesAndPageBreakDetection() throws Exception {
        String file = path("notable.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph h = doc.createParagraph();
            h.setStyle("Heading1");
            h.createRun().setText("Only Heading");
            XWPFParagraph p = doc.createParagraph();
            p.createRun().addBreak(BreakType.PAGE);
            doc.createParagraph().createRun().setText("after break");
            writeDocx(file, doc);
        }
        String preview = docxTools.readDocxPreview(file, 10);
        assertThat(preview).contains("Tables: 0");
        assertThat(preview).contains("Only Heading");
        String content = docxTools.readDocxContent(file, 1, 20);
        assertThat(content.contains("PAGE_BREAK") || content.contains("after break")).isTrue();
    }

    @Test
    void testCorruptFileErrorPaths() throws Exception {
        String bad = path("bad.docx");
        Files.writeString(Path.of(bad), "not-a-docx", StandardCharsets.UTF_8);
        assertThat(docxTools.readDocxPreview(bad, 5)).contains("Error previewing DOCX");
        assertThat(docxTools.readDocxContent(bad, 1, 5)).contains("Error reading DOCX");
        assertThat(docxTools.replaceDocxText(bad, "a", "b", true)).contains("Error replacing text");
        assertThat(docxTools.mergeDocxRuns(bad, null)).contains("Error merging runs");
        assertThat(docxTools.addDocxComment(bad, "c", null, null, null, null)).contains("Error adding comment");
        assertThat(docxTools.acceptDocxTrackedChanges(bad, null)).contains("Error accepting tracked changes");
    }

    @Test
    void testReplaceDocxText() {
        String file = path("edit.docx");
        createSimple(file, "alpha beta alpha");
        assertThat(docxTools.replaceDocxText(file, "alpha", "ALPHA", true)).contains("2 replacement");
        assertThat(docxTools.readDocxPreview(file, 5)).contains("ALPHA beta ALPHA");
        assertThat(docxTools.replaceDocxText(file, "ALPHA", "A", false)).contains("1 replacement");
        assertThat(docxTools.readDocxPreview(file, 5)).contains("A beta ALPHA");
    }

    @Test
    void testReplaceBranchesCrossRunAndTableAndDefaults() throws Exception {
        String file = path("cross.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("Hel");
            p.createRun().setText("loWorld");
            doc.createParagraph();
            var table = doc.createTable(1, 1);
            table.getRow(0).getCell(0).setText("cell-foo-cell");
            writeDocx(file, doc);
        }
        assertThat(docxTools.replaceDocxText(file, "Hello", null, null)).contains("replacement");
        assertThat(docxTools.readDocxContent(file, 1, 20)).contains("World");
        assertThat(docxTools.replaceDocxText(file, "foo", "bar", true)).contains("replacement");
        assertThat(docxTools.readDocxContent(file, 1, 20)).contains("cell-bar-cell");
        assertThat(docxTools.replaceDocxText(file, "zzz", "q", true)).contains("0 replacement");
        assertThat(docxTools.replaceDocxText(file, null, "q", true)).contains("find cannot be empty");
        assertThat(docxTools.replaceDocxText(path("missing.docx"), "a", "b", true)).contains("does not exist");
    }

    @Test
    void testReplaceSingleOccurrenceStopsEarly() {
        String file = path("once.docx");
        createSimple(file, "xx yy xx");
        assertThat(docxTools.replaceDocxText(file, "xx", "ZZ", false)).contains("1 replacement");
        assertThat(docxTools.readDocxContent(file, 1, 5)).contains("ZZ yy xx");
    }

    @Test
    void testMergeDocxRuns() throws Exception {
        String file = path("fragmented.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r1 = p.createRun();
            r1.setText("Hel");
            r1.setBold(true);
            XWPFRun r2 = p.createRun();
            r2.setText("lo");
            r2.setBold(true);
            XWPFRun r3 = p.createRun();
            r3.setText(" World");
            r3.setBold(false);
            writeDocx(file, doc);
        }
        assertThat(docxTools.mergeDocxRuns(file, null)).contains("Merged").contains("1 runs");
        try (InputStream is = Files.newInputStream(Path.of(file));
             XWPFDocument doc = new XWPFDocument(is)) {
            XWPFParagraph p = doc.getParagraphs().get(0);
            assertThat(p.getRuns()).hasSize(2);
            assertThat(p.getRuns().get(0).text()).isEqualTo("Hello");
            assertThat(p.getText()).isEqualTo("Hello World");
        }
    }

    @Test
    void testMergeRunsOutputPathProofErrAndCannotMerge() throws Exception {
        String file = path("merge2.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("A");
            p.createRun().setText("B");
            XWPFRun br = p.createRun();
            br.setText("C");
            br.addBreak();
            p.createRun().setText("D");
            XWPFRun b = p.createRun();
            b.setBold(true);
            b.setText("E");
            p.createRun().setText("F");
            p.getCTP().addNewProofErr().setType(STProofErr.SPELL_START);
            writeDocx(file, doc);
        }
        String out = path("merge2-out.docx");
        assertThat(docxTools.mergeDocxRuns(file, out)).contains("wrote " + out);
        assertThat(Files.exists(Path.of(out))).isTrue();

        String m3 = path("m3.docx");
        createSimple(m3, "x");
        assertThat(docxTools.mergeDocxRuns(m3, "  ")).contains("wrote " + m3);
        assertThat(docxTools.mergeDocxRuns(path("missing.docx"), null)).contains("does not exist");
    }

    @Test
    void testMergeRunsAsymmetricFormatting() throws Exception {
        String file = path("asym.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("x");
            XWPFRun b = p.createRun();
            b.setBold(true);
            b.setText("y");
            writeDocx(file, doc);
        }
        assertThat(docxTools.mergeDocxRuns(file, null)).contains("Merged 0 runs");
    }

    @Test
    void testAddDocxCommentWithAndWithoutAnchor() throws Exception {
        String file = path("comments.docx");
        createSimple(file, "Fee cap is too low for this contract.");

        assertThat(docxTools.addDocxComment(file, "Please raise the cap", "Reviewer", "R", "Fee cap", null))
                .contains("Added comment id=").contains("anchored");
        assertThat(docxTools.addDocxComment(file, "General note", "Reviewer", "R", null, null))
                .contains("no anchorText");
        String out = path("comments-out.docx");
        assertThat(docxTools.addDocxComment(file, "X", null, null, "no-such-anchor-text", out))
                .contains("anchorText not found");
        assertThat(Files.exists(Path.of(out))).isTrue();
        assertThat(docxTools.addDocxComment(file, "Y", "  ", "  ", null, null)).contains("author=Codex");

        try (InputStream is = Files.newInputStream(Path.of(file));
             XWPFDocument doc = new XWPFDocument(is)) {
            assertThat(doc.getDocComments()).isNotNull();
            List<XWPFComment> comments = doc.getDocComments().getComments();
            assertThat(comments).hasSizeGreaterThanOrEqualTo(2);
            assertThat(comments.stream().map(XWPFComment::getText).anyMatch(t -> t.contains("Please raise the cap"))).isTrue();

            // Anchored comment must wrap the matched text in document order:
            // commentRangeStart ... Fee cap ... commentRangeEnd ... commentReference
            String xml = doc.getDocument().getBody().xmlText();
            assertThat(xml).contains("commentRangeStart");
            assertThat(xml).contains("commentRangeEnd");
            assertThat(xml).contains("commentReference");
            int start = xml.indexOf("commentRangeStart");
            int fee = xml.indexOf("Fee cap");
            int end = xml.indexOf("commentRangeEnd");
            int ref = xml.indexOf("commentReference");
            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(fee).isGreaterThan(start);
            assertThat(end).isGreaterThan(fee);
            assertThat(ref).isGreaterThan(end);
        }
    }

    @Test
    void testAddCommentValidation() {
        String file = path("c.docx");
        createSimple(file, "text");
        assertThat(docxTools.addDocxComment(file, null, null, null, null, null)).contains("cannot be empty");
        assertThat(docxTools.addDocxComment(file, "  ", null, null, null, null)).contains("cannot be empty");
        assertThat(docxTools.addDocxComment(path("missing.docx"), "hi", null, null, null, null)).contains("does not exist");
    }

    @Test
    void testAcceptDocxTrackedChanges() throws Exception {
        String input = path("tracked.docx");
        String output = path("clean.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("Keep ");
            CTP ctp = p.getCTP();
            CTRunTrackChange ins = ctp.addNewIns();
            ins.setId(BigInteger.ZERO);
            ins.setAuthor("Editor");
            ins.setDate(Calendar.getInstance());
            CTR insRun = ins.addNewR();
            insRun.addNewT().setStringValue("INSERTED ");
            CTRunTrackChange del = ctp.addNewDel();
            del.setId(BigInteger.ONE);
            del.setAuthor("Editor");
            del.setDate(Calendar.getInstance());
            del.addNewR().addNewDelText().setStringValue("DELETED ");
            p.createRun().setText("End");

            CTPPr pPr = ctp.isSetPPr() ? ctp.getPPr() : ctp.addNewPPr();
            CTParaRPr rPr = pPr.isSetRPr() ? pPr.getRPr() : pPr.addNewRPr();
            var delMark = rPr.addNewDel();
            delMark.setAuthor("Editor");
            delMark.setId(BigInteger.valueOf(2));
            delMark.setDate(Calendar.getInstance());
            var insMark = rPr.addNewIns();
            insMark.setAuthor("Editor");
            insMark.setId(BigInteger.valueOf(3));
            insMark.setDate(Calendar.getInstance());
            writeDocx(input, doc);
        }

        assertThat(docxTools.acceptDocxTrackedChanges(input, output)).contains("Accepted tracked changes");
        assertThat(Files.exists(Path.of(output))).isTrue();
        try (InputStream is = Files.newInputStream(Path.of(output));
             XWPFDocument doc = new XWPFDocument(is)) {
            String text = doc.getParagraphs().get(0).getText();
            assertThat(text).contains("Keep").contains("INSERTED").contains("End").doesNotContain("DELETED");
            assertThat(doc.getParagraphs().get(0).getCTP().sizeOfInsArray()).isZero();
            assertThat(doc.getParagraphs().get(0).getCTP().sizeOfDelArray()).isZero();
        }
    }

    @Test
    void testAcceptTrackedChangesDefaultOutputAndMissing() {
        String file = path("acc.docx");
        createSimple(file, "plain");
        assertThat(docxTools.acceptDocxTrackedChanges(file, null)).contains("wrote " + file);
        assertThat(docxTools.acceptDocxTrackedChanges(file, "  ")).contains("wrote " + file);
        assertThat(docxTools.acceptDocxTrackedChanges(path("missing.docx"), path("o.docx"))).contains("does not exist");
    }

    @Test
    void testFileNotExistsAndTooLarge() throws Exception {
        assertThat(docxTools.readDocxPreview(path("non_existent.docx"), 10)).contains("does not exist");
        Path huge = tempDir.resolve("huge.docx");
        try (RandomAccessFile raf = new RandomAccessFile(huge.toFile(), "rw")) {
            raf.setLength(51L * 1024 * 1024);
        }
        assertThat(docxTools.readDocxPreview(huge.toAbsolutePath().toString(), 10)).contains("50MB");
        assertThat(docxTools.replaceDocxText(huge.toAbsolutePath().toString(), "a", "b", true)).contains("50MB");
        assertThat(docxTools.mergeDocxRuns(huge.toAbsolutePath().toString(), null)).contains("50MB");
        assertThat(docxTools.addDocxComment(huge.toAbsolutePath().toString(), "t", null, null, null, null)).contains("50MB");
        assertThat(docxTools.acceptDocxTrackedChanges(huge.toAbsolutePath().toString(), null)).contains("50MB");
    }

    @Test
    void testErrorPaths() {
        assertThat(docxTools.createDocx(path("x.docx"), null, null)).contains("cannot be empty");
        assertThat(docxTools.createDocx(path("x.docx"), List.of(), null)).contains("cannot be empty");
        String ok = path("ok.docx");
        createSimple(ok, "x");
        assertThat(docxTools.replaceDocxText(ok, "", "b", true)).contains("find cannot be empty");
        assertThat(docxTools.addDocxComment(ok, "  ", null, null, null, null)).contains("cannot be empty");
    }

    @Test
    void testSkillUtilLoadClassPath() {
        List<SkillsTool.Skill> skills = SkillUtil.loadClassPath("classpath*:skills/docx/SKILL.md");
        assertThat(skills).isNotEmpty();
        SkillsTool.Skill docx = skills.stream().filter(s -> "docx".equals(s.name())).findFirst().orElse(null);
        assertThat(docx).isNotNull();
        String tools = docx.frontMatter().get("tool-calls").toString();
        assertThat(tools).contains("readDocxPreview", "createDocx", "replaceDocxText", "mergeDocxRuns",
                "addDocxComment", "acceptDocxTrackedChanges", "readDocxContent");
    }

    @Test
    void testTableReplaceAndNestedStructure() {
        String file = path("tbl.docx");
        List<DocxBlockSpec> blocks = List.of(
                new DocxBlockSpec("table", null, new DocxTableSpec(List.of(1000, 1000, 1000), List.of(
                        new DocxTableRowSpec(List.of(
                                new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("findme")))), 1000, null),
                                new DocxTableCellSpec(List.of(new DocxParagraphSpec(null, null, List.of(run("keep")))), null, null)
                        ))
                )))
        );
        docxTools.createDocx(file, blocks, new DocxPageSetupSpec(null, null, null, null, null, null, false));
        assertThat(docxTools.replaceDocxText(file, "findme", "found", true)).contains("1 replacement");
        assertThat(docxTools.readDocxContent(file, 1, 10)).contains("found");
    }

    @Test
    void testColorsHexInvalidAndBare() {
        assertThat(docxTools.createDocx(path("colors.docx"), List.of(
                para(null, null, run("a", null, null, null, null, null, "#ABC", null, null, null, null)),
                para(null, null, run("b", null, null, null, null, null, "12,xx,3", null, null, null, null)),
                para(null, null, run("c", null, null, null, null, null, "ddeeff", null, null, null, null)),
                para(null, null, run("d", null, null, null, null, null, " ", null, null, null, null)),
                para(null, null, run("e", null, null, null, null, null, null, "LINE", null, null, null))
        ), null)).contains("Successfully");
    }

    @Test
    void testLongHeadingJpegAndPicturesPreventMerge() throws Exception {
        docxTools.createDocx(path("longh.docx"), List.of(
                para("Heading1", null, run("H".repeat(200))),
                para(null, null, run("body"))
        ), null);
        assertThat(docxTools.readDocxPreview(path("longh.docx"), 5)).contains("…");

        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bosImg = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bosImg);
        Files.write(tempDir.resolve("x.jpeg"), bosImg.toByteArray());
        assertThat(docxTools.createDocx(path("jpeg.docx"), List.of(
                para(null, null, run("j", null, null, null, null, null, null, null, path("x.jpeg"), 0, -1))
        ), null)).contains("Successfully");

        String picFile = path("picmerge.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r1 = p.createRun();
            r1.setText("A");
            try (InputStream is = new ByteArrayInputStream(bosImg.toByteArray())) {
                r1.addPicture(is, PictureType.PNG, "x.png", Units.toEMU(1), Units.toEMU(1));
            }
            XWPFRun r2 = p.createRun();
            r2.setText("B");
            try (InputStream is = new ByteArrayInputStream(bosImg.toByteArray())) {
                r2.addPicture(is, PictureType.PNG, "y.png", Units.toEMU(1), Units.toEMU(1));
            }
            writeDocx(picFile, doc);
        }
        assertThat(docxTools.mergeDocxRuns(picFile, null)).contains("Merged 0 runs");
    }

    @Test
    void testCreateParentDirectories() {
        String nested = path("sub/dir/out.docx");
        assertThat(docxTools.createDocx(nested, List.of(para(null, null, run("nested"))), null))
                .contains("Successfully");
        assertThat(Files.exists(Path.of(nested))).isTrue();
    }
}
