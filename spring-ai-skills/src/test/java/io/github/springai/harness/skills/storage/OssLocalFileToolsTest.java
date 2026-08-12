package io.github.springai.harness.skills.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssLocalFileToolsTest {

    private static final String BUCKET = "test-bucket";
    private static final String PREFIX = "mcp/workspaces/sys-agent-user/";

    private OSS ossClient;
    private OssLocalFileTools tools;
    private Path downloadRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        downloadRoot = tempDir.resolve("dl");
        Files.createDirectories(downloadRoot);
        ossClient = mock(OSS.class);
        tools = OssLocalFileTools.builder()
                .ossClient(ossClient)
                .bucketName(BUCKET)
                .prefix(PREFIX)
                .downloadPath(downloadRoot)
                .build();
    }

    @Test
    void normalizePrefixAndFullKey() {
        assertThat(tools.prefix()).isEqualTo(PREFIX);
        assertThat(tools.getFullKey("reports/a.xlsx")).isEqualTo(PREFIX + "reports/a.xlsx");
        assertThat(tools.getFullKey("./reports/a.xlsx")).isEqualTo(PREFIX + "reports/a.xlsx");

        OssLocalFileTools noPrefix = OssLocalFileTools.builder()
                .ossClient(ossClient)
                .bucketName(BUCKET)
                .downloadPath(downloadRoot)
                .build();
        assertThat(noPrefix.prefix()).isEmpty();
        assertThat(noPrefix.getFullKey("a.xlsx")).isEqualTo("a.xlsx");
        assertThat(noPrefix.downloadPath()).isEqualTo(downloadRoot.toAbsolutePath().normalize());
    }

    @Test
    void defaultDownloadPathIsTmp() {
        OssLocalFileTools defaults = new OssLocalFileTools(ossClient, BUCKET);
        assertThat(defaults.downloadPath()).isEqualTo(Path.of("/tmp").toAbsolutePath().normalize());
    }

    @Test
    void downloadWritesIntoUuidDirWithOriginalName() throws Exception {
        String relative = "reports/data.csv";
        String fullKey = PREFIX + relative;
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

        when(ossClient.doesObjectExist(BUCKET, fullKey)).thenReturn(true);
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(content.length);
        when(ossClient.getObjectMetadata(BUCKET, fullKey)).thenReturn(meta);

        OSSObject ossObject = mock(OSSObject.class);
        when(ossObject.getObjectContent()).thenReturn(new ByteArrayInputStream(content));
        when(ossClient.getObject(BUCKET, fullKey)).thenReturn(ossObject);

        String localPath = tools.downloadOssFileToLocal(relative);
        Path local = Path.of(localPath);

        assertThat(local.getFileName().toString()).isEqualTo("data.csv");
        assertThat(local.getParent().getParent()).isEqualTo(downloadRoot.toAbsolutePath().normalize());
        assertThat(local.getParent().getFileName().toString()).matches("[0-9a-f]{32}");
        assertThat(Files.readString(local)).isEqualTo("a,b\n1,2\n");
        assertThat(localPath).startsWith(downloadRoot.toAbsolutePath().toString());
    }

    @Test
    void downloadMissingObjectThrows() {
        when(ossClient.doesObjectExist(eq(BUCKET), any())).thenReturn(false);
        assertThatThrownBy(() -> tools.downloadOssFileToLocal("missing.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void downloadRejectsAbsolutePath() {
        assertThatThrownBy(() -> tools.downloadOssFileToLocal("/abs.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not start with '/'");
    }

    @Test
    void downloadRejectsOversizedObject() {
        String fullKey = PREFIX + "huge.bin";
        when(ossClient.doesObjectExist(BUCKET, fullKey)).thenReturn(true);
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(60L * 1024 * 1024);
        when(ossClient.getObjectMetadata(BUCKET, fullKey)).thenReturn(meta);

        assertThatThrownBy(() -> tools.downloadOssFileToLocal("huge.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50MB");
    }

    @Test
    void uploadPutsObjectWithPrefix() throws Exception {
        Path local = downloadRoot.resolve("out.xlsx");
        Files.writeString(local, "xlsx-bytes");

        when(ossClient.putObject(eq(BUCKET), eq(PREFIX + "out/result.xlsx"), any(InputStream.class), any(ObjectMetadata.class)))
                .thenReturn(new PutObjectResult());

        String result = tools.uploadLocalFileToOss(local.toString(), "out/result.xlsx");
        assertThat(result).contains("Successfully uploaded");
        assertThat(result).contains(PREFIX + "out/result.xlsx");

        ArgumentCaptor<ObjectMetadata> metaCaptor = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(ossClient).putObject(eq(BUCKET), eq(PREFIX + "out/result.xlsx"), any(InputStream.class), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getContentLength()).isEqualTo(Files.size(local));
    }

    @Test
    void uploadMissingLocalThrows() {
        assertThatThrownBy(() -> tools.uploadLocalFileToOss(downloadRoot.resolve("nope.bin").toString(), "a.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Local file does not exist");
    }

    @Test
    void constructorRequiresOssAndBucket() {
        assertThatThrownBy(() -> new OssLocalFileTools(null, BUCKET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OssLocalFileTools(ossClient, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadEnforcesLimitWhenContentLengthMissing() throws Exception {
        String relative = "big.bin";
        String fullKey = PREFIX + relative;
        when(ossClient.doesObjectExist(BUCKET, fullKey)).thenReturn(true);
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(0); // unknown / missing
        when(ossClient.getObjectMetadata(BUCKET, fullKey)).thenReturn(meta);

        // Stream more than 50MB
        InputStream huge = new InputStream() {
            long remaining = 51L * 1024 * 1024;
            @Override
            public int read() {
                if (remaining-- <= 0) {
                    return -1;
                }
                return 1;
            }
            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, remaining);
                for (int i = 0; i < n; i++) {
                    b[off + i] = 1;
                }
                remaining -= n;
                return n;
            }
        };
        OSSObject ossObject = mock(OSSObject.class);
        when(ossObject.getObjectContent()).thenReturn(huge);
        when(ossClient.getObject(BUCKET, fullKey)).thenReturn(ossObject);

        assertThatThrownBy(() -> tools.downloadOssFileToLocal(relative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50MB");
    }

}
