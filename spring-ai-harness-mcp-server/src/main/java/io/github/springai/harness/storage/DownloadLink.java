package io.github.springai.harness.storage;

import java.net.URI;
import java.util.Date;

/**
 * 封装临时文件下载链接信息的 Record。
 *
 * @author ichaobuster
 */
public record DownloadLink(
		URI url,
		Date expiresAt,
		String fileName,
		long size
) {
}
