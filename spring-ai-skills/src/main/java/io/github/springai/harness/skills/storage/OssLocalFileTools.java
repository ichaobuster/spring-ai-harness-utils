package io.github.springai.harness.skills.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Spring AI Tools that download/upload files between Aliyun OSS and the local filesystem.
 * <p>
 * Designed for domain skills (e.g. {@code XlsxTools}) that operate on local paths:
 * download an OSS object under {@code prefix + path} into {@code downloadPath}, then upload results back.
 * <p>
 * Name collision strategy: each download writes into
 * {@code {downloadPath}/{uuid}/{originalFileName}} so the original file name is preserved.
 *
 * @author ichaobuster
 */
@Slf4j
public class OssLocalFileTools {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    private static final Path DEFAULT_DOWNLOAD_PATH = Path.of("/tmp");

    private final OSS ossClient;
    private final String bucketName;
    /**
     * Normalized object key prefix. Empty string or ends with {@code /}.
     */
    private final String prefix;
    private final Path downloadPath;

    public OssLocalFileTools(OSS ossClient, String bucketName) {
        this(ossClient, bucketName, null, null);
    }

    public OssLocalFileTools(OSS ossClient, String bucketName, String prefix) {
        this(ossClient, bucketName, prefix, null);
    }

    public OssLocalFileTools(OSS ossClient, String bucketName, String prefix, Path downloadPath) {
        Assert.notNull(ossClient, "ossClient must not be null");
        Assert.hasText(bucketName, "bucketName must not be empty");
        this.ossClient = ossClient;
        this.bucketName = bucketName;
        this.prefix = normalizePrefix(prefix);
        this.downloadPath = (downloadPath != null ? downloadPath : DEFAULT_DOWNLOAD_PATH).toAbsolutePath().normalize();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OSS ossClient;
        private String bucketName;
        private String prefix;
        private Path downloadPath;

        public Builder ossClient(OSS ossClient) {
            this.ossClient = ossClient;
            return this;
        }

        public Builder bucketName(String bucketName) {
            this.bucketName = bucketName;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /**
         * Local directory that will hold downloaded files. Defaults to {@code /tmp}.
         */
        public Builder downloadPath(Path downloadPath) {
            this.downloadPath = downloadPath;
            return this;
        }

        public Builder downloadPath(String downloadPath) {
            this.downloadPath = downloadPath != null ? Path.of(downloadPath) : null;
            return this;
        }

        public OssLocalFileTools build() {
            return new OssLocalFileTools(ossClient, bucketName, prefix, downloadPath);
        }
    }

    /**
     * Download an OSS object to {@code downloadPath/{uuid}/{fileName}} and return the absolute local path.
     *
     * @param path object path relative to the configured prefix (must not start with {@code /})
     */
    @Tool(name = "downloadOssFileToLocal",
            description = "Download a file from Aliyun OSS into a local directory (default /tmp) and return the absolute local filesystem path. Creates a UUID subdirectory under the download root and keeps the original file name. Use before Xlsx/Docx tools that require local paths.")
    public String downloadOssFileToLocal(
            @ToolParam(description = "OSS object path relative to the configured prefix (e.g. 'reports/a.xlsx'). Must not start with '/'.") String path) {
        try {
            String relativePath = validateRelativeObjectPath(path);
            String fullKey = getFullKey(relativePath);

            if (!ossClient.doesObjectExist(bucketName, fullKey)) {
                throw new IllegalArgumentException("OSS object does not exist: " + fullKey);
            }

            ObjectMetadata metadata = ossClient.getObjectMetadata(bucketName, fullKey);
            if (metadata != null && metadata.getContentLength() > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException("OSS object size exceeds 50MB safety limit: " + fullKey);
            }

            String originalName = extractFileName(relativePath);
            Path targetDir = downloadPath.resolve(uuidDirName()).normalize();
            if (!targetDir.startsWith(downloadPath)) {
                throw new IllegalArgumentException("Resolved download directory escapes downloadPath: " + targetDir);
            }
            Files.createDirectories(targetDir);

            Path targetFile = targetDir.resolve(originalName).normalize();
            if (!targetFile.startsWith(targetDir)) {
                throw new IllegalArgumentException("Resolved local file escapes UUID directory: " + targetFile);
            }

            try (OSSObject ossObject = ossClient.getObject(bucketName, fullKey);
                 InputStream raw = ossObject.getObjectContent();
                 InputStream in = new LimitedSizeInputStream(raw, MAX_FILE_SIZE_BYTES, fullKey);
                 OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            } catch (IOException e) {
                // Clean up partial file if the size limit (or I/O) failed mid-stream
                try {
                    Files.deleteIfExists(targetFile);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }

            String absolute = targetFile.toAbsolutePath().toString();
            log.info("Downloaded OSS object {} (bucket={}) to {}", fullKey, bucketName, absolute);
            return absolute;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("50MB safety limit")) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            log.error("Failed to download OSS object path={} bucket={}", path, bucketName, e);
            return "Error downloading OSS file to local path: " + e.getMessage();
        } catch (Exception e) {
            log.error("Failed to download OSS object path={} bucket={}", path, bucketName, e);
            return "Error downloading OSS file to local path: " + e.getMessage();
        }
    }

    /**
     * Upload a local file to OSS under {@code prefix + path}.
     */
    @Tool(name = "uploadLocalFileToOss",
            description = "Upload a local filesystem file to Aliyun OSS under the configured bucket/prefix. Use after domain tools finish writing a local result file.")
    public String uploadLocalFileToOss(
            @ToolParam(description = "Absolute or relative local filesystem path of the source file") String localPath,
            @ToolParam(description = "OSS object path relative to the configured prefix (e.g. 'reports/a.xlsx'). Must not start with '/'.") String path) {
        try {
            Path source = resolveExistingLocalFile(localPath);
            String relativePath = validateRelativeObjectPath(path);
            String fullKey = getFullKey(relativePath);

            long size = Files.size(source);
            if (size > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException("Local file size exceeds 50MB safety limit: " + source);
            }

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(size);

            try (InputStream in = Files.newInputStream(source)) {
                ossClient.putObject(bucketName, fullKey, in, metadata);
            }

            String msg = "Successfully uploaded local file to OSS: " + fullKey
                    + " (bucket=" + bucketName + ", from=" + source.toAbsolutePath() + ")";
            log.info(msg);
            return msg;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload local file {} to OSS path={} bucket={}", localPath, path, bucketName, e);
            return "Error uploading local file to OSS: " + e.getMessage();
        }
    }

    // --- helpers mirrored from AliyunOssStorage path rules ---

    static String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "";
        }
        String ossPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
        if (ossPrefix.startsWith("/")) {
            ossPrefix = ossPrefix.substring(1);
        }
        return ossPrefix;
    }

    String getFullKey(String path) {
        if (!StringUtils.hasText(path)) {
            return this.prefix;
        }
        if (path.startsWith("/")) {
            throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
        }
        if (path.startsWith("./")) {
            return this.prefix + path.substring(2);
        }
        return this.prefix + path;
    }

    private static String validateRelativeObjectPath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path must be relative to the OSS prefix and must not start with '/': " + path);
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("path must not contain '..': " + path);
        }
        return path;
    }

    private static String extractFileName(String relativePath) {
        String name = Path.of(relativePath).getFileName().toString();
        name = sanitizeFileName(name);
        if (!StringUtils.hasText(name) || ".".equals(name) || "..".equals(name)) {
            return "download.bin";
        }
        return name;
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/\\x00-\\x1F]", "_");
        if (!StringUtils.hasText(cleaned)) {
            return "download.bin";
        }
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    private static String uuidDirName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Path resolveExistingLocalFile(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            throw new IllegalArgumentException("localPath must not be blank");
        }
        Path path = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Local file does not exist: " + path);
        }
        return path;
    }

    /**
     * Counts bytes read and aborts once {@code maxBytes} is exceeded.
     * Used so the 50MB guard holds even when OSS omits Content-Length.
     */
    static final class LimitedSizeInputStream extends FilterInputStream {
        private final long maxBytes;
        private final String label;
        private long bytesRead;

        LimitedSizeInputStream(InputStream in, long maxBytes, String label) {
            super(in);
            this.maxBytes = maxBytes;
            this.label = label;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long n) throws IOException {
            bytesRead += n;
            if (bytesRead > maxBytes) {
                throw new IOException("OSS object exceeds 50MB safety limit while downloading: " + label);
            }
        }
    }

    // visible for tests
    String prefix() {
        return prefix;
    }

    Path downloadPath() {
        return downloadPath;
    }

    String bucketName() {
        return bucketName;
    }
}
