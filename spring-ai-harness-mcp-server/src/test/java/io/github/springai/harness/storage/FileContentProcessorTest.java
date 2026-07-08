package io.github.springai.harness.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FileContentProcessor Tests")
class FileContentProcessorTest {

	@Test
	@DisplayName("Should convert stream to String successfully")
	void shouldConvertStreamToString() throws IOException {
		String input = "Hello\nWorld";
		try (InputStream is = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))) {
			String result = FileContentProcessor.streamToString(is);
			assertThat(result).isEqualTo(input);
		}
	}

	@Test
	@DisplayName("Should convert stream to lines successfully")
	void shouldConvertStreamToLines() throws IOException {
		String input = "Line1\nLine2\nLine3";
		try (InputStream is = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))) {
			List<String> result = FileContentProcessor.streamToLines(is);
			assertThat(result).containsExactly("Line1", "Line2", "Line3");
		}
	}

	@Test
	@DisplayName("Should encode image stream to base64 directly if within limits")
	void shouldEncodeImageStreamDirectly() throws IOException {
		// Create a small 10x10 image in memory
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", baos);
		byte[] imgBytes = baos.toByteArray();

		try (InputStream is = new ByteArrayInputStream(imgBytes)) {
			String base64Result = FileContentProcessor.processImageStream(is, "test.png");
			assertThat(base64Result).isEqualTo(Base64.getEncoder().encodeToString(imgBytes));
		}
	}

	@Test
	@DisplayName("Should resize image stream if either side is greater than MAX_IMAGE_EDGE")
	void shouldResizeLargeImageStream() throws IOException {
		// Generate a 3000x1000 png image in memory
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3000, 1000, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.RED);
		g.fillRect(0, 0, 3000, 1000);
		g.dispose();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", baos);
		byte[] imgBytes = baos.toByteArray();

		try (InputStream is = new ByteArrayInputStream(imgBytes)) {
			String base64Result = FileContentProcessor.processImageStream(is, "large.png");
			byte[] decodedRaw = Base64.getDecoder().decode(base64Result);

			try (ByteArrayInputStream bais = new ByteArrayInputStream(decodedRaw)) {
				java.awt.image.BufferedImage resizedImg = javax.imageio.ImageIO.read(bais);
				assertThat(resizedImg).isNotNull();
				int expectedHeight = (int) Math.round(1000.0 * StorageProvider.MAX_IMAGE_EDGE / 3000.0);
				assertThat(resizedImg.getWidth()).isEqualTo(StorageProvider.MAX_IMAGE_EDGE);
				assertThat(resizedImg.getHeight()).isEqualTo(expectedHeight);
			}
		}
	}

	@Test
	@DisplayName("Should parse PDF stream and support page ranges")
	void shouldParsePdfStreamAndSupportPageRanges() throws IOException {
		// Generate a simple 2-page PDF in memory
		try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
			// Page 1
			org.apache.pdfbox.pdmodel.PDPage page1 = new org.apache.pdfbox.pdmodel.PDPage();
			doc.addPage(page1);
			try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page1)) {
				cs.beginText();
				cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(100, 700);
				cs.showText("Hello Page 1");
				cs.endText();
			}

			// Page 2
			org.apache.pdfbox.pdmodel.PDPage page2 = new org.apache.pdfbox.pdmodel.PDPage();
			doc.addPage(page2);
			try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page2)) {
				cs.beginText();
				cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
				cs.newLineAtOffset(100, 700);
				cs.showText("Hello Page 2");
				cs.endText();
			}

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			doc.save(baos);
			byte[] pdfBytes = baos.toByteArray();

			// Test read full PDF
			try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
				String fullText = FileContentProcessor.processPdfStream(is, null, null);
				assertThat(fullText).contains("Page").contains("1").contains("2");
			}

			// Test page 1 range
			try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
				String p1Text = FileContentProcessor.processPdfStream(is, 1, 1);
				assertThat(p1Text).contains("Page").contains("1");
				assertThat(p1Text).doesNotContain("Page    2").doesNotContain("Page 2");
			}

			// Test page 2 range
			try (InputStream is = new ByteArrayInputStream(pdfBytes)) {
				String p2Text = FileContentProcessor.processPdfStream(is, 2, 2);
				assertThat(p2Text).contains("Page").contains("2");
				assertThat(p2Text).doesNotContain("Page    1").doesNotContain("Page 1");
			}
		}
	}

	@Test
	@DisplayName("Should parse Office document stream successfully")
	void shouldParseOfficeDocumentStream() throws IOException {
		// Generate a simple docx in memory
		try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
			org.apache.poi.xwpf.usermodel.XWPFParagraph p = doc.createParagraph();
			org.apache.poi.xwpf.usermodel.XWPFRun r = p.createRun();
			r.setText("Hello Docx Document Content");

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			doc.write(baos);
			byte[] docxBytes = baos.toByteArray();

			try (InputStream is = new ByteArrayInputStream(docxBytes)) {
				String docText = FileContentProcessor.processDocumentStream(is);
				assertThat(docText).contains("Hello Docx Document Content");
			}
		}
	}
}
