package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentQueryPermissionServiceTest {

    private final AiAgentSemanticSchemaService schemaService = new AiAgentSemanticSchemaService();

    @Test
    void shouldDenyExternalContractWhenPermissionIsMissing() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        AiAgentContext context = context(null);
        DeptDataPermissionDTO customerAll = new DeptDataPermissionDTO();
        customerAll.setAll(true);
        context.setDataPermission(customerAll);

        AiAgentQueryPermissionService.PermissionSql permission = service.build(contractInfo(), context);

        assertThat(permission.whereSql()).contains("AND 1 = 0");
        verify(mapper, never()).findUserNamesByDataPermission(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotUseCustomerPermissionWhenOrderPermissionIsMissing() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        AiAgentContext context = new AiAgentContext();
        context.setUserId("user-1");
        context.setOrganizationId("org-1");
        DeptDataPermissionDTO customerAll = new DeptDataPermissionDTO();
        customerAll.setAll(true);
        context.setDataPermission(customerAll);

        AiAgentQueryPermissionService.PermissionSql permission = service.build(
                schemaService.findEntity("sales_order").orElseThrow(), context);

        assertThat(permission.whereSql()).contains("AND 1 = 0");
    }

    @Test
    void shouldDenyCustomerQueryWhenResolvedPermissionIsExplicitlyEmpty() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        AiAgentContext context = new AiAgentContext();
        context.setUserId("user-1");
        context.setOrganizationId("org-1");
        context.setDataPermission(new DeptDataPermissionDTO());

        AiAgentQueryPermissionService.PermissionSql permission = service.build(
                schemaService.findEntity("customer").orElseThrow(), context);

        assertThat(permission.whereSql()).contains("AND 1 = 0");
    }

    @Test
    void shouldAllowAllOnlyWhenResolvedPermissionExplicitlyAllowsAll() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        DeptDataPermissionDTO all = new DeptDataPermissionDTO();
        all.setAll(true);

        AiAgentQueryPermissionService.PermissionSql permission = service.build(contractInfo(), context(all));

        assertThat(permission.whereSql()).isEmpty();
    }

    @Test
    void shouldKeepOrganizationIsolationWithoutAddingUserScopeForFullAgentContext() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        DeptDataPermissionDTO all = new DeptDataPermissionDTO();
        all.setAll(true);
        AiAgentContext context = context(all);
        context.setDataPermission(all);
        context.setOrderDataPermission(all);

        List<AiAgentQueryPermissionService.PermissionSql> permissions = List.of(
                service.build(schemaService.findEntity("customer").orElseThrow(), context),
                service.build(schemaService.findEntity("contract").orElseThrow(), context),
                service.build(schemaService.findEntity("sales_order").orElseThrow(), context));

        assertThat(permissions).allSatisfy(permission -> {
            assertThat(permission.whereSql()).contains("organization_id = :orgId");
            assertThat(permission.whereSql()).doesNotContain("1 = 0", ".owner = :userId");
        });
    }

    @Test
    void shouldRestrictExternalContractToResolvedManagerNames() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        DeptDataPermissionDTO self = new DeptDataPermissionDTO();
        self.setSelf(true);
        when(mapper.findUserNamesByDataPermission("org-1", "user-1", self)).thenReturn(List.of("Alice"));
        AiAgentContext context = context(self);

        AiAgentQueryPermissionService.PermissionSql permission = service.build(contractInfo(), context);
        Map<String, Object> params = new HashMap<>();
        service.fillCommonParams(params, contractInfo(), context);

        assertThat(permission.whereSql()).contains("TRIM(ci.manager) = :allowedManagerName0");
        assertThat(params).containsEntry("allowedManagerName0", "Alice");
    }

    @Test
    void shouldAllowExternalContractForAgentUserWithAllPermission() {
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        AiAgentQueryPermissionService service = service(mapper);
        DeptDataPermissionDTO all = new DeptDataPermissionDTO();
        all.setAll(true);

        AiAgentQueryPermissionService.PermissionSql permission = service.build(contractInfo(), context(all));

        assertThat(permission.whereSql()).isEmpty();
        verify(mapper, never()).findUserNamesByDataPermission(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private AiAgentContext context(DeptDataPermissionDTO permission) {
        AiAgentContext context = new AiAgentContext();
        context.setUserId("user-1");
        context.setOrganizationId("org-1");
        context.setContractDataPermission(permission);
        return context;
    }

    private AiAgentSemanticSchemaService.EntitySpec contractInfo() {
        return schemaService.findEntity("contract_info").orElseThrow();
    }

    private AiAgentQueryPermissionService service(AiAgentInternalMapper mapper) {
        return new AiAgentQueryPermissionService(mapper);
    }
}
