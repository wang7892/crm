package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentQueryPermissionService {

    private final AiAgentInternalMapper aiAgentInternalMapper;

    public AiAgentQueryPermissionService(AiAgentInternalMapper aiAgentInternalMapper) {
        this.aiAgentInternalMapper = aiAgentInternalMapper;
    }

    public PermissionSql build(AiAgentSemanticSchemaService.EntitySpec entity, AiAgentContext context) {
        return switch (entity.permissionKind()) {
            case CUSTOMER -> customerPermission("c", context.getDataPermission(), context);
            case CUSTOMER_JOIN -> customerJoinPermission(context);
            case CONTRACT -> ownerPermission("ct", context.getContractDataPermission(), context, true);
            case ORGANIZATION -> organizationPermission(entity, context);
            case USER_ORGANIZATION -> userOrganizationPermission(context);
            case EXTERNAL_CONTRACT -> externalContractPermission(context);
        };
    }

    private PermissionSql customerJoinPermission(AiAgentContext context) {
        PermissionSql customer = customerPermission("c", context.getDataPermission(), context);
        return new PermissionSql(customer.joinSql(), " AND fr.organization_id = :orgId" + customer.whereSql());
    }

    private PermissionSql customerPermission(String customerAlias, DeptDataPermissionDTO dataPermission, AiAgentContext context) {
        StringBuilder join = new StringBuilder();
        StringBuilder where = new StringBuilder();
        where.append(" AND ").append(customerAlias).append(".organization_id = :orgId");
        where.append(" AND (").append(customerAlias).append(".in_shared_pool IS NULL OR ")
                .append(customerAlias).append(".in_shared_pool IS FALSE)");

        if (dataPermission == null) {
            where.append(" AND 1 = 0");
            return new PermissionSql(join.toString(), where.toString());
        }
        if (Boolean.TRUE.equals(dataPermission.getVisible())) {
            join.append("""
                    JOIN customer_collaboration cc_perm ON CONVERT(%s.id USING utf8mb4) COLLATE utf8mb4_general_ci =
                        CONVERT(cc_perm.customer_id USING utf8mb4) COLLATE utf8mb4_general_ci
                     AND cc_perm.user_id = :userId
                    """.formatted(customerAlias));
            return new PermissionSql(join.toString(), where.toString());
        }
        if (Boolean.TRUE.equals(dataPermission.getAll())) {
            return new PermissionSql(join.toString(), where.toString());
        }
        if (Boolean.TRUE.equals(dataPermission.getSelf())) {
            where.append(" AND ").append(customerAlias).append(".owner = :userId");
            return new PermissionSql(join.toString(), where.toString());
        }
        if (dataPermission.getDeptIds() != null && !dataPermission.getDeptIds().isEmpty()) {
            join.append("""
                    JOIN sys_organization_user sou_perm ON CONVERT(%s.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                        CONVERT(sou_perm.user_id USING utf8mb4) COLLATE utf8mb4_general_ci
                     AND sou_perm.organization_id = :orgId
                     AND (sou_perm.department_id IN (:deptIds)
                    """.formatted(customerAlias));
            if (StringUtils.equals(dataPermission.getViewId(), "ALL")) {
                join.append(" OR sou_perm.user_id = :userId");
            }
            join.append(")\n");
            return new PermissionSql(join.toString(), where.toString());
        }
        where.append(" AND 1 = 0");
        return new PermissionSql(join.toString(), where.toString());
    }

    private PermissionSql ownerPermission(String ownerAlias, DeptDataPermissionDTO dataPermission,
                                          AiAgentContext context, boolean includeOrg) {
        StringBuilder join = new StringBuilder();
        StringBuilder where = new StringBuilder();
        if (includeOrg) {
            where.append(" AND ").append(ownerAlias).append(".organization_id = :orgId");
        }
        if (dataPermission == null) {
            where.append(" AND 1 = 0");
            return new PermissionSql(join.toString(), where.toString());
        }
        if (Boolean.TRUE.equals(dataPermission.getAll())) {
            return new PermissionSql(join.toString(), where.toString());
        }
        if (Boolean.TRUE.equals(dataPermission.getSelf())) {
            where.append(" AND ").append(ownerAlias).append(".owner = :userId");
            return new PermissionSql(join.toString(), where.toString());
        }
        if (dataPermission.getDeptIds() != null && !dataPermission.getDeptIds().isEmpty()) {
            join.append("""
                    JOIN sys_organization_user sou_perm ON CONVERT(%s.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                        CONVERT(sou_perm.user_id USING utf8mb4) COLLATE utf8mb4_general_ci
                     AND sou_perm.organization_id = :orgId
                     AND (sou_perm.department_id IN (:deptIds)
                    """.formatted(ownerAlias));
            if (StringUtils.equals(dataPermission.getViewId(), "ALL")) {
                join.append(" OR sou_perm.user_id = :userId");
            }
            join.append(")\n");
            return new PermissionSql(join.toString(), where.toString());
        }
        where.append(" AND 1 = 0");
        return new PermissionSql(join.toString(), where.toString());
    }

    private PermissionSql organizationPermission(AiAgentSemanticSchemaService.EntitySpec entity, AiAgentContext context) {
        String alias = switch (entity.name()) {
            case "wecom_ingestion_session_day" -> "ws";
            case "wecom_ingestion_message" -> "wm";
            case "wecom_ingestion_media" -> "wmedia";
            case "wecom_ingestion_message_follow_record" -> "wmfr";
            case "email_webhook_event" -> "em";
            case "email_webhook_attachment" -> "ea";
            default -> "";
        };
        if (StringUtils.isBlank(alias)) {
            return new PermissionSql("", " AND 1 = 0");
        }
        return new PermissionSql("", " AND " + alias + ".organization_id = :orgId");
    }

    private PermissionSql userOrganizationPermission(AiAgentContext context) {
        StringBuilder where = new StringBuilder(" AND sou.organization_id = :orgId");
        DeptDataPermissionDTO dataPermission = context.getDataPermission();
        if (dataPermission == null) {
            where.append(" AND 1 = 0");
        } else if (Boolean.TRUE.equals(dataPermission.getSelf())) {
            where.append(" AND su.id = :userId");
        } else if (!Boolean.TRUE.equals(dataPermission.getAll())
                && dataPermission.getDeptIds() != null
                && !dataPermission.getDeptIds().isEmpty()) {
            where.append(" AND (sou.department_id IN (:deptIds)");
            if (StringUtils.equals(dataPermission.getViewId(), "ALL")) {
                where.append(" OR sou.user_id = :userId");
            }
            where.append(")");
        } else if (!Boolean.TRUE.equals(dataPermission.getAll())
                && (dataPermission.getDeptIds() == null || dataPermission.getDeptIds().isEmpty())) {
            where.append(" AND 1 = 0");
        }
        return new PermissionSql("", where.toString());
    }

    private PermissionSql externalContractPermission(AiAgentContext context) {
        DeptDataPermissionDTO dataPermission = context.getContractDataPermission() == null
                ? context.getDataPermission()
                : context.getContractDataPermission();
        if (dataPermission == null || Boolean.TRUE.equals(dataPermission.getAll())) {
            return new PermissionSql("", "");
        }
        List<String> managerNames = aiAgentInternalMapper.findUserNamesByDataPermission(
                context.getOrganizationId(), context.getUserId(), dataPermission);
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < managerNames.size(); index++) {
            if (StringUtils.isNotBlank(managerNames.get(index))) {
                clauses.add("ci.manager LIKE :allowedManagerName" + index);
            }
        }
        if (clauses.isEmpty()) {
            return new PermissionSql("", " AND 1 = 0");
        }
        return new PermissionSql("", " AND (" + String.join(" OR ", clauses) + ")");
    }

    public void fillCommonParams(Map<String, Object> params, AiAgentSemanticSchemaService.EntitySpec entity,
                                 AiAgentContext context) {
        params.put("orgId", context.getOrganizationId());
        params.put("userId", context.getUserId());
        DeptDataPermissionDTO dataPermission = switch (entity.permissionKind()) {
            case CONTRACT, EXTERNAL_CONTRACT -> context.getContractDataPermission() == null
                    ? context.getDataPermission()
                    : context.getContractDataPermission();
            default -> context.getDataPermission();
        };
        if (dataPermission != null && dataPermission.getDeptIds() != null && !dataPermission.getDeptIds().isEmpty()) {
            params.put("deptIds", dataPermission.getDeptIds());
        }
        fillAllowedManagerParams(params, entity, context);
    }

    private void fillAllowedManagerParams(Map<String, Object> params, AiAgentSemanticSchemaService.EntitySpec entity,
                                          AiAgentContext context) {
        if (entity.permissionKind() != AiAgentSemanticSchemaService.PermissionKind.EXTERNAL_CONTRACT) {
            return;
        }
        DeptDataPermissionDTO dataPermission = context.getContractDataPermission() == null
                ? context.getDataPermission()
                : context.getContractDataPermission();
        if (dataPermission == null || Boolean.TRUE.equals(dataPermission.getAll())) {
            return;
        }
        List<String> managerNames = aiAgentInternalMapper.findUserNamesByDataPermission(
                context.getOrganizationId(), context.getUserId(), dataPermission);
        for (int index = 0; index < managerNames.size(); index++) {
            String managerName = managerNames.get(index);
            if (StringUtils.isNotBlank(managerName)) {
                params.put("allowedManagerName" + index, "%" + managerName.trim() + "%");
            }
        }
    }

    public record PermissionSql(String joinSql, String whereSql) {
    }
}
