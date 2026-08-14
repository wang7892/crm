package cn.cordys.crm.task.constants;

import cn.cordys.common.exception.GenericException;

import java.util.Locale;

public enum TaskAttachmentScene {
    TASK,
    REPORT;

    public static TaskAttachmentScene parse(String value) {
        try {
            return TaskAttachmentScene.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new GenericException("不支持的任务附件场景");
        }
    }
}
