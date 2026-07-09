package io.github.springai.harness.storage;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QuotaManager}.
 */
@DisplayName("QuotaManager Unit Tests")
@ExtendWith(MockitoExtension.class)
class QuotaManagerTest {

	@Mock
	private StorageProvider storage;

	private HarnessMcpServerProperties.QuotaProperties quotaProperties;

	private QuotaManager quotaManager;

	@BeforeEach
	void setUp() {
		quotaProperties = new HarnessMcpServerProperties.QuotaProperties();
		quotaProperties.setMaxBytes(1000L);
		quotaProperties.setMetaFile(".storage");
		quotaProperties.setRecalculationInterval(Duration.ofHours(24));
		quotaProperties.setIncludeSnapshots(false);
		quotaProperties.setIncludeTrash(true);

		quotaManager = new QuotaManager(quotaProperties);
	}

	@Test
	@DisplayName("Should trigger full recalculation when meta file does not exist")
	void shouldRecalculateWhenMetaFileDoesNotExist() throws IOException {
		when(storage.exists(".storage")).thenReturn(false);
		when(storage.calculateTotalSize(anyList())).thenReturn(456L);

		long used = quotaManager.getUsedBytes(storage);

		assertThat(used).isEqualTo(456L);
		verify(storage).calculateTotalSize(List.of(".snapshots/", ".shadow/", ".storage"));
		verify(storage).writeString(eq(".storage"), contains("usedBytes=456"));
	}

	@Test
	@DisplayName("Should return cached bytes when meta file exists and is not expired")
	void shouldReturnCachedBytes() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		long now = System.currentTimeMillis();
		String content = "usedBytes=123\ncalculatedAt=" + now + "\n";
		when(storage.readString(".storage")).thenReturn(content);

		long used = quotaManager.getUsedBytes(storage);

		assertThat(used).isEqualTo(123L);
		verify(storage, never()).calculateTotalSize(anyList());
	}

	@Test
	@DisplayName("Should recalculate when meta file exists but is expired")
	void shouldRecalculateWhenExpired() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		// calculated 25 hours ago (limit is 24 hours)
		long oldTime = System.currentTimeMillis() - Duration.ofHours(25).toMillis();
		String content = "usedBytes=123\ncalculatedAt=" + oldTime + "\n";
		when(storage.readString(".storage")).thenReturn(content);
		when(storage.calculateTotalSize(anyList())).thenReturn(789L);

		long used = quotaManager.getUsedBytes(storage);

		assertThat(used).isEqualTo(789L);
		verify(storage).calculateTotalSize(List.of(".snapshots/", ".shadow/", ".storage"));
		verify(storage).writeString(eq(".storage"), contains("usedBytes=789"));
	}

	@Test
	@DisplayName("Should recalculate when meta file is corrupted")
	void shouldRecalculateWhenCorrupted() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		when(storage.readString(".storage")).thenReturn("corrupt-content");
		when(storage.calculateTotalSize(anyList())).thenReturn(789L);

		long used = quotaManager.getUsedBytes(storage);

		assertThat(used).isEqualTo(789L);
		verify(storage).calculateTotalSize(anyList());
	}

	@Test
	@DisplayName("Should pass quota check when capacity is sufficient")
	void shouldPassQuotaCheck() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		String content = "usedBytes=500\ncalculatedAt=" + System.currentTimeMillis() + "\n";
		when(storage.readString(".storage")).thenReturn(content);

		// delta 400, limit 1000, current 500 => total 900 <= 1000
		quotaManager.checkQuota(storage, 400L);
	}

	@Test
	@DisplayName("Should throw QuotaExceededException when capacity is exceeded")
	void shouldThrowOnExceeded() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		String content = "usedBytes=500\ncalculatedAt=" + System.currentTimeMillis() + "\n";
		when(storage.readString(".storage")).thenReturn(content);

		// delta 600, limit 1000, current 500 => total 1100 > 1000
		assertThatThrownBy(() -> quotaManager.checkQuota(storage, 600L))
				.isInstanceOf(QuotaExceededException.class)
				.hasMessageContaining("Storage quota exceeded")
				.satisfies(e -> {
					QuotaExceededException ex = (QuotaExceededException) e;
					assertThat(ex.getUsedBytes()).isEqualTo(500L);
					assertThat(ex.getMaxBytes()).isEqualTo(1000L);
					assertThat(ex.getRequiredBytes()).isEqualTo(600L);
				});
	}

	@Test
	@DisplayName("Should update used bytes and write back to meta file")
	void shouldUpdateUsedBytes() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		String content = "usedBytes=500\ncalculatedAt=" + System.currentTimeMillis() + "\n";
		when(storage.readString(".storage")).thenReturn(content);

		quotaManager.updateUsedBytes(storage, 150L);

		verify(storage).writeString(eq(".storage"), contains("usedBytes=650"));
	}

	@Test
	@DisplayName("Should not decrease used bytes below zero")
	void shouldNotDecreaseBelowZero() throws IOException {
		when(storage.exists(".storage")).thenReturn(true);
		when(storage.isDirectory(".storage")).thenReturn(false);
		String content = "usedBytes=100\ncalculatedAt=" + System.currentTimeMillis() + "\n";
		when(storage.readString(".storage")).thenReturn(content);

		quotaManager.updateUsedBytes(storage, -150L);

		verify(storage).writeString(eq(".storage"), contains("usedBytes=0"));
	}
}
