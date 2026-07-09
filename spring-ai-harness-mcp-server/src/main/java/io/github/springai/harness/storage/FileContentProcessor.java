package io.github.springai.harness.storage;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.util.Assert;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 文件内容解析与处理工具类，提供针对纯文本、图片、PDF 和 Office 文档的统一流式处理逻辑。
 */
public final class FileContentProcessor {

	private FileContentProcessor() {
		// Utility class
	}

	public static String streamToString(InputStream is) throws IOException {
		if (is == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			return reader.lines().collect(Collectors.joining("\n"));
		}
	}

	public static List<String> streamToLines(InputStream is) throws IOException {
		if (is == null) {
			return List.of();
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			return reader.lines().collect(Collectors.toList());
		}
	}

	public static String processImageStream(InputStream is, String extension) throws IOException {
		Assert.notNull(is, "InputStream must not be null");
		Assert.hasText(extension, "extension must not be empty");

		String lower = extension.toLowerCase(Locale.ENGLISH);
		BufferedImage originalImage = ImageIO.read(is);
		if (originalImage == null) {
			throw new IOException("Failed to read image from stream");
		}

		int width = originalImage.getWidth();
		int height = originalImage.getHeight();

		if (width > StorageProvider.MAX_IMAGE_EDGE || height > StorageProvider.MAX_IMAGE_EDGE) {
			int targetWidth;
			int targetHeight;
			if (width >= height) {
				targetWidth = StorageProvider.MAX_IMAGE_EDGE;
				targetHeight = (int) Math.round((double) height * StorageProvider.MAX_IMAGE_EDGE / width);
			} else {
				targetHeight = StorageProvider.MAX_IMAGE_EDGE;
				targetWidth = (int) Math.round((double) width * StorageProvider.MAX_IMAGE_EDGE / height);
			}
			targetWidth = Math.max(1, targetWidth);
			targetHeight = Math.max(1, targetHeight);

			int type = originalImage.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : originalImage.getType();
			BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, type);
			Graphics2D g = resizedImage.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
			g.dispose();

			String format = lower.endsWith("png") ? "png" : "jpg";
			try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
				ImageIO.write(resizedImage, format, baos);
				return Base64.getEncoder().encodeToString(baos.toByteArray());
			}
		}

		String format = lower.endsWith("png") ? "png" : "jpg";
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(originalImage, format, baos);
			return Base64.getEncoder().encodeToString(baos.toByteArray());
		}
	}

	public static String processPdfStream(InputStream is, Integer startPage, Integer endPage) throws IOException {
		Assert.notNull(is, "InputStream must not be null");
		InputStreamResource resource = new InputStreamResource(is);
		PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
				.withPagesPerDocument(1)
				.build();
		PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
		List<Document> docs = reader.read();

		int totalPages = docs.size();
		int start = (startPage != null) ? Math.max(1, startPage) : 1;
		int end = (endPage != null) ? Math.min(totalPages, endPage) : totalPages;

		if (start > totalPages || start > end) {
			return "";
		}

		StringBuilder sb = new StringBuilder();
		for (int i = start - 1; i < end && i < totalPages; i++) {
			sb.append(docs.get(i).getText());
			if (i < end - 1 && i < totalPages - 1) {
				sb.append("\f");
			}
		}
		return sb.toString().trim();
	}

	public static String processDocumentStream(InputStream is) throws IOException {
		Assert.notNull(is, "InputStream must not be null");
		InputStreamResource resource = new InputStreamResource(is);
		TikaDocumentReader reader = new TikaDocumentReader(resource);
		List<Document> docs = reader.read();

		return docs.stream()
				.map(Document::getText)
				.collect(Collectors.joining("\n"))
				.trim();
	}
}
