package cn.cordys.crm.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@Data
@ConfigurationProperties(prefix = "crm.task.attachment")
public class TaskAttachmentProperties {

    private String root = "./data/task-attachments";
    private DataSize maxFileSize = DataSize.ofMegabytes(50);
    private int maxFiles = 10;
}
