package io.github.springai.harness.snapshot;

import io.github.springai.harness.storage.StorageProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.util.List;

/**
 * Decorator for {@link SnapshotProvider} that wraps all operations with Micrometer Observations.
 *
 * @author buyc
 */
public class ObservedSnapshotProvider implements SnapshotProvider {

	private final SnapshotProvider delegate;
	private final ObservationRegistry observationRegistry;

	public ObservedSnapshotProvider(SnapshotProvider delegate, ObservationRegistry observationRegistry) {
		this.delegate = delegate;
		this.observationRegistry = observationRegistry;
	}

	@Override
	public String createSnapshot(StorageProvider storage, String filePath, String action) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.snapshot.create", observationRegistry)
				.lowCardinalityKeyValue("filePath", filePath)
				.lowCardinalityKeyValue("action", action);
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			String snapshotId = delegate.createSnapshot(storage, filePath, action);
			observation.highCardinalityKeyValue("snapshotId", snapshotId != null ? snapshotId : "null");
			observation.stop();
			return snapshotId;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Failed to create snapshot", e);
		}
	}

	@Override
	public List<SnapshotInfo> listSnapshots(StorageProvider storage, String filterFilePath) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.snapshot.list", observationRegistry)
				.lowCardinalityKeyValue("filterFilePath", filterFilePath != null ? filterFilePath : "all");
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			List<SnapshotInfo> result = delegate.listSnapshots(storage, filterFilePath);
			observation.stop();
			return result;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Failed to list snapshots", e);
		}
	}

	@Override
	public String rewind(StorageProvider storage, String snapshotId) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.snapshot.rewind", observationRegistry)
				.lowCardinalityKeyValue("snapshotId", snapshotId);
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			String result = delegate.rewind(storage, snapshotId);
			observation.stop();
			return result;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Failed to rewind snapshot", e);
		}
	}
}
