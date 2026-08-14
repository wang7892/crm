package cn.cordys.crm.aiagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiAgentAttachmentServiceTest {

    private final AiAgentAttachmentService service = new AiAgentAttachmentService(
            mock(AiAgentFileContentService.class),
            mock(AiAgentKnowledgeService.class),
            mock(AiAgentLlmClient.class));

    @Test
    void shouldUseAttachmentsOnlyForCurrentChatByDefault() {
        assertThat(service.resolveMode("请根据附件总结客户需求"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
    }

    @Test
    void shouldAddAttachmentsToKnowledgeForExplicitCommand() {
        assertThat(service.resolveMode("请把这些文件加入公司知识库"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.KNOWLEDGE);
        assertThat(service.resolveMode("上传这个文档到公司知识库中"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.KNOWLEDGE);
        assertThat(service.resolveMode("请把附件存到知识库里"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.KNOWLEDGE);
    }

    @Test
    void shouldNotAddAttachmentsToKnowledgeForQuestionOrNegativeCommand() {
        assertThat(service.resolveMode("这个文件适合加入知识库吗？"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
        assertThat(service.resolveMode("不要加入知识库，只在当前聊天中分析"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
        assertThat(service.resolveMode("不要把它放进知识库"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
        assertThat(service.resolveMode("要不要加入知识库？"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
        assertThat(service.resolveMode("不需要保存到知识库"))
                .isEqualTo(AiAgentAttachmentService.AttachmentMode.CHAT);
    }
}
