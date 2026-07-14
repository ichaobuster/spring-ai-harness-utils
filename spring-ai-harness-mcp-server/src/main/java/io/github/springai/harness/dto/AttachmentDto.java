package io.github.springai.harness.dto;

/**
 * DTO representing an uploaded file attachment.
 *
 * @param attachmentId   the unique UUID for this attachment
 * @param conversationId the conversation identifier (uses 'default' if not specified)
 * @param fileName       the sanitized original file name
 * @param path           the storage relative path of the attachment
 * @param size           the file size in bytes
 * @param lastModified   the upload timestamp in milliseconds
 * @author ichaobuster
 */
public record AttachmentDto(
		String attachmentId,
		String conversationId,
		String fileName,
		String path,
		long size,
		long lastModified
) {
}
