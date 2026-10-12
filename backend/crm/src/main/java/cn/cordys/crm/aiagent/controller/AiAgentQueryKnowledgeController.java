package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.context.OrganizationContext;
import cn.cordys.security.SessionUtils;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.*;
import cn.cordys.crm.aiagent.service.AiAgentQueryKnowledgeService;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/ai-agent/query-knowledge")
public class AiAgentQueryKnowledgeController {
    private final AiAgentQueryKnowledgeService service;
    private final cn.cordys.crm.aiagent.service.AiAgentQueryKnowledgeFiles files;
    public AiAgentQueryKnowledgeController(AiAgentQueryKnowledgeService service, cn.cordys.crm.aiagent.service.AiAgentQueryKnowledgeFiles files) {
        this.service = service; this.files = files;
    }

    @PostMapping("/reindex") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public ReindexResult reindex(@RequestBody(required = false) java.util.List<String> skip) {
        return service.reindex(OrganizationContext.getOrganizationId(), SessionUtils.getUserId(), skip == null ? java.util.List.of() : skip);
    }

    @GetMapping @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public List<Item> list(@RequestParam String kind) {
        return service.list(kind, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public Item save(@RequestBody Item item) {
        return service.save(item, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/{id}/enabled") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public Item enable(@PathVariable String id, @RequestBody Change change) {
        return service.enable(id, change, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/delete") @RequiresPermissions(PermissionConstants.AGENT_DELETE)
    public void delete(@RequestBody List<Removal> removals) {
        service.delete(removals, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/search") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public Matches search(@RequestBody Search search) {
        if (search == null || search.question() == null || search.question().isBlank() || search.question().length() > 2000)
            throw new IllegalArgumentException("请输入 1 到 2000 字符的问题");
        return service.retrieve(search.question(), search.datasourceId(), OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @GetMapping("/export") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestParam String kind,
            @RequestParam(required = false) List<String> ids, @RequestParam(defaultValue = "false") boolean template) {
        byte[] body = files.export(kind, ids, template, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=query-knowledge.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @PostMapping("/import") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public java.util.Map<String, Integer> importFile(@RequestParam String kind,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return java.util.Map.of("imported", files.importFile(kind, file, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()));
    }
}
