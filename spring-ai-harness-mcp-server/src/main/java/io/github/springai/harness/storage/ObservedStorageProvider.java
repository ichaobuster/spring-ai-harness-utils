package io.github.springai.harness.storage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.util.List;

/**
 * Decorator for {@link StorageProvider} that wraps all operations with Micrometer Observations.
 *
 * @author ichaobuster
 */
public class ObservedStorageProvider implements StorageProvider {

	private final StorageProvider delegate;
	private final ObservationRegistry observationRegistry;

	public ObservedStorageProvider(StorageProvider delegate, ObservationRegistry observationRegistry) {
		this.delegate = delegate;
		this.observationRegistry = observationRegistry;
	}

	private interface StorageOperation<T> {
		T execute() throws IOException;
	}

	private interface StorageVoidOperation {
		void execute() throws IOException;
	}

	private <T> T observe(String operationName, String path, StorageOperation<T> op) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.storage." + operationName, observationRegistry)
				.lowCardinalityKeyValue("path", path != null ? path : "null");
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			T result = op.execute();
			observation.stop();
			return result;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Storage operation failed: " + operationName, e);
		}
	}

	private void observeVoid(String operationName, String path, StorageVoidOperation op) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.storage." + operationName, observationRegistry)
				.lowCardinalityKeyValue("path", path != null ? path : "null");
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			op.execute();
			observation.stop();
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Storage operation failed: " + operationName, e);
		}
	}

	@Override
	public StorageProvider subDirProvider(String subDir) {
		StorageProvider subDelegate = delegate.subDirProvider(subDir);
		return new ObservedStorageProvider(subDelegate, observationRegistry);
	}

	@Override
	public boolean exists(String path) {
		try {
			return observe("exists", path, () -> delegate.exists(path));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isDirectory(String path) {
		try {
			return observe("isDirectory", path, () -> delegate.isDirectory(path));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Info> listDirectory(String path) throws IOException {
		return observe("listDirectory", path, () -> delegate.listDirectory(path));
	}

	@Override
	public String readString(String path) throws IOException {
		return observe("readString", path, () -> delegate.readString(path));
	}

	@Override
	public List<String> readAllLines(String path) throws IOException {
		return observe("readAllLines", path, () -> delegate.readAllLines(path));
	}

	@Override
	public void writeString(String path, String content) throws IOException {
		observeVoid("writeString", path, () -> delegate.writeString(path, content));
	}

	@Override
	public void trash(String path) throws IOException {
		observeVoid("trash", path, () -> delegate.trash(path));
	}

	@Override
	public void delete(String path) throws IOException {
		observeVoid("delete", path, () -> delegate.delete(path));
	}

	@Override
	public void rename(String oldPath, String newPath) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.storage.rename", observationRegistry)
				.lowCardinalityKeyValue("oldPath", oldPath)
				.lowCardinalityKeyValue("newPath", newPath);
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			delegate.rename(oldPath, newPath);
			observation.stop();
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Storage operation failed: rename", e);
		}
	}

	@Override
	public List<String> glob(String pattern, String path) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.storage.glob", observationRegistry)
				.lowCardinalityKeyValue("pattern", pattern)
				.lowCardinalityKeyValue("path", path);
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			List<String> result = delegate.glob(pattern, path);
			observation.stop();
			return result;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Storage operation failed: glob", e);
		}
	}

	@Override
	public List<String> grep(String pattern, String path, String glob, GrepOutputMode outputMode, Integer contextBefore, Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive, Integer headLimit, Integer offset, Boolean multiline) throws IOException {
		Observation observation = Observation.createNotStarted("mcp.storage.grep", observationRegistry)
				.lowCardinalityKeyValue("pattern", pattern)
				.lowCardinalityKeyValue("path", path);
		observation.start();
		try (Observation.Scope scope = observation.openScope()) {
			List<String> result = delegate.grep(pattern, path, glob, outputMode, contextBefore, contextAfter, context, showLineNumbers, caseInsensitive, headLimit, offset, multiline);
			observation.stop();
			return result;
		} catch (Exception e) {
			observation.error(e);
			observation.stop();
			if (e instanceof IOException) {
				throw (IOException) e;
			}
			throw new IOException("Storage operation failed: grep", e);
		}
	}

	@Override
	public Info getInfo(String path) throws IOException {
		return observe("getInfo", path, () -> delegate.getInfo(path));
	}
}
