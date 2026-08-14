package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.pager.Pager;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeChunkPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeDocumentPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeSearchTestRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleBatchReviewRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRulePageRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleReviewRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleSaveRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleVersionPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleVersionSwitchRequest;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeChunkResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeDocumentResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.response.AiSemanticRuleResponse;
import cn.cordys.crm.aiagent.dto.response.AiSemanticSchemaOptionsResponse;
import cn.cordys.crm.aiagent.service.AiAgentKnowledgeService;
import cn.cordys.crm.aiagent.service.AiAgentSemanticRuleService;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "AI Agent 公司文档知识库")
@Validated
@RestController
@RequestMapping("/ai-agent/knowledge/document")
public class AiAgentKnowledgeController {

    @Resource
    private AiAgentKnowledgeService aiAgentKnowledgeService;
    @Resource
    private AiAgentSemanticRuleService aiAgentSemanticRuleService;

    @PostMapping("/page")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "文档分页")
    public Pager<List<AiKnowledgeDocumentResponse>> pageDocuments(
            @Validated @RequestBody AiKnowledgeDocumentPageRequest request) {
        return aiAgentKnowledgeService.pageDocuments(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/upload")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "上传知识文档")
    public AiKnowledgeDocumentResponse upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "remark", required = false) String remark) {
        return aiAgentKnowledgeService.uploadDocument(
                file,
                remark,
                OrganizationContext.getOrganizationId(),
                SessionUtils.getUserId()
        );
    }

    @GetMapping("/detail/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "文档详情")
    public AiKnowledgeDocumentResponse detail(@PathVariable String id) {
        return aiAgentKnowledgeService.getDocument(id, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/chunk/page")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "文档切片分页")
    public Pager<List<AiKnowledgeChunkResponse>> pageChunks(@Valid @RequestBody AiKnowledgeChunkPageRequest request) {
        return aiAgentKnowledgeService.pageChunks(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/reparse/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "重新解析文档")
    public void reparse(@PathVariable String id) {
        aiAgentKnowledgeService.reparseDocument(id, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/enable/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "启用文档")
    public void enable(@PathVariable String id) {
        aiAgentKnowledgeService.setEnabled(id, OrganizationContext.getOrganizationId(), SessionUtils.getUserId(), true);
    }

    @PostMapping("/disable/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "停用文档")
    public void disable(@PathVariable String id) {
        aiAgentKnowledgeService.setEnabled(id, OrganizationContext.getOrganizationId(), SessionUtils.getUserId(), false);
    }

    @PostMapping("/delete/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_DELETE)
    @Operation(summary = "删除文档")
    public void delete(@PathVariable String id) {
        aiAgentKnowledgeService.deleteDocument(id, OrganizationContext.getOrganizationId());
    }

    @GetMapping("/download/{id}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "下载原文档")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String id) {
        return aiAgentKnowledgeService.downloadDocument(id, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/search-test")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "知识检索测试")
    public AiKnowledgeSearchTestResponse searchTest(@Valid @RequestBody AiKnowledgeSearchTestRequest request) {
        return aiAgentKnowledgeService.searchTest(
                request.getQuestion(),
                request.getTopK(),
                request.getMode(),
                OrganizationContext.getOrganizationId()
        );
    }

    @PostMapping("/semantic-rule/page")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "语义规则分页")
    public Pager<List<AiSemanticRuleResponse>> pageSemanticRules(
            @Valid @RequestBody AiSemanticRulePageRequest request) {
        return aiAgentSemanticRuleService.pageRules(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/semantic-rule/save/{chunkId}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "保存语义规则")
    public AiSemanticRuleResponse saveSemanticRule(@PathVariable String chunkId,
                                                   @Valid @RequestBody AiSemanticRuleSaveRequest request) {
        return aiAgentSemanticRuleService.saveRule(
                chunkId, request, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/semantic-rule/review/{chunkId}")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "审核语义规则")
    public AiSemanticRuleResponse reviewSemanticRule(@PathVariable String chunkId,
                                                     @Valid @RequestBody AiSemanticRuleReviewRequest request) {
        return aiAgentSemanticRuleService.reviewRule(
                chunkId, request, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/semantic-rule/review/batch")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "批量审核语义规则")
    public List<AiSemanticRuleResponse> reviewSemanticRules(
            @Valid @RequestBody AiSemanticRuleBatchReviewRequest request) {
        return aiAgentSemanticRuleService.reviewRules(
                request, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @GetMapping("/semantic-rule/schema-options")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "语义规则可选实体和字段")
    public AiSemanticSchemaOptionsResponse semanticSchemaOptions() {
        return aiAgentSemanticRuleService.schemaOptions();
    }

    @PostMapping("/semantic-rule/version/page")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "语义规则版本分页")
    public Pager<List<AiSemanticRuleResponse>> pageSemanticRuleVersions(
            @Valid @RequestBody AiSemanticRuleVersionPageRequest request) {
        return aiAgentSemanticRuleService.pageVersions(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/semantic-rule/version/switch")
    @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    @Operation(summary = "切换语义规则版本")
    public AiSemanticRuleResponse switchSemanticRuleVersion(
            @Valid @RequestBody AiSemanticRuleVersionSwitchRequest request) {
        return aiAgentSemanticRuleService.switchVersion(
                request, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }
}
