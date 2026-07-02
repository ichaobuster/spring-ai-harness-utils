package io.github.springai.harness.storage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

final class ImageStorageUtil {

	private static final int MAX_IMAGE_SIDE = 2048;

	private static final String JPEG_MIME_TYPE = "image/jpeg";

	private ImageStorageUtil() {
	}

	static String toBase64ImageString(byte[] imageBytes, String path, String contentType) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
		if (image == null) {
			throw new IOException("File cannot be decoded as an image: " + path);
		}

		if (image.getWidth() <= MAX_IMAGE_SIDE && image.getHeight() <= MAX_IMAGE_SIDE) {
			return toDataUrl(resolveImageMimeType(path, contentType), imageBytes);
		}

		byte[] resizedBytes = resizeToJpeg(image);
		return toDataUrl(JPEG_MIME_TYPE, resizedBytes);
	}

	private static byte[] resizeToJpeg(BufferedImage source) throws IOException {
		double scale = (double) MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight());
		int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

		BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < targetHeight; y++) {
			int sourceY = Math.min(source.getHeight() - 1, (int) Math.floor(y / scale));
			for (int x = 0; x < targetWidth; x++) {
				int sourceX = Math.min(source.getWidth() - 1, (int) Math.floor(x / scale));
				resized.setRGB(x, y, flattenOnWhite(source.getRGB(sourceX, sourceY)));
			}
		}

		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			if (!ImageIO.write(resized, "jpg", outputStream)) {
				throw new IOException("No JPEG image writer available");
			}
			return outputStream.toByteArray();
		}
	}

	private static int flattenOnWhite(int argb) {
		int alpha = (argb >>> 24) & 0xff;
		if (alpha == 255) {
			return argb & 0x00ffffff;
		}
		int red = (argb >>> 16) & 0xff;
		int green = (argb >>> 8) & 0xff;
		int blue = argb & 0xff;
		red = blendWithWhite(red, alpha);
		green = blendWithWhite(green, alpha);
		blue = blendWithWhite(blue, alpha);
		return (red << 16) | (green << 8) | blue;
	}

	private static int blendWithWhite(int color, int alpha) {
		return ((color * alpha) + (255 * (255 - alpha))) / 255;
	}

	private static String toDataUrl(String mimeType, byte[] bytes) {
		return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
	}

	private static String resolveImageMimeType(String path, String contentType) {
		String normalizedContentType = normalizeImageMimeType(contentType);
		if (normalizedContentType != null) {
			return normalizedContentType;
		}

		String extension = getExtension(path);
		return switch (extension) {
			case "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "gif" -> "image/gif";
			case "bmp" -> "image/bmp";
			case "webp" -> "image/webp";
			case "svg" -> "image/svg+xml";
			case "tif", "tiff" -> "image/tiff";
			default -> "image/png";
		};
	}

	private static String normalizeImageMimeType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return null;
		}
		String mimeType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		if (!mimeType.startsWith("image/")) {
			return null;
		}
		return switch (mimeType) {
			case "image/x-png" -> "image/png";
			case "image/pjpeg" -> "image/jpeg";
			default -> mimeType;
		};
	}

	private static String getExtension(String path) {
		if (path == null) {
			return "";
		}
		int slashIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		int dotIndex = path.lastIndexOf('.');
		if (dotIndex <= slashIndex || dotIndex == path.length() - 1) {
			return "";
		}
		return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}

}
