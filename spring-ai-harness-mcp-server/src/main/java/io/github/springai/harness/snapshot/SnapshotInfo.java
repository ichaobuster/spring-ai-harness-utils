package io.github.springai.harness.snapshot;

/**
 * Metadata record for a file snapshot.
 *
 * @param snapshotId   unique identifier of the snapshot (timestamp based)
 * @param filePath     relative file path in workspace that was snapshotted
 * @param action       action that triggered the snapshot (e.g. WRITE, EDIT, TRASH)
 * @param snapshotPath full storage path to the snapshot object
 * @param timestamp    creation timestamp in epoch millis
 * @author ichaobuster
 */
public record SnapshotInfo(
		String snapshotId,
		String filePath,
		String action,
		String snapshotPath,
		long timestamp
) {
}
