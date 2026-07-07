package io.github.springai.harness.snapshot;

import io.github.springai.harness.storage.StorageProvider;

import java.io.IOException;
import java.util.List;

/**
 * SnapshotProvider interface for creating, listing, and restoring file snapshots.
 *
 * @author ichaobuster
 */
public interface SnapshotProvider {

	/**
	 * Creates a pre-operation snapshot of the file before a modifying operation if the file exists.
	 *
	 * @param storage  the storage provider
	 * @param filePath relative target file path in workspace
	 * @param action   action type (WRITE, EDIT, TRASH)
	 * @return snapshotId if snapshot created, or null if file did not exist
	 * @throws IOException if storage operation fails
	 */
	String createSnapshot(StorageProvider storage, String filePath, String action) throws IOException;

	/**
	 * Lists recent file snapshots in the workspace.
	 *
	 * @param storage  the storage provider
	 * @param filePath optional target file path to filter by (or null/empty for all)
	 * @return list of snapshot info
	 * @throws IOException if storage operation fails
	 */
	List<SnapshotInfo> listSnapshots(StorageProvider storage, String filePath) throws IOException;

	/**
	 * Rewinds (restores) a file to a specified snapshot state.
	 *
	 * @param storage    the storage provider
	 * @param snapshotId the snapshot ID to restore
	 * @return success or error description message
	 * @throws IOException if storage operation fails
	 */
	String rewind(StorageProvider storage, String snapshotId) throws IOException;
}
