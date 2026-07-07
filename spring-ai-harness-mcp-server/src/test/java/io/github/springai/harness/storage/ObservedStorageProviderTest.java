package io.github.springai.harness.storage;

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
 * Unit tests for {@link ObservedStorageProvider}.
 */
@DisplayName("ObservedStorageProvider Unit Tests")
@ExtendWith(MockitoExtension.class)
class ObservedStorageProviderTest {

	@Mock
	private StorageProvider delegate;

	private ObservationRegistry observationRegistry;
	private List<String> startedObservations;
	private ObservedStorageProvider observedStorageProvider;

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

		observedStorageProvider = new ObservedStorageProvider(delegate, observationRegistry);
	}

	@Test
	@DisplayName("Should trace exists and delegate")
	void shouldTraceExists() {
		when(delegate.exists("foo.txt")).thenReturn(true);

		boolean result = observedStorageProvider.exists("foo.txt");

		assertThat(result).isTrue();
		assertThat(startedObservations).contains("mcp.storage.exists");
		verify(delegate).exists("foo.txt");
	}

	@Test
	@DisplayName("Should trace writeString and delegate")
	void shouldTraceWriteString() throws IOException {
		observedStorageProvider.writeString("foo.txt", "hello");

		assertThat(startedObservations).contains("mcp.storage.writeString");
		verify(delegate).writeString("foo.txt", "hello");
	}

	@Test
	@DisplayName("Should trace readString and delegate")
	void shouldTraceReadString() throws IOException {
		when(delegate.readString("foo.txt")).thenReturn("hello");

		String result = observedStorageProvider.readString("foo.txt");

		assertThat(result).isEqualTo("hello");
		assertThat(startedObservations).contains("mcp.storage.readString");
		verify(delegate).readString("foo.txt");
	}

	@Test
	@DisplayName("Should trace rename and delegate")
	void shouldTraceRename() throws IOException {
		observedStorageProvider.rename("old.txt", "new.txt");

		assertThat(startedObservations).contains("mcp.storage.rename");
		verify(delegate).rename("old.txt", "new.txt");
	}

	@Test
	@DisplayName("Should trace glob and delegate")
	void shouldTraceGlob() throws IOException {
		when(delegate.glob("*.txt", "src")).thenReturn(List.of("src/a.txt"));

		List<String> result = observedStorageProvider.glob("*.txt", "src");

		assertThat(result).containsExactly("src/a.txt");
		assertThat(startedObservations).contains("mcp.storage.glob");
		verify(delegate).glob("*.txt", "src");
	}
}
