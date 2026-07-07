package io.github.springai.harness.storage;

import io.github.springai.harness.storage.StorageProvider.GrepOutputMode;
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

	@Test
	@DisplayName("Should trace isDirectory and delegate")
	void shouldTraceIsDirectory() {
		when(delegate.isDirectory("src")).thenReturn(true);

		boolean result = observedStorageProvider.isDirectory("src");

		assertThat(result).isTrue();
		assertThat(startedObservations).contains("mcp.storage.isDirectory");
	}

	@Test
	@DisplayName("Should trace listDirectory and delegate")
	void shouldTraceListDirectory() throws IOException {
		when(delegate.listDirectory("src")).thenReturn(List.of());

		List<StorageProvider.Info> result = observedStorageProvider.listDirectory("src");

		assertThat(result).isEmpty();
		assertThat(startedObservations).contains("mcp.storage.listDirectory");
	}

	@Test
	@DisplayName("Should trace readAllLines and delegate")
	void shouldTraceReadAllLines() throws IOException {
		when(delegate.readAllLines("foo.txt")).thenReturn(List.of("line1"));

		List<String> result = observedStorageProvider.readAllLines("foo.txt");

		assertThat(result).containsExactly("line1");
		assertThat(startedObservations).contains("mcp.storage.readAllLines");
	}

	@Test
	@DisplayName("Should trace trash and delegate")
	void shouldTraceTrash() throws IOException {
		observedStorageProvider.trash("foo.txt");

		assertThat(startedObservations).contains("mcp.storage.trash");
		verify(delegate).trash("foo.txt");
	}

	@Test
	@DisplayName("Should trace delete and delegate")
	void shouldTraceDelete() throws IOException {
		observedStorageProvider.delete("foo.txt");

		assertThat(startedObservations).contains("mcp.storage.delete");
		verify(delegate).delete("foo.txt");
	}

	@Test
	@DisplayName("Should trace getInfo and delegate")
	void shouldTraceGetInfo() throws IOException {
		StorageProvider.Info info = new StorageProvider.Info("foo.txt", false, false, 100, 1000);
		when(delegate.getInfo("foo.txt")).thenReturn(info);

		StorageProvider.Info result = observedStorageProvider.getInfo("foo.txt");

		assertThat(result).isEqualTo(info);
		assertThat(startedObservations).contains("mcp.storage.getInfo");
	}

	@Test
	@DisplayName("Should return wrapped ObservedStorageProvider for subDirProvider")
	void shouldWrapSubDirProvider() {
		StorageProvider mockSub = mock(StorageProvider.class);
		when(delegate.subDirProvider("sub")).thenReturn(mockSub);

		StorageProvider result = observedStorageProvider.subDirProvider("sub");

		assertThat(result).isInstanceOf(ObservedStorageProvider.class);
	}

	@Test
	@DisplayName("Should propagate exception in exists")
	void shouldPropagateExceptionInExists() {
		when(delegate.exists("foo.txt")).thenThrow(new RuntimeException("Fail"));

		org.junit.jupiter.api.Assertions.assertThrows(
				RuntimeException.class,
				() -> observedStorageProvider.exists("foo.txt")
		);
	}

	@Test
	@DisplayName("Should propagate IOException in readString")
	void shouldPropagateIOExceptionInReadString() throws IOException {
		when(delegate.readString("foo.txt")).thenThrow(new IOException("Disk failure"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedStorageProvider.readString("foo.txt")
		);
		assertThat(startedObservations).contains("mcp.storage.readString");
	}

	@Test
	@DisplayName("Should propagate IOException in rename")
	void shouldPropagateIOExceptionInRename() throws IOException {
		doThrow(new IOException("Disk failure")).when(delegate).rename("old.txt", "new.txt");

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedStorageProvider.rename("old.txt", "new.txt")
		);
		assertThat(startedObservations).contains("mcp.storage.rename");
	}

	@Test
	@DisplayName("Should trace grep and delegate")
	void shouldTraceGrep() throws IOException {
		when(delegate.grep("pattern", "src", "*.txt", GrepOutputMode.content, 1, 1, null, true, false, null, null, false))
				.thenReturn(List.of("src/a.txt:1:pattern"));

		List<String> result = observedStorageProvider.grep("pattern", "src", "*.txt", GrepOutputMode.content, 1, 1, null, true, false, null, null, false);

		assertThat(result).containsExactly("src/a.txt:1:pattern");
		assertThat(startedObservations).contains("mcp.storage.grep");
	}

	@Test
	@DisplayName("Should propagate IOException in grep")
	void shouldPropagateIOExceptionInGrep() throws IOException {
		when(delegate.grep(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new IOException("Grep failure"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedStorageProvider.grep("pattern", "src", "*.txt", GrepOutputMode.content, 1, 1, null, true, false, null, null, false)
		);
		assertThat(startedObservations).contains("mcp.storage.grep");
	}

	@Test
	@DisplayName("Should propagate IOException in delete to cover observeVoid catch block")
	void shouldPropagateIOExceptionInDelete() throws IOException {
		doThrow(new IOException("Delete failure")).when(delegate).delete("foo.txt");

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> observedStorageProvider.delete("foo.txt")
		);
		assertThat(startedObservations).contains("mcp.storage.delete");
	}
}
