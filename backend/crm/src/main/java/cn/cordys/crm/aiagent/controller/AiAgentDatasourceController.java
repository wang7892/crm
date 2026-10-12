package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.context.OrganizationContext;
import cn.cordys.security.SessionUtils;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.service.AiAgentDatasourceService;
import jakarta.validation.Valid;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/ai-agent/datasources")
public class AiAgentDatasourceController {
    private final AiAgentDatasourceService service;
    public AiAgentDatasourceController(AiAgentDatasourceService service) { this.service = service; }

    @GetMapping @RequiresPermissions(PermissionConstants.AGENT_READ)
    public List<View> list() { return service.list(OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }

    @GetMapping("/{id}") @RequiresPermissions(PermissionConstants.AGENT_READ)
    public View get(@PathVariable String id) { return service.get(id, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }

    @PostMapping("/test") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public void test(@Valid @RequestBody Config c) { service.test(c, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }

    @PostMapping("/tables") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public List<Table> tables(@Valid @RequestBody Config c) { return service.tables(c, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }

    @PostMapping @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public View save(@Valid @RequestBody Config c) { return service.save(c, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }

    @PostMapping("/upload") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public View upload(@RequestParam String name, @RequestParam(defaultValue = "UTF-8") String encoding,
                       @RequestPart("file") MultipartFile file) {
        return service.upload(name, file, encoding, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @DeleteMapping("/{id}") @RequiresPermissions(PermissionConstants.AGENT_UPDATE)
    public void delete(@PathVariable String id) { service.delete(id, OrganizationContext.getOrganizationId(), SessionUtils.getUserId()); }
}
