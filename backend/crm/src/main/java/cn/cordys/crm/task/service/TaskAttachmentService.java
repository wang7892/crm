package cn.cordys.crm.task.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.util.BeanUtils;
import cn.cordys.crm.task.config.TaskAttachmentProperties;
import cn.cordys.crm.task.constants.TaskAttachmentScene;
import cn.cordys.crm.task.constants.TaskStatus;
import cn.cordys.crm.task.domain.CrmTask;
import cn.cordys.crm.task.domain.CrmTaskAttachment;
import cn.cordys.crm.task.dto.response.TaskAttachmentResponse;
import cn.cordys.mybatis.BaseMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class TaskAttachmentService {

    @Resource
    private BaseMapper<CrmTaskAttachment> taskAttachmentMapper;
    @Resource
    private TaskService taskService;
    @Resource
    private TaskAttachmentStorageService storageService;
    @Resource
    private TaskAttachmentProperties properties;

    public List<TaskAttachmentResponse> upload(String taskId, TaskAttachmentScene scene, List<MultipartFile> files,
                                                String userId, String organizationId,
                                                boolean manager, boolean executor) {
        CrmTask task = checkScenePermission(taskId, scene, userId, organizationId, manager, executor, true);
        storageService.validateFiles(files);
        CrmTaskAttachment criteria = new CrmTaskAttachment();
        criteria.setTaskId(taskId);
        criteria.setOrganizationId(organizationId);
        criteria.setScene(scene.name());
        long existingCount = taskAttachmentMapper.countByExample(criteria);
        if (existingCount + files.size() > properties.getMaxFiles()) {
            throw new GenericException("每个任务场景最多保留 " + properties.getMaxFiles() + " 个附件");
        }

        List<CrmTaskAttachment> storedAttachments = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                String attachmentId = IDGenerator.nextStr();
                TaskAttachmentStorageService.StoredFile stored = storageService.store(file, organizationId,
                        task.getId(), scene, attachmentId);
                long now = System.currentTimeMillis();
                CrmTaskAttachment attachment = new CrmTaskAttachment();
                attachment.setId(attachmentId);
                attachment.setTaskId(task.getId());
                attachment.setOrganizationId(organizationId);
                attachment.setScene(scene.name());
                attachment.setStoragePath(stored.storagePath());
                attachment.setOriginalName(stored.originalName());
                attachment.setContentType(stored.contentType());
                attachment.setSizeBytes(stored.sizeBytes());
                attachment.setSha256Hex(stored.sha256Hex());
                attachment.setCreateTime(now);
                attachment.setUpdateTime(now);
                attachment.setCreateUser(userId);
                attachment.setUpdateUser(userId);
                storedAttachments.add(attachment);
                taskAttachmentMapper.insert(attachment);
            }
        } catch (RuntimeException e) {
            storedAttachments.forEach(attachment -> {
                try {
                    storageService.delete(attachment.getStoragePath());
                } catch (RuntimeException cleanupException) {
                    log.warn("Failed to clean up task attachment after upload failure, attachmentId={}, path={}",
                            attachment.getId(), attachment.getStoragePath(), cleanupException);
                }
            });
            throw e;
        }
        return storedAttachments.stream().map(this::toResponse).toList();
    }

    public void delete(String attachmentId, String userId, String organizationId,
                       boolean manager, boolean executor) {
        CrmTaskAttachment attachment = getAttachment(attachmentId, organizationId);
        TaskAttachmentScene scene = TaskAttachmentScene.parse(attachment.getScene());
        checkScenePermission(attachment.getTaskId(), scene, userId, organizationId, manager, executor, true);
        taskAttachmentMapper.deleteByPrimaryKey(attachment.getId());
        storageService.delete(attachment.getStoragePath());
    }

    @Transactional(readOnly = true)
    public ResponseEntity<org.springframework.core.io.Resource> resource(String attachmentId, boolean inline,
                                                                        String userId, String organizationId,
                                                                        boolean manager) {
        CrmTaskAttachment attachment = getAttachment(attachmentId, organizationId);
        taskService.getAccessibleTask(attachment.getTaskId(), userId, organizationId, manager);
        return storageService.resource(attachment.getStoragePath(), attachment.getOriginalName(),
                attachment.getContentType(), attachment.getSizeBytes(), inline);
    }

    private CrmTask checkScenePermission(String taskId, TaskAttachmentScene scene, String userId,
                                         String organizationId, boolean manager, boolean executor,
                                         boolean requireMutable) {
        CrmTask task;
        if (scene == TaskAttachmentScene.TASK) {
            if (!manager) {
                throw new GenericException("只有管理员或销售经理可以修改任务附件");
            }
            task = taskService.getTask(taskId, organizationId);
        } else {
            if (!executor) {
                throw new GenericException("当前账号没有任务执行权限");
            }
            task = taskService.getExecutableTask(taskId, userId, organizationId, manager);
            if (requireMutable && Objects.equals(task.getStatus(), TaskStatus.COMPLETED.name())) {
                throw new GenericException("已完成任务不能修改汇报附件");
            }
        }
        return task;
    }

    private CrmTaskAttachment getAttachment(String attachmentId, String organizationId) {
        CrmTaskAttachment attachment = taskAttachmentMapper.selectByPrimaryKey(attachmentId);
        if (attachment == null || !Objects.equals(attachment.getOrganizationId(), organizationId)) {
            throw new GenericException("任务附件不存在");
        }
        return attachment;
    }

    private TaskAttachmentResponse toResponse(CrmTaskAttachment attachment) {
        return BeanUtils.copyBean(new TaskAttachmentResponse(), attachment);
    }
}
