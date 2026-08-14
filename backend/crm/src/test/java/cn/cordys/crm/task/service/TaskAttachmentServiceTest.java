package cn.cordys.crm.task.service;

import cn.cordys.common.uid.IDGenerator;
import cn.cordys.crm.task.config.TaskAttachmentProperties;
import cn.cordys.crm.task.constants.TaskAttachmentScene;
import cn.cordys.crm.task.constants.TaskStatus;
import cn.cordys.crm.task.domain.CrmTask;
import cn.cordys.crm.task.domain.CrmTaskAttachment;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAttachmentServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeleteStoredFileWhenDatabaseInsertFails() {
        BaseMapper<CrmTaskAttachment> mapper = mock(BaseMapper.class);
        TaskService taskService = mock(TaskService.class);
        TaskAttachmentStorageService storageService = mock(TaskAttachmentStorageService.class);
        TaskAttachmentProperties properties = new TaskAttachmentProperties();
        TaskAttachmentService service = new TaskAttachmentService();
        ReflectionTestUtils.setField(service, "taskAttachmentMapper", mapper);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        ReflectionTestUtils.setField(service, "storageService", storageService);
        ReflectionTestUtils.setField(service, "properties", properties);

        CrmTask task = new CrmTask();
        task.setId("task-1");
        task.setStatus(TaskStatus.PENDING.name());
        when(taskService.getTask("task-1", "org-1")).thenReturn(task);
        when(mapper.countByExample(any(CrmTaskAttachment.class))).thenReturn(0L);
        when(storageService.store(any(), eq("org-1"), eq("task-1"), eq(TaskAttachmentScene.TASK), any()))
                .thenReturn(new TaskAttachmentStorageService.StoredFile(
                        "org-1/task-1/task/attachment-1.txt", "note.txt", "text/plain", 5,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).insert(any(CrmTaskAttachment.class));
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("attachment-1");
            assertThatThrownBy(() -> service.upload("task-1", TaskAttachmentScene.TASK, List.of(file),
                    "manager-1", "org-1", true, false))
                    .isInstanceOf(IllegalStateException.class);
        }

        verify(storageService).delete("org-1/task-1/task/attachment-1.txt");
    }
}
