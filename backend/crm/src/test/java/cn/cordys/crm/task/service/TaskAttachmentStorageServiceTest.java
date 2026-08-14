package cn.cordys.crm.task.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.crm.task.config.TaskAttachmentProperties;
import cn.cordys.crm.task.constants.TaskAttachmentScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAttachmentStorageServiceTest {

    Path tempDir;

    @BeforeEach
    void setUp() {
        tempDir = Path.of("target", "task-test-files", UUID.randomUUID().toString());
    }

    @Test
    void shouldStoreFileInTaskScopedPathAndCalculateSha256() throws Exception {
        TaskAttachmentProperties properties = new TaskAttachmentProperties();
        properties.setRoot(tempDir.toString());
        TaskAttachmentStorageService service = new TaskAttachmentStorageService(properties);
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        TaskAttachmentStorageService.StoredFile stored = service.store(
                file, "org-1", "task-1", TaskAttachmentScene.TASK, "attachment-1");

        assertThat(stored.storagePath()).isEqualTo("org-1/task-1/task/attachment-1.txt");
        assertThat(stored.sha256Hex())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(Files.readString(tempDir.resolve(stored.storagePath()))).isEqualTo("hello");
    }

    @Test
    void shouldEnforceDefaultFiftyMegabyteLimit() {
        TaskAttachmentStorageService service = new TaskAttachmentStorageService(new TaskAttachmentProperties());
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(50L * 1024 * 1024 + 1);
        when(oversized.getOriginalFilename()).thenReturn("large.bin");

        assertThatThrownBy(() -> service.validateFiles(Collections.singletonList(oversized)))
                .isInstanceOf(GenericException.class);
    }

    @Test
    void shouldEnforceDefaultTenFileLimit() {
        TaskAttachmentStorageService service = new TaskAttachmentStorageService(new TaskAttachmentProperties());
        MultipartFile file = mock(MultipartFile.class);

        assertThatThrownBy(() -> service.validateFiles(Collections.nCopies(11, file)))
                .isInstanceOf(GenericException.class);
    }
}
