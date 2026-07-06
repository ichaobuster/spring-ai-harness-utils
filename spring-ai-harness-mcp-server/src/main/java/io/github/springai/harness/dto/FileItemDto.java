package io.github.springai.harness.dto;

/**
 * DTO representing a file or directory item in a workspace.
 *
 * @param path         relative path of the file or directory
 * @param isDirectory  whether the item is a directory
 * @param size         size in bytes
 * @param lastModified last modified timestamp in millis
 * @author buyc
 */
public record FileItemDto(
		String path,
		boolean isDirectory,
		long size,
		long lastModified
) {
}
