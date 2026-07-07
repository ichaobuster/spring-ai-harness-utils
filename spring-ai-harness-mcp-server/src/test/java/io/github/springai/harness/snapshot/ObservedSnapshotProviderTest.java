package io.github.springai.harness.snapshot;

import io.github.springai.harness.storage.StorageProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ObservedSnapshotProvider}.
 */
@DisplayName("ObservedSnapshotProvider Unit Tests")
@ExtendWith(MockitoExtension.class)
class ObservedSnapshotProviderTest {

	@Mock
	private SnapshotProvider delegate;

	@Mock
	private StorageProvider storage;

	private ObservationRegistry observationRegistry;
	private List<String> startedObservations;
	private ObservedSnapshotProvider observedSnapshotProvider;

	@BeforeEach
	void setUp() {
		observationRegistry = ObservationRegistry.create();
		startedObservations = new ArrayList<>();

		observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public void onStart(Observation.Context context) {
				startedObservations.add(context.getName());
			}

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}
		});

		observedSnapshotProvider = new ObservedSnapshotProvider(delegate, observationRegistry);
	}

	@Test
	@DisplayName("Should trace createSnapshot and delegate")
	void shouldTraceCreateSnapshot() throws IOException {
		when(delegate.createSnapshot(storage, "foo.txt", "WRITE")).thenReturn("snap123");

		String result = observedSnapshotProvider.createSnapshot(storage, "foo.txt", "WRITE");

		assertThat(result).isEqualTo("snap123");
		assertThat(startedObservations).contains("mcp.snapshot.create");
		verify(delegate).createSnapshot(storage, "foo.txt", "WRITE");
	}

	@Test
	@DisplayName("Should trace listSnapshots and delegate")
	void shouldTraceListSnapshots() throws IOException {
		when(delegate.listSnapshots(storage, "foo.txt")).thenReturn(List.of());

		List<SnapshotInfo> result = observedSnapshotProvider.listSnapshots(storage, "foo.txt");

		assertThat(result).isEmpty();
		assertThat(startedObservations).contains("mcp.snapshot.list");
		verify(delegate).listSnapshots(storage, "foo.txt");
	}
	@Test
	@DisplayName("Should trace rewind and delegate")
	void shouldTraceRewind() throws IOException {
		when(delegate.rewind(storage, "snap123")).thenReturn("Successfully rewound.");

		String result = observedSnapshotProvider.rewind(storage, "snap123");

		assertThat(result).isEqualTo("Successfully rewound.");
		assertThat(startedObservations).contains("mcp.snapshot.rewind");
		verify(delegate).rewind(storage, "snap123");
	}

	@Test
	@DisplayName("Should propagate IOException when delegate fails in createSnapshot")
	void shouldPropagateExceptionInCreateSnapshot() throws IOException {
		when(delegate.createSnapshot(storage, "foo.txt", "WRITE")).thenThrow(new IOException("Fail"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedSnapshotProvider.createSnapshot(storage, "foo.txt", "WRITE")
		);
		assertThat(startedObservations).contains("mcp.snapshot.create");
	}

	@Test
	@DisplayName("Should propagate IOException when delegate fails in listSnapshots")
	void shouldPropagateExceptionInListSnapshots() throws IOException {
		when(delegate.listSnapshots(storage, "foo.txt")).thenThrow(new IOException("Fail"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedSnapshotProvider.listSnapshots(storage, "foo.txt")
		);
		assertThat(startedObservations).contains("mcp.snapshot.list");
	}

	@Test
	@DisplayName("Should propagate IOException when delegate fails in rewind")
	void shouldPropagateExceptionInRewind() throws IOException {
		when(delegate.rewind(storage, "snap123")).thenThrow(new IOException("Fail"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedSnapshotProvider.rewind(storage, "snap123")
		);
		assertThat(startedObservations).contains("mcp.snapshot.rewind");
	}
}
