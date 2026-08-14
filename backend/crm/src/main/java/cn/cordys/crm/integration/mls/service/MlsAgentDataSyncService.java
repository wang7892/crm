package cn.cordys.crm.integration.mls.service;

import cn.cordys.common.constants.InternalUser;
import cn.cordys.common.util.CommonBeanFactory;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.integration.mls.dto.MlsAgentDataSyncResult;
import cn.cordys.crm.task.service.ShipmentTaskGenerationService;
import cn.cordys.quartz.anno.QuartzScheduled;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Full, idempotent synchronization from the read-only mls_agent_data database.
 *
 * <p>The stages intentionally run in dependency order: customers, contracts,
 * then orders. Contract and order mappings are rebuilt on every complete pass
 * because the external tables can be cleared and imported with different row
 * ids. Only MLS-mapped rows can be removed; local manual rows are never mirror
 * cleanup candidates.</p>
 */
@Service
@Slf4j
public class MlsAgentDataSyncService {

    private static final String CUSTOMER_SOURCE_COMPANY = "\u516c\u53f8\u5ba2\u6237";
    private static final String SOURCE_CUSTOMER = "customer_info";
    private static final String SOURCE_CONTRACT = "contract_info";
    private static final String SOURCE_ORDER = "order_info";
    private static final String TABLE_CUSTOMER = "customer";
    private static final String TABLE_CONTRACT = "contract";
    private static final String TABLE_ORDER = "sales_order";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_STALE = "STALE";
    private static final String STATUS_CONFLICT = "CONFLICT";
    private static final String STATUS_ADMIN = InternalUser.ADMIN.getValue();
    private static final int DEFAULT_PAGE_SIZE = 2000;
    private static final int MAX_PAGE_SIZE = 10000;
    private static final int MAX_ERROR_LENGTH = 4000;
    private static final int CLEANUP_BATCH_SIZE = 1000;
    private static final ZoneId MLS_ZONE_ID = ZoneId.of("Asia/Shanghai");

    private static final String CUSTOMER_UPSERT_SQL = """
            INSERT INTO customer
              (id, name, owner, collection_time, create_time, update_time,
               create_user, update_user, in_shared_pool, organization_id,
               email, full_name, credit_limit, customs_code, region, phone,
               address, remark, customer_available, customer_source)
            VALUES
              (:id, :name, :owner, :collectionTime, :createTime, :updateTime,
               :createUser, :updateUser, :inSharedPool, :organizationId,
               :email, :fullName, :creditLimit, :customsCode, :region, :phone,
               :address, :remark, :customerAvailable, :customerSource)
            ON DUPLICATE KEY UPDATE
              name = VALUES(name),
              full_name = VALUES(full_name),
              credit_limit = VALUES(credit_limit),
              customs_code = VALUES(customs_code),
              region = VALUES(region),
              phone = VALUES(phone),
              address = VALUES(address),
              email = VALUES(email),
              remark = VALUES(remark),
              customer_available = VALUES(customer_available),
              customer_source = VALUES(customer_source),
              collection_time = VALUES(collection_time),
              create_time = VALUES(create_time),
              create_user = VALUES(create_user),
              update_time = VALUES(update_time),
              update_user = VALUES(update_user)
            """;

    private static final String CONTRACT_UPSERT_SQL = """
            INSERT INTO contract
              (id, name, customer_id, owner, amount, order_status, currency,
               number, approval_status, organization_id, create_time, update_time,
               create_user, update_user, start_time, end_time)
            VALUES
              (:id, :name, :customerId, :owner, :amount, :orderStatus, :currency,
               :number, :approvalStatus, :organizationId, :createTime, :updateTime,
               :createUser, :updateUser, :startTime, :endTime)
            ON DUPLICATE KEY UPDATE
              name = VALUES(name),
              customer_id = VALUES(customer_id),
              owner = VALUES(owner),
              amount = VALUES(amount),
              order_status = VALUES(order_status),
              currency = VALUES(currency),
              number = VALUES(number),
              organization_id = VALUES(organization_id),
              create_time = VALUES(create_time),
              create_user = VALUES(create_user),
              update_time = VALUES(update_time),
              update_user = VALUES(update_user),
              start_time = VALUES(start_time),
              end_time = VALUES(end_time)
            """;

    private static final String ORDER_UPSERT_SQL = """
            INSERT INTO sales_order
              (id, order_no, customer_id, contract_id, owner, organization_id,
               process_order_no, processor, merchandiser, status, color, color_code,
               composition, material_name, material_type, process_technology,
               order_time, warehouse_actual_ship_date, quantity, unit, unit_price, amount, currency,
               create_time, update_time, create_user, update_user)
            VALUES
              (:id, :orderNo, :customerId, :contractId, :owner, :organizationId,
               :processOrderNo, :processor, :merchandiser, :status, :color, :colorCode,
               :composition, :materialName, :materialType, :processTechnology,
               :orderTime, :warehouseActualShipDate, :quantity, :unit, :unitPrice, :amount, :currency,
               :createTime, :updateTime, :createUser, :updateUser)
            ON DUPLICATE KEY UPDATE
              order_no = VALUES(order_no),
              customer_id = VALUES(customer_id),
              contract_id = VALUES(contract_id),
              owner = VALUES(owner),
              organization_id = VALUES(organization_id),
              process_order_no = VALUES(process_order_no),
              processor = VALUES(processor),
              merchandiser = VALUES(merchandiser),
              status = VALUES(status),
              color = VALUES(color),
              color_code = VALUES(color_code),
              composition = VALUES(composition),
              material_name = VALUES(material_name),
              material_type = VALUES(material_type),
              process_technology = VALUES(process_technology),
              order_time = VALUES(order_time),
              warehouse_actual_ship_date = VALUES(warehouse_actual_ship_date),
              quantity = VALUES(quantity),
              unit = VALUES(unit),
              unit_price = VALUES(unit_price),
              amount = VALUES(amount),
              currency = VALUES(currency),
              update_time = VALUES(update_time),
              update_user = VALUES(update_user)
            """;

    private static final String MAPPING_UPSERT_SQL = """
            INSERT INTO mls_sync_mapping
              (id, organization_id, source_table, source_id, previous_source_id,
               target_table, target_id, source_updated_at, source_hash, status,
               missing_count, last_error, last_run_id,
               create_time, update_time)
            VALUES
              (:mappingId, :organizationId, :sourceTable, :sourceId, :sourceId,
               :targetTable, :targetId, :sourceUpdatedAt, :sourceHash, :status,
               0, :lastError, :lastRunId,
               :createTime, :updateTime)
            ON DUPLICATE KEY UPDATE
              previous_source_id = VALUES(source_id),
              target_table = VALUES(target_table),
              target_id = VALUES(target_id),
              source_updated_at = VALUES(source_updated_at),
              source_hash = VALUES(source_hash),
              status = VALUES(status),
              missing_count = 0,
              last_error = VALUES(last_error),
              last_run_id = VALUES(last_run_id),
              update_time = VALUES(update_time)
            """;

    private static final String RUN_INSERT_SQL = """
            INSERT INTO mls_sync_run
              (run_id, organization_id, trigger_type, status, stage, start_time,
               create_time, update_time)
            VALUES (:runId, :organizationId, :triggerType, :status, :stage,
                    :startTime, :createTime, :updateTime)
            """;

    private static final String RUN_UPDATE_SQL = """
            UPDATE mls_sync_run
            SET status = :status,
                stage = :stage,
                end_time = :endTime,
                customer_start_time = COALESCE(:customerStartTime, customer_start_time),
                customer_end_time = COALESCE(:customerEndTime, customer_end_time),
                customer_read_count = :customerRead,
                customer_created_count = :customerCreated,
                customer_updated_count = :customerUpdated,
                customer_skipped_count = :customerSkipped,
                customer_failed_count = :customerFailed,
                customer_error_summary = :customerError,
                contract_start_time = COALESCE(:contractStartTime, contract_start_time),
                contract_end_time = COALESCE(:contractEndTime, contract_end_time),
                contract_read_count = :contractRead,
                contract_created_count = :contractCreated,
                contract_updated_count = :contractUpdated,
                contract_skipped_count = :contractSkipped,
                contract_failed_count = :contractFailed,
                contract_deleted_count = :contractDeleted,
                contract_conflict_count = :contractConflicts,
                contract_error_summary = :contractError,
                order_start_time = COALESCE(:orderStartTime, order_start_time),
                order_end_time = COALESCE(:orderEndTime, order_end_time),
                order_read_count = :orderRead,
                order_created_count = :orderCreated,
                order_updated_count = :orderUpdated,
                order_skipped_count = :orderSkipped,
                order_failed_count = :orderFailed,
                order_deleted_count = :orderDeleted,
                order_error_summary = :orderError,
                error_summary = :errorSummary,
                mirror_protection_triggered = :mirrorProtectionTriggered,
                update_time = :updateTime
            WHERE run_id = :runId
            """;

    private static final String CHECKPOINT_UPSERT_SQL = """
            INSERT INTO mls_sync_checkpoint
              (id, organization_id, source_table, cursor_updated_at, cursor_id,
               last_success_run_id, last_success_time, status, last_error,
               create_time, update_time)
            VALUES (:checkpointId, :organizationId, :sourceTable, :cursorUpdatedAt, :cursorId,
                    :lastSuccessRunId, :lastSuccessTime, :status, :lastError,
                    :createTime, :updateTime)
            ON DUPLICATE KEY UPDATE
              cursor_updated_at = VALUES(cursor_updated_at),
              cursor_id = VALUES(cursor_id),
              last_success_run_id = COALESCE(VALUES(last_success_run_id), last_success_run_id),
              last_success_time = COALESCE(VALUES(last_success_time), last_success_time),
              status = VALUES(status),
              last_error = VALUES(last_error),
              update_time = VALUES(update_time)
            """;

    private static final String RUN_ERROR_INSERT_SQL = """
            INSERT INTO mls_sync_run_error
              (id, run_id, organization_id, stage, source_table, source_id,
               target_table, target_id, source_updated_at, status, error_code,
               error_message, row_payload, retryable, create_time, update_time)
            VALUES (:id, :runId, :organizationId, :stage, :sourceTable, :sourceId,
                    :targetTable, :targetId, :sourceUpdatedAt, :status, :errorCode,
                    :errorMessage, :rowPayload, :retryable, :createTime, :updateTime)
            """;

    private final NamedParameterJdbcTemplate crmJdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MlsExternalQueryRetry externalQueryRetry = new MlsExternalQueryRetry();

    @Autowired(required = false)
    @Qualifier("aiAgentExternalOrderJdbcTemplate")
    private NamedParameterJdbcTemplate externalJdbcTemplate;

    @Autowired
    private ShipmentTaskGenerationService shipmentTaskGenerationService;

    @Value("${crm.mls-sync.enabled:false}")
    private boolean enabled;

    @Value("${crm.mls-sync.organization-id:}")
    private String configuredOrganizationId;

    @Value("${crm.mls-sync.page-size:2000}")
    private int configuredPageSize;

    public MlsAgentDataSyncService(@Qualifier("dataSource") DataSource dataSource) {
        this.crmJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    /** Runs every night at local midnight through the repository's Quartz scheduler. */
    @QuartzScheduled(cron = "${crm.mls-sync.resolved-cron:0 0 0 * * ?}")
    public void scheduledSync() {
        try {
            MlsAgentDataSyncResult result = sync(configuredOrganizationId, "SCHEDULED", configuredPageSize);
            if (STATUS_FAILED.equals(result.getStatus()) || STATUS_PARTIAL.equals(result.getStatus())
                    || "NOT_CONFIGURED".equals(result.getStatus())
                    || "REJECTED_ORGANIZATION".equals(result.getStatus())) {
                log.error("MLS scheduled synchronization finished with status={}, runId={}, warnings={}",
                        result.getStatus(), result.getRunId(), result.getWarnings());
            }
        } catch (Exception e) {
            log.error("MLS scheduled synchronization failed", e);
        }
    }

    public MlsAgentDataSyncResult sync(String organizationId, String triggerType, Integer pageSizeOverride) {
        String orgId = StringUtils.defaultIfBlank(organizationId, configuredOrganizationId);
        String trigger = StringUtils.defaultIfBlank(triggerType, "MANUAL");
        int pageSize = normalizePageSize(pageSizeOverride == null ? configuredPageSize : pageSizeOverride);

        MlsAgentDataSyncResult result = new MlsAgentDataSyncResult();
        result.setOrganizationId(orgId);
        result.setTriggerType(trigger);
        result.setStartTime(System.currentTimeMillis());

        if (!enabled) {
            result.setStatus("DISABLED");
            result.setEndTime(System.currentTimeMillis());
            result.warning("crm.mls-sync.enabled=false");
            return result;
        }
        if (StringUtils.isBlank(configuredOrganizationId)) {
            result.setStatus("NOT_CONFIGURED");
            result.setEndTime(System.currentTimeMillis());
            result.warning("crm.mls-sync.organization-id is required");
            return result;
        }
        if (!configuredOrganizationId.equals(orgId)) {
            result.setStatus("REJECTED_ORGANIZATION");
            result.setEndTime(System.currentTimeMillis());
            result.warning("MLS synchronization is restricted to organization " + configuredOrganizationId);
            return result;
        }
        if (externalJdbcTemplate == null) {
            result.setStatus("NOT_CONFIGURED");
            result.setEndTime(System.currentTimeMillis());
            result.warning("外部数据源未配置，请配置 crm.ai-agent.external-order.*");
            return result;
        }

        RLock lock = null;
        try {
            Redisson redisson = CommonBeanFactory.getBean(Redisson.class);
            if (redisson != null) {
                lock = redisson.getLock("crm:mls-sync:" + orgId);
                if (!lock.tryLock()) {
                    result.setStatus("SKIPPED_LOCKED");
                    result.setEndTime(System.currentTimeMillis());
                    result.warning("已有另一条 MLS 同步任务正在运行");
                    return result;
                }
            } else if (hasRecentRunningRun(orgId)) {
                result.setStatus("SKIPPED_LOCKED");
                result.setEndTime(System.currentTimeMillis());
                result.warning("检测到已有 MLS 同步任务正在运行");
                return result;
            }

            String runId = newId();
            result.setRunId(runId);
            failAbandonedRuns(orgId, result.getStartTime());
            createRun(runId, orgId, trigger, result.getStartTime());
            OrganizationContext.setOrganizationId(orgId);
            try {
                syncCustomers(orgId, runId, pageSize, result);
                MirrorStageState contractStage = syncContracts(orgId, runId, pageSize, result);
                MirrorStageState orderStage = syncOrders(orgId, runId, pageSize, result);
                finalizeMirrorCleanup(orgId, runId, orderStage, contractStage, result);
                generateShipmentTasks(orgId, result);
                result.setStatus(result.hasFailures() ? STATUS_PARTIAL : STATUS_SUCCESS);
            } catch (Exception e) {
                result.setStatus(STATUS_FAILED);
                result.warning("同步任务异常: " + safeMessage(e));
                log.error("MLS synchronization failed, runId={}, orgId={}", runId, orgId, e);
            } finally {
                result.setEndTime(System.currentTimeMillis());
                try {
                    updateRun(runId, result, result.getStatus(), "completed");
                } finally {
                    OrganizationContext.clear();
                }
            }
        } catch (Exception e) {
            result.setStatus(STATUS_FAILED);
            result.setEndTime(System.currentTimeMillis());
            result.warning("创建同步运行记录失败: " + safeMessage(e));
            log.error("MLS synchronization setup failed, orgId={}", orgId, e);
        } finally {
            if (lock != null) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("释放 MLS synchronization lock failed", e);
                }
            }
        }
        return result;
    }

    private void generateShipmentTasks(String organizationId, MlsAgentDataSyncResult result) {
        try {
            ShipmentTaskGenerationService.GenerationResult generation =
                    shipmentTaskGenerationService.generateYesterdayTasks(organizationId);
            result.setShipmentTaskScanned(generation.scanned());
            result.setShipmentTaskCreated(generation.created());
            result.setShipmentTaskSkipped(generation.skipped());
            result.setShipmentTaskFailed(generation.failed());
            result.warningIf(generation.failed() > 0,
                    generation.failed() + " shipment notification task(s) could not be generated");
            log.info("Shipment notification task generation completed, organizationId={}, scanned={}, created={}, "
                            + "skipped={}, failed={}", organizationId, generation.scanned(), generation.created(),
                    generation.skipped(), generation.failed());
        } catch (RuntimeException e) {
            result.setShipmentTaskFailed(result.getShipmentTaskFailed() + 1);
            result.warning("发货通知任务生成失败: " + safeMessage(e));
            log.error("Shipment notification task generation failed, organizationId={}", organizationId, e);
        }
    }

    private void syncCustomers(String orgId, String runId, int pageSize, MlsAgentDataSyncResult result) {
        long stageStart = System.currentTimeMillis();
        updateStageStart(runId, "customer", stageStart);
        CustomerIndex customerIndex = loadCustomerIndex(orgId);
        CustomerMappingRepair mappingRepair = loadCustomerMappingRepair(orgId);
        long lastId = 0;
        long maxUpdatedAt = 0;
        while (true) {
            long pageStartId = lastId;
            List<Map<String, Object>> rows = queryExternalRows(SOURCE_CUSTOMER, """
                    SELECT id, customer_no, customer_name, customer_full_name, customer_type,
                           credit_limit, address, telephone, email, remark, creator, create_time,
                           updater, update_time, is_usable, customs_code, region
                    FROM customer_info
                    WHERE id > :lastId
                    ORDER BY id ASC
                    LIMIT :limit
                    """, lastId, pageSize);
            if (rows.isEmpty()) {
                break;
            }
            Map<String, MappingRecord> mappings = loadMappings(orgId, SOURCE_CUSTOMER, sourceIds(rows));
            for (Map<String, Object> row : rows) {
                result.setCustomerRead(result.getCustomerRead() + 1);
                String sourceId = sourceId(row);
                MappingRecord existingMapping = mappings.get(sourceId);
                try {
                    ProcessOutcome outcome = syncCustomerRow(orgId, runId, row, existingMapping,
                            customerIndex, mappingRepair.forceRewriteSourceIds.contains(sourceId),
                            mappingRepair.remapSourceIds.contains(sourceId), result.getStartTime());
                    applyCustomerOutcome(result, outcome);
                    Long updatedAt = toEpochMillis(row.get("update_time"));
                    maxUpdatedAt = Math.max(maxUpdatedAt, updatedAt == null ? 0 : updatedAt);
                } catch (Exception e) {
                    result.setCustomerFailed(result.getCustomerFailed() + 1);
                    recordFailure(orgId, runId, "customer", SOURCE_CUSTOMER, sourceId, TABLE_CUSTOMER,
                            existingMapping == null ? null : existingMapping.targetId,
                            toEpochMillis(row.get("update_time")),
                            existingMapping == null ? null : existingMapping.sourceHash, e, row);
                }
                lastId = Math.max(lastId, numericId(row.get("id")));
            }
            updateRun(runId, result, STATUS_RUNNING, "customer");
            log.info("MLS customer progress, runId={}, read={}, failed={}",
                    runId, result.getCustomerRead(), result.getCustomerFailed());
            if (rows.size() >= pageSize && lastId <= pageStartId) {
                throw new SyncRowException("SOURCE_CURSOR_NOT_ADVANCING", "customer_info.id 游标未前进");
            }
            if (rows.size() < pageSize) {
                break;
            }
        }
        upsertCheckpoint(orgId, SOURCE_CUSTOMER, maxUpdatedAt, Long.toString(lastId), runId,
                result.getCustomerFailed() == 0 ? STATUS_SUCCESS : STATUS_PARTIAL,
                result.getCustomerFailed() == 0 ? null : "客户阶段存在失败记录");
        updateStageEnd(runId, "customer", System.currentTimeMillis());
        result.warningIf(result.getCustomerFailed() > 0,
                "客户阶段有 " + result.getCustomerFailed() + " 条失败记录，请查询 mls_sync_run_error");
    }

    private ProcessOutcome syncCustomerRow(String orgId, String runId, Map<String, Object> row,
                                            MappingRecord mapping, CustomerIndex customerIndex,
                                            boolean forceRewrite, boolean remapTarget, long syncTime) {
        String sourceId = sourceId(row);
        String name = text(row.get("customer_name"));
        if (StringUtils.isBlank(sourceId) || StringUtils.isBlank(name)) {
            throw new SyncRowException("INVALID_CUSTOMER", "customer_info.id/customer_name 为空");
        }
        String hash = hashValues(
                name, text(row.get("customer_full_name")), row.get("credit_limit"), row.get("address"),
                row.get("telephone"), row.get("email"), row.get("remark"), row.get("is_usable"),
                row.get("customs_code"), row.get("region"), row.get("creator"), row.get("create_time"),
                row.get("updater"), row.get("update_time"));

        String targetId = mapping == null || remapTarget ? null : mapping.targetId;
        boolean existing = false;
        if (StringUtils.isNotBlank(targetId)) {
            existing = resolveMappedTargetExists(TABLE_CUSTOMER, targetId, orgId, mapping);
        }
        if (!existing && !remapTarget) {
            CustomerRef reusable = customerIndex.uniqueUnmappedCompanyMatch(name);
            if (reusable == null) {
                reusable = customerIndex.uniqueUnmappedCompanyMatch(text(row.get("customer_full_name")));
            }
            if (reusable != null && (mapping == null || StringUtils.isBlank(mapping.targetId))) {
                targetId = reusable.id;
                existing = true;
            }
        }
        if (StringUtils.isBlank(targetId)) {
            targetId = stableId(SOURCE_CUSTOMER, orgId, sourceId);
        }
        if (!forceRewrite && mapping != null && STATUS_SUCCESS.equals(mapping.status) && hash.equals(mapping.sourceHash)
                && targetMatchesLastSync(mapping, true)) {
            customerIndex.add(new CustomerRef(targetId, name, text(row.get("customer_full_name")),
                    CUSTOMER_SOURCE_COMPANY, true));
            return ProcessOutcome.SKIPPED;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", targetId)
                .addValue("name", limit(name, 255))
                .addValue("owner", existing ? null : STATUS_ADMIN)
                .addValue("collectionTime", syncTime)
                .addValue("createTime", defaultTime(toEpochMillis(row.get("create_time")), syncTime))
                .addValue("updateTime", defaultTime(toEpochMillis(row.get("update_time")), syncTime))
                .addValue("createUser", auditUser(row.get("creator")))
                .addValue("updateUser", auditUser(row.get("updater"), row.get("creator")))
                .addValue("inSharedPool", false)
                .addValue("organizationId", orgId)
                .addValue("email", text(row.get("email")))
                .addValue("fullName", text(row.get("customer_full_name")))
                .addValue("creditLimit", text(row.get("credit_limit")))
                .addValue("customsCode", text(row.get("customs_code")))
                .addValue("region", text(row.get("region")))
                .addValue("phone", text(row.get("telephone")))
                .addValue("address", text(row.get("address")))
                .addValue("remark", row.get("remark"))
                .addValue("customerAvailable", usable(row.get("is_usable")))
                .addValue("customerSource", CUSTOMER_SOURCE_COMPANY);
        String persistedTargetId = targetId;
        transactionTemplate.executeWithoutResult(status -> {
            crmJdbcTemplate.update(CUSTOMER_UPSERT_SQL, params);
            upsertMapping(orgId, runId, SOURCE_CUSTOMER, sourceId, TABLE_CUSTOMER, persistedTargetId,
                    toEpochMillis(row.get("update_time")), hash, STATUS_SUCCESS, null, syncTime);
        });
        customerIndex.add(new CustomerRef(targetId, name, text(row.get("customer_full_name")),
                CUSTOMER_SOURCE_COMPANY, true));
        return existing || mapping != null ? ProcessOutcome.UPDATED : ProcessOutcome.CREATED;
    }

    private MirrorStageState syncContracts(String orgId, String runId, int pageSize,
                                           MlsAgentDataSyncResult result) {
        long stageStart = System.currentTimeMillis();
        updateStageStart(runId, "contract", stageStart);
        MlsMirrorSyncProtection.SourceSnapshot startSnapshot = queryExternalSnapshot(SOURCE_CONTRACT);
        long mappedCount = countMirrorMappings(orgId, SOURCE_CONTRACT, TABLE_CONTRACT);
        if (!MlsMirrorSyncProtection.allowsMirrorPass(startSnapshot.rowCount(), mappedCount)) {
            String warning = mirrorCountGuardMessage(SOURCE_CONTRACT, startSnapshot.rowCount(), mappedCount);
            result.setMirrorProtectionTriggered(true);
            result.warning(warning);
            upsertCheckpoint(orgId, SOURCE_CONTRACT, null, Long.toString(startSnapshot.maxId()), runId,
                    STATUS_PARTIAL, warning);
            updateStageEnd(runId, "contract", System.currentTimeMillis());
            return MirrorStageState.blocked(SOURCE_CONTRACT, TABLE_CONTRACT);
        }
        stageMirrorMappings(orgId, runId, SOURCE_CONTRACT);
        CustomerIndex customerIndex = loadCustomerIndex(orgId);
        Set<String> successfulCustomerTargetIds = loadSuccessfulTargetIds(
                orgId, SOURCE_CUSTOMER, TABLE_CUSTOMER);
        ContractIndex contractIndex = loadContractIndex(orgId);
        UserIndex userIndex = loadUserIndex(orgId);
        Map<String, OwnerCandidate> ownerCandidates = new HashMap<>();
        long lastId = 0;
        long maxUpdatedAt = 0;
        while (true) {
            long pageStartId = lastId;
            List<Map<String, Object>> rows = queryExternalRows(SOURCE_CONTRACT, """
                    SELECT id, order_no, product_name, manager, customer, order_status,
                           amount, currency, release_date, creator, create_time,
                           updater, update_time, approval_status, delivery_date
                    FROM contract_info
                    WHERE id > :lastId
                    ORDER BY id ASC
                    LIMIT :limit
                    """, lastId, pageSize);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                result.setContractRead(result.getContractRead() + 1);
                String sourceId = sourceId(row);
                MappingRecord existingMapping = null;
                try {
                    ContractRef mappedContract = contractIndex.uniqueMappedMatch(
                            text(row.get("order_no")), null);
                    existingMapping = mappedContract == null ? null : mappedContract.mapping;
                    ProcessOutcome outcome = syncContractRow(orgId, runId, row, existingMapping,
                            customerIndex, successfulCustomerTargetIds, contractIndex, userIndex, ownerCandidates,
                            result.getStartTime(), result);
                    applyContractOutcome(result, outcome);
                    Long updatedAt = toEpochMillis(row.get("update_time"));
                    maxUpdatedAt = Math.max(maxUpdatedAt, updatedAt == null ? 0 : updatedAt);
                } catch (Exception e) {
                    result.setContractFailed(result.getContractFailed() + 1);
                    recordFailure(orgId, runId, "contract", SOURCE_CONTRACT, sourceId, TABLE_CONTRACT,
                            existingMapping == null ? null : existingMapping.targetId,
                            toEpochMillis(row.get("update_time")),
                            existingMapping == null ? null : existingMapping.sourceHash, e, row, false);
                }
                lastId = Math.max(lastId, numericId(row.get("id")));
            }
            updateRun(runId, result, STATUS_RUNNING, "contract");
            log.info("MLS contract progress, runId={}, read={}, failed={}",
                    runId, result.getContractRead(), result.getContractFailed());
            if (rows.size() >= pageSize && lastId <= pageStartId) {
                throw new SyncRowException("SOURCE_CURSOR_NOT_ADVANCING", "contract_info.id 游标未前进");
            }
            if (rows.size() < pageSize) {
                break;
            }
        }
        if (result.getContractFailed() == 0) {
            refreshCustomerOwners(orgId, ownerCandidates);
        } else {
            result.warning("Customer owner refresh was skipped because the contract stage had failures");
        }
        MlsMirrorSyncProtection.SourceSnapshot endSnapshot = queryExternalSnapshot(SOURCE_CONTRACT);
        boolean complete = MlsMirrorSyncProtection.isComplete(startSnapshot, endSnapshot,
                result.getContractRead(), result.getContractFailed());
        if (!startSnapshot.equals(endSnapshot)) {
            result.setMirrorProtectionTriggered(true);
            result.warning("contract_info changed while it was being read; contract cleanup was blocked");
        }
        upsertCheckpoint(orgId, SOURCE_CONTRACT, maxUpdatedAt, Long.toString(lastId), runId,
                complete ? STATUS_SUCCESS : STATUS_PARTIAL,
                result.getContractFailed() == 0 ? null : "合同阶段存在失败记录");
        updateStageEnd(runId, "contract", System.currentTimeMillis());
        result.warningIf(result.getContractFailed() > 0,
                "合同阶段有 " + result.getContractFailed() + " 条失败记录，请查询 mls_sync_run_error");
        return new MirrorStageState(SOURCE_CONTRACT, TABLE_CONTRACT, true, complete);
    }

    private ProcessOutcome syncContractRow(String orgId, String runId, Map<String, Object> row,
                                            MappingRecord mapping, CustomerIndex customerIndex,
                                            Set<String> successfulCustomerTargetIds,
                                            ContractIndex contractIndex, UserIndex userIndex,
                                            Map<String, OwnerCandidate> ownerCandidates, long syncTime,
                                            MlsAgentDataSyncResult result) {
        String sourceId = sourceId(row);
        String number = text(row.get("order_no"));
        if (StringUtils.isBlank(sourceId) || StringUtils.isBlank(number)) {
            throw new SyncRowException("INVALID_CONTRACT", "contract_info.id/order_no 为空");
        }
        String customerName = text(row.get("customer"));
        CustomerRef customer = customerIndex.resolve(customerName);
        if (customer == null) {
            throw new SyncRowException("CUSTOMER_NOT_FOUND", "合同客户无法唯一匹配 CRM 客户: " + customerName);
        }
        if (!successfulCustomerTargetIds.contains(customer.id)) {
            throw new SyncRowException("CUSTOMER_SYNC_NOT_SUCCESSFUL",
                    "合同客户在本轮客户同步后没有成功映射: " + customerName);
        }
        String owner = userIndex.resolve(text(row.get("manager")));
        Long createTime = defaultTime(toEpochMillis(row.get("create_time")), syncTime);
        Long startTime = toEpochMillis(row.get("release_date"));
        if (startTime == null) {
            startTime = createTime;
            result.warningIf(result.getWarnings().stream().noneMatch(v -> v.startsWith("合同 release_date")),
                    "合同 release_date 为空，已使用外部 create_time 回退");
        }
        Long endTime = toEpochMillis(row.get("delivery_date"));
        if (endTime == null) {
            endTime = startTime;
            result.warningIf(result.getWarnings().stream().noneMatch(v -> v.startsWith("合同 delivery_date")),
                    "合同 delivery_date 为空，已使用 start_time 回退");
        }
        String name = StringUtils.defaultIfBlank(text(row.get("product_name")), number);
        Long updateTime = defaultTime(toEpochMillis(row.get("update_time")), syncTime);
        String resolvedOwner = StringUtils.defaultIfBlank(owner, STATUS_ADMIN);
        String hash = hashValues(number, name, customer.id, resolvedOwner, row.get("amount"),
                row.get("order_status"), row.get("currency"), startTime, endTime,
                row.get("creator"), row.get("create_time"), row.get("updater"), row.get("update_time"));

        String targetId = mapping == null ? null : mapping.targetId;
        boolean existing = false;
        if (StringUtils.isNotBlank(targetId)) {
            existing = resolveMappedTargetExists(TABLE_CONTRACT, targetId, orgId, mapping);
        }
        if (StringUtils.isBlank(targetId)) {
            targetId = stableId("contract-number", orgId, normalize(number));
        }
        if (mapping != null && hash.equals(mapping.sourceHash)
                && targetMatchesLastSync(mapping, true)) {
            activateMapping(orgId, runId, SOURCE_CONTRACT, sourceId, TABLE_CONTRACT, targetId,
                    toEpochMillis(row.get("update_time")), hash, mapping, syncTime, true);
            contractIndex.add(new ContractRef(targetId, number, customer.id, resolvedOwner, true, mapping));
            recordOwnerCandidate(ownerCandidates, customer.id, resolvedOwner, updateTime, sourceId);
            return ProcessOutcome.SKIPPED;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", targetId)
                .addValue("name", limit(name, 255))
                .addValue("customerId", customer.id)
                .addValue("owner", limit(resolvedOwner, 32))
                .addValue("amount", toBigDecimal(row.get("amount")))
                .addValue("orderStatus", text(row.get("order_status")))
                .addValue("currency", text(row.get("currency")))
                .addValue("number", limit(number, 50))
                .addValue("approvalStatus", "NONE")
                .addValue("organizationId", orgId)
                .addValue("createTime", createTime)
                .addValue("updateTime", updateTime)
                .addValue("createUser", auditUser(row.get("creator")))
                .addValue("updateUser", auditUser(row.get("updater"), row.get("creator")))
                .addValue("startTime", startTime)
                .addValue("endTime", endTime);
        String persistedTargetId = targetId;
        transactionTemplate.executeWithoutResult(status -> {
            crmJdbcTemplate.update(CONTRACT_UPSERT_SQL, params);
            activateMapping(orgId, runId, SOURCE_CONTRACT, sourceId, TABLE_CONTRACT, persistedTargetId,
                    toEpochMillis(row.get("update_time")), hash, mapping, syncTime, false);
        });
        contractIndex.add(new ContractRef(targetId, number, customer.id, resolvedOwner, true, mapping));
        recordOwnerCandidate(ownerCandidates, customer.id, resolvedOwner, updateTime, sourceId);
        return existing || mapping != null ? ProcessOutcome.UPDATED : ProcessOutcome.CREATED;
    }

    private void refreshCustomerOwners(String orgId, Map<String, OwnerCandidate> candidates) {
        transactionTemplate.executeWithoutResult(status -> {
            List<Map<String, Object>> mapped = crmJdbcTemplate.queryForList("""
                    SELECT target_id
                    FROM mls_sync_mapping
                    WHERE organization_id = :organizationId
                      AND source_table = :sourceTable
                      AND target_table = :targetTable
                      AND status = 'SUCCESS'
                      AND target_id IS NOT NULL
                    """, new MapSqlParameterSource()
                    .addValue("organizationId", orgId)
                    .addValue("sourceTable", SOURCE_CUSTOMER)
                    .addValue("targetTable", TABLE_CUSTOMER));
            List<String> customerIds = mapped.stream().map(value -> text(value.get("target_id")))
                    .filter(StringUtils::isNotBlank).distinct().toList();
            if (!customerIds.isEmpty()) {
                crmJdbcTemplate.update("""
                        UPDATE customer
                        SET owner = :owner
                        WHERE organization_id = :organizationId AND id IN (:ids)
                        """, new MapSqlParameterSource()
                        .addValue("owner", STATUS_ADMIN)
                        .addValue("organizationId", orgId)
                        .addValue("ids", customerIds));
            }
            for (Map.Entry<String, OwnerCandidate> entry : candidates.entrySet()) {
                crmJdbcTemplate.update("""
                        UPDATE customer
                        SET owner = :owner
                        WHERE organization_id = :organizationId AND id = :id
                        """, new MapSqlParameterSource()
                        .addValue("owner", limit(StringUtils.defaultIfBlank(
                                entry.getValue().owner, STATUS_ADMIN), 32))
                        .addValue("organizationId", orgId)
                        .addValue("id", entry.getKey()));
            }
        });
    }

    private MirrorStageState syncOrders(String orgId, String runId, int pageSize,
                                        MlsAgentDataSyncResult result) {
        long stageStart = System.currentTimeMillis();
        updateStageStart(runId, "order", stageStart);
        MlsMirrorSyncProtection.SourceSnapshot startSnapshot = queryExternalSnapshot(SOURCE_ORDER);
        long mappedCount = countMirrorMappings(orgId, SOURCE_ORDER, TABLE_ORDER);
        if (!MlsMirrorSyncProtection.allowsMirrorPass(startSnapshot.rowCount(), mappedCount)) {
            String warning = mirrorCountGuardMessage(SOURCE_ORDER, startSnapshot.rowCount(), mappedCount);
            result.setMirrorProtectionTriggered(true);
            result.warning(warning);
            upsertCheckpoint(orgId, SOURCE_ORDER, null, Long.toString(startSnapshot.maxId()), runId,
                    STATUS_PARTIAL, warning);
            updateStageEnd(runId, "order", System.currentTimeMillis());
            return MirrorStageState.blocked(SOURCE_ORDER, TABLE_ORDER);
        }
        stageMirrorMappings(orgId, runId, SOURCE_ORDER);
        ContractIndex contractIndex = loadContractIndex(orgId);
        Set<String> successfulContractTargetIds = loadSuccessfulTargetIds(
                orgId, SOURCE_CONTRACT, TABLE_CONTRACT);
        Map<String, Integer> hashOccurrences = new HashMap<>();
        long lastId = 0;
        while (true) {
            long pageStartId = lastId;
            List<Map<String, Object>> rows = queryExternalRows(SOURCE_ORDER, """
                    SELECT order_row.id, order_row.order_no, order_row.process_order_no,
                           order_row.processor, order_row.merchandiser, order_row.status,
                           order_row.color, order_row.color_code, order_row.composition,
                           order_row.material_name, order_row.material_type,
                           order_row.process_technology, order_row.order_time,
                           timeline.warehouse_actual_ship_date,
                           order_row.quantity, order_row.unit, order_row.unit_price,
                           order_row.amount, order_row.currency
                    FROM order_info order_row
                    LEFT JOIN order_timeline timeline ON timeline.order_no = order_row.order_no
                    WHERE order_row.id > :lastId
                    ORDER BY order_row.id ASC
                    LIMIT :limit
                    """, lastId, pageSize);
            if (rows.isEmpty()) {
                break;
            }
            List<PreparedOrder> preparedOrders = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                result.setOrderRead(result.getOrderRead() + 1);
                String sourceId = sourceId(row);
                try {
                    preparedOrders.add(prepareOrder(row, contractIndex, successfulContractTargetIds,
                            hashOccurrences));
                } catch (Exception e) {
                    result.setOrderFailed(result.getOrderFailed() + 1);
                    recordFailure(orgId, runId, "order", SOURCE_ORDER, sourceId, TABLE_ORDER,
                            null, null, null, e, row, false);
                }
                lastId = Math.max(lastId, numericId(row.get("id")));
            }
            OrderMappingPool mappingPool = loadOrderMappingPool(orgId, preparedOrders);
            for (PreparedOrder prepared : preparedOrders) {
                MappingRecord existingMapping = mappingPool.take(prepared);
                try {
                    ProcessOutcome outcome = syncOrderRow(orgId, runId, prepared, existingMapping,
                            result.getStartTime());
                    applyOrderOutcome(result, outcome);
                } catch (Exception e) {
                    result.setOrderFailed(result.getOrderFailed() + 1);
                    recordFailure(orgId, runId, "order", SOURCE_ORDER, prepared.sourceId, TABLE_ORDER,
                            existingMapping == null ? null : existingMapping.targetId, null,
                            existingMapping == null ? null : existingMapping.sourceHash,
                            e, prepared.row, false);
                }
            }
            updateRun(runId, result, STATUS_RUNNING, "order");
            if (result.getOrderRead() % 20_000 < rows.size() || rows.size() < pageSize) {
                log.info("MLS order progress, runId={}, read={}, failed={}",
                        runId, result.getOrderRead(), result.getOrderFailed());
            }
            if (rows.size() >= pageSize && lastId <= pageStartId) {
                throw new SyncRowException("SOURCE_CURSOR_NOT_ADVANCING", "order_info.id 游标未前进");
            }
            if (rows.size() < pageSize) {
                break;
            }
        }
        MlsMirrorSyncProtection.SourceSnapshot endSnapshot = queryExternalSnapshot(SOURCE_ORDER);
        boolean complete = MlsMirrorSyncProtection.isComplete(startSnapshot, endSnapshot,
                result.getOrderRead(), result.getOrderFailed());
        if (!startSnapshot.equals(endSnapshot)) {
            result.setMirrorProtectionTriggered(true);
            result.warning("order_info changed while it was being read; order cleanup was blocked");
        }
        upsertCheckpoint(orgId, SOURCE_ORDER, null, Long.toString(lastId), runId,
                complete ? STATUS_SUCCESS : STATUS_PARTIAL,
                result.getOrderFailed() == 0 ? null : "订单阶段存在失败记录");
        updateStageEnd(runId, "order", System.currentTimeMillis());
        result.warningIf(result.getOrderFailed() > 0,
                "订单阶段有 " + result.getOrderFailed() + " 条失败记录，请查询 mls_sync_run_error");
        return new MirrorStageState(SOURCE_ORDER, TABLE_ORDER, true, complete);
    }

    private PreparedOrder prepareOrder(Map<String, Object> row, ContractIndex contractIndex,
                                       Set<String> successfulContractTargetIds,
                                       Map<String, Integer> hashOccurrences) {
        String sourceId = sourceId(row);
        String orderNo = text(row.get("order_no"));
        if (StringUtils.isBlank(sourceId) || StringUtils.isBlank(orderNo)) {
            throw new SyncRowException("INVALID_ORDER", "order_info.id/order_no 为空");
        }
        ContractRef contract = contractIndex.resolveForOrder(orderNo);
        if (contract == null) {
            throw new SyncRowException("CONTRACT_NOT_FOUND",
                    "No unique MLS contract mapping for order_info.order_no: " + orderNo);
        }
        if (!successfulContractTargetIds.contains(contract.id)) {
            throw new SyncRowException("CONTRACT_SYNC_NOT_SUCCESSFUL",
                    "The MLS contract mapping is not successful for order_info.order_no: " + orderNo);
        }
        String hash = hashValues(orderNo, row.get("process_order_no"), row.get("processor"),
                row.get("merchandiser"), row.get("status"), row.get("color"), row.get("color_code"),
                row.get("composition"), row.get("material_name"), row.get("material_type"),
                row.get("process_technology"), row.get("order_time"),
                row.get("warehouse_actual_ship_date"), row.get("quantity"),
                row.get("unit"), row.get("unit_price"), row.get("amount"), row.get("currency"),
                contract.customerId, contract.id, contract.owner);
        int occurrence = hashOccurrences.merge(hash, 1, Integer::sum);
        return new PreparedOrder(row, sourceId, orderNo, contract, hash, occurrence);
    }

    private ProcessOutcome syncOrderRow(String orgId, String runId, PreparedOrder prepared,
                                        MappingRecord mapping, long syncTime) {
        Map<String, Object> row = prepared.row;
        String sourceId = prepared.sourceId;
        String orderNo = prepared.orderNo;
        ContractRef contract = prepared.contract;
        String hash = prepared.hash;
        String targetId = mapping == null ? null : mapping.targetId;
        boolean existing = false;
        if (StringUtils.isNotBlank(targetId)) {
            existing = resolveMappedTargetExists(TABLE_ORDER, targetId, orgId, mapping);
        }
        if (StringUtils.isBlank(targetId)) {
            targetId = stableId("order-hash:" + hash, orgId, Integer.toString(prepared.occurrence));
            existing = targetExists(TABLE_ORDER, targetId, orgId);
            if (!existing && targetExistsAny(TABLE_ORDER, targetId)) {
                throw new SyncRowException("TARGET_ORGANIZATION_CONFLICT",
                        "Deterministic sales_order target belongs to another organization: " + targetId);
            }
        }
        String customerId = contract.customerId;
        String contractId = contract.id;
        String owner = contract.owner;
        if (mapping != null && hash.equals(mapping.sourceHash)
                && targetMatchesLastSync(mapping, false)) {
            activateMapping(orgId, runId, SOURCE_ORDER, sourceId, TABLE_ORDER, targetId,
                    null, hash, mapping, syncTime, true);
            return ProcessOutcome.SKIPPED;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", targetId)
                .addValue("orderNo", limit(orderNo, 50))
                .addValue("customerId", customerId)
                .addValue("contractId", contractId)
                .addValue("owner", owner)
                .addValue("organizationId", orgId)
                .addValue("processOrderNo", text(row.get("process_order_no")))
                .addValue("processor", text(row.get("processor")))
                .addValue("merchandiser", text(row.get("merchandiser")))
                .addValue("status", text(row.get("status")))
                .addValue("color", text(row.get("color")))
                .addValue("colorCode", text(row.get("color_code")))
                .addValue("composition", text(row.get("composition")))
                .addValue("materialName", text(row.get("material_name")))
                .addValue("materialType", text(row.get("material_type")))
                .addValue("processTechnology", text(row.get("process_technology")))
                .addValue("orderTime", toEpochMillis(row.get("order_time")))
                .addValue("warehouseActualShipDate", toEpochMillis(row.get("warehouse_actual_ship_date")))
                .addValue("quantity", toBigDecimal(row.get("quantity")))
                .addValue("unit", text(row.get("unit")))
                .addValue("unitPrice", toBigDecimal(row.get("unit_price")))
                .addValue("amount", toBigDecimal(row.get("amount")))
                .addValue("currency", text(row.get("currency")))
                .addValue("createTime", syncTime)
                .addValue("updateTime", syncTime)
                .addValue("createUser", STATUS_ADMIN)
                .addValue("updateUser", STATUS_ADMIN);
        String persistedTargetId = targetId;
        transactionTemplate.executeWithoutResult(status -> {
            crmJdbcTemplate.update(ORDER_UPSERT_SQL, params);
            activateMapping(orgId, runId, SOURCE_ORDER, sourceId, TABLE_ORDER, persistedTargetId,
                    null, hash, mapping, syncTime, false);
        });
        return existing || mapping != null ? ProcessOutcome.UPDATED : ProcessOutcome.CREATED;
    }

    private MlsMirrorSyncProtection.SourceSnapshot queryExternalSnapshot(String sourceTable) {
        String safeTable = switch (sourceTable) {
            case SOURCE_CONTRACT, SOURCE_ORDER -> sourceTable;
            default -> throw new IllegalArgumentException("Unsupported mirror source table: " + sourceTable);
        };
        return externalQueryRetry.execute(sourceTable, () -> {
            Map<String, Object> row = externalJdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS row_count, COALESCE(MAX(id), 0) AS max_id FROM " + safeTable,
                    new MapSqlParameterSource());
            return new MlsMirrorSyncProtection.SourceSnapshot(
                    numericId(row.get("row_count")), numericId(row.get("max_id")));
        });
    }

    private long countMirrorMappings(String orgId, String sourceTable, String targetTable) {
        Long count = crmJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mls_sync_mapping
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                  AND target_table = :targetTable
                  AND target_id IS NOT NULL
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("targetTable", targetTable), Long.class);
        return count == null ? 0 : count;
    }

    private void stageMirrorMappings(String orgId, String runId, String sourceTable) {
        crmJdbcTemplate.update("""
                UPDATE mls_sync_mapping
                SET previous_source_id = CASE
                        WHEN source_id LIKE '~stale:%' THEN COALESCE(previous_source_id, source_id)
                        ELSE source_id
                    END,
                    source_id = CONCAT('~stale:', :runId, ':', id),
                    status = :status
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("status", STATUS_STALE)
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable));
    }

    private String mirrorCountGuardMessage(String sourceTable, long externalCount, long mappedCount) {
        return sourceTable + " mirror pass was blocked: external count=" + externalCount
                + ", previous mapped count=" + mappedCount
                + "; the source is empty or below the 90% deletion safety threshold";
    }

    private OrderMappingPool loadOrderMappingPool(String orgId, List<PreparedOrder> preparedOrders) {
        if (preparedOrders.isEmpty()) {
            return new OrderMappingPool(List.of());
        }
        List<String> sourceIds = preparedOrders.stream().map(PreparedOrder::sourceId).distinct().toList();
        List<String> hashes = preparedOrders.stream().map(PreparedOrder::hash).distinct().toList();
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT mapping.id AS mapping_id, mapping.previous_source_id,
                       mapping.target_id, mapping.source_hash, mapping.status,
                       mapping.source_updated_at, mapping.update_time AS mapping_updated_at,
                       target_row.id AS existing_target_id, target_row.order_no AS target_order_no,
                       target_row.update_time AS target_updated_at
                FROM mls_sync_mapping mapping
                LEFT JOIN sales_order target_row
                  ON target_row.id = mapping.target_id
                 AND target_row.organization_id = mapping.organization_id
                WHERE mapping.organization_id = :organizationId
                  AND mapping.source_table = :sourceTable
                  AND mapping.target_table = :targetTable
                  AND mapping.status = :status
                  AND mapping.target_id IS NOT NULL
                  AND (mapping.previous_source_id IN (:sourceIds)
                       OR mapping.source_hash IN (:hashes))
                ORDER BY mapping.id
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", SOURCE_ORDER)
                .addValue("targetTable", TABLE_ORDER)
                .addValue("status", STATUS_STALE)
                .addValue("sourceIds", sourceIds)
                .addValue("hashes", hashes));
        List<OrderMappingCandidate> candidates = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            MappingRecord mapping = new MappingRecord(
                    text(row.get("mapping_id")), text(row.get("previous_source_id")),
                    text(row.get("target_id")), text(row.get("source_hash")), text(row.get("status")),
                    toEpochMillis(row.get("source_updated_at")), toEpochMillis(row.get("mapping_updated_at")),
                    StringUtils.isNotBlank(text(row.get("existing_target_id"))),
                    toEpochMillis(row.get("target_updated_at")));
            candidates.add(new OrderMappingCandidate(mapping, text(row.get("target_order_no"))));
        }
        return new OrderMappingPool(candidates);
    }

    private void activateMapping(String orgId, String runId, String sourceTable, String sourceId,
                                 String targetTable, String targetId, Long sourceUpdatedAt,
                                 String sourceHash, MappingRecord mapping, long now,
                                 boolean preserveMappingUpdateTime) {
        if (mapping == null || StringUtils.isBlank(mapping.mappingId)) {
            upsertMapping(orgId, runId, sourceTable, sourceId, targetTable, targetId,
                    sourceUpdatedAt, sourceHash, STATUS_SUCCESS, null, now);
            return;
        }
        int updated = crmJdbcTemplate.update("""
                UPDATE mls_sync_mapping
                SET source_id = :sourceId,
                    previous_source_id = :sourceId,
                    target_table = :targetTable,
                    target_id = :targetId,
                    source_updated_at = :sourceUpdatedAt,
                    source_hash = :sourceHash,
                    status = :status,
                    missing_count = 0,
                    last_error = NULL,
                    last_run_id = :runId,
                    update_time = CASE WHEN :preserveUpdateTime = 1 THEN update_time ELSE :updateTime END
                WHERE id = :mappingId
                  AND organization_id = :organizationId
                  AND source_table = :sourceTable
                """, new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("sourceUpdatedAt", sourceUpdatedAt)
                .addValue("sourceHash", sourceHash)
                .addValue("status", STATUS_SUCCESS)
                .addValue("runId", runId)
                .addValue("updateTime", now)
                .addValue("preserveUpdateTime", preserveMappingUpdateTime)
                .addValue("mappingId", mapping.mappingId)
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable));
        if (updated == 0) {
            upsertMapping(orgId, runId, sourceTable, sourceId, targetTable, targetId,
                    sourceUpdatedAt, sourceHash, STATUS_SUCCESS, null, now);
        }
    }

    private void finalizeMirrorCleanup(String orgId, String runId, MirrorStageState orderStage,
                                       MirrorStageState contractStage, MlsAgentDataSyncResult result) {
        if (orderStage.cleanupEligible()) {
            advanceMissingMappings(orgId, runId, SOURCE_ORDER);
            result.setOrderDeleted(result.getOrderDeleted() + cleanupMissingOrders(orgId));
        }
        if (contractStage.cleanupEligible() && orderStage.cleanupEligible()) {
            advanceMissingMappings(orgId, runId, SOURCE_CONTRACT);
            cleanupMissingContracts(orgId, runId, result);
            result.warningIf(result.getContractConflicts() > 0,
                    result.getContractConflicts()
                            + " missing MLS contracts were retained because CRM related data exists; "
                            + "see mls_sync_run_error");
        } else if (contractStage.cleanupEligible() && !orderStage.cleanupEligible()) {
            result.setMirrorProtectionTriggered(true);
            result.warning("Contract cleanup was blocked because the order mirror pass was incomplete");
        }
        updateRun(runId, result, STATUS_RUNNING, "cleanup");
    }

    private void advanceMissingMappings(String orgId, String runId, String sourceTable) {
        crmJdbcTemplate.update("""
                UPDATE mls_sync_mapping
                SET missing_count = missing_count + 1,
                    last_run_id = :runId,
                    update_time = :updateTime
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                  AND status = :status
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("updateTime", System.currentTimeMillis())
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("status", STATUS_STALE));
    }

    private long cleanupMissingOrders(String orgId) {
        long deleted = 0;
        while (true) {
            List<Map<String, Object>> rows = loadCleanupCandidates(
                    orgId, SOURCE_ORDER, TABLE_ORDER, CLEANUP_BATCH_SIZE);
            if (rows.isEmpty()) {
                return deleted;
            }
            List<String> mappingIds = rows.stream().map(row -> text(row.get("mapping_id"))).toList();
            List<String> targetIds = rows.stream().map(row -> text(row.get("target_id")))
                    .filter(StringUtils::isNotBlank).distinct().toList();
            int removed = transactionTemplate.execute(status -> {
                if (!targetIds.isEmpty()) {
                    MapSqlParameterSource ids = new MapSqlParameterSource().addValue("ids", targetIds);
                    crmJdbcTemplate.update("DELETE FROM sales_order_field WHERE resource_id IN (:ids)", ids);
                    crmJdbcTemplate.update("DELETE FROM sales_order_field_blob WHERE resource_id IN (:ids)", ids);
                    crmJdbcTemplate.update("DELETE FROM sales_order_snapshot WHERE order_id IN (:ids)", ids);
                }
                int targetCount = targetIds.isEmpty() ? 0 : crmJdbcTemplate.update("""
                        DELETE FROM sales_order
                        WHERE organization_id = :organizationId AND id IN (:ids)
                        """, new MapSqlParameterSource()
                        .addValue("organizationId", orgId)
                        .addValue("ids", targetIds));
                crmJdbcTemplate.update("DELETE FROM mls_sync_mapping WHERE id IN (:ids)",
                        new MapSqlParameterSource().addValue("ids", mappingIds));
                return targetCount;
            });
            deleted += removed;
        }
    }

    private void cleanupMissingContracts(String orgId, String runId, MlsAgentDataSyncResult result) {
        while (true) {
            List<Map<String, Object>> rows = loadCleanupCandidates(
                    orgId, SOURCE_CONTRACT, TABLE_CONTRACT, CLEANUP_BATCH_SIZE);
            if (rows.isEmpty()) {
                return;
            }
            for (Map<String, Object> row : rows) {
                String mappingId = text(row.get("mapping_id"));
                String sourceId = text(row.get("previous_source_id"));
                String targetId = text(row.get("target_id"));
                String related = contractRelatedSummary(targetId);
                if (StringUtils.isNotBlank(related)) {
                    recordContractDeleteConflict(orgId, runId, mappingId, sourceId, targetId, related);
                    result.setContractConflicts(result.getContractConflicts() + 1);
                    continue;
                }
                int removed = transactionTemplate.execute(status -> {
                    String concurrentRelated = contractRelatedSummary(targetId);
                    if (StringUtils.isNotBlank(concurrentRelated)) {
                        recordContractDeleteConflict(
                                orgId, runId, mappingId, sourceId, targetId, concurrentRelated);
                        return -1;
                    }
                    int count = crmJdbcTemplate.update("""
                            DELETE FROM contract
                            WHERE organization_id = :organizationId AND id = :targetId
                            """, new MapSqlParameterSource()
                            .addValue("organizationId", orgId)
                            .addValue("targetId", targetId));
                    crmJdbcTemplate.update("DELETE FROM mls_sync_mapping WHERE id = :mappingId",
                            new MapSqlParameterSource().addValue("mappingId", mappingId));
                    return count;
                });
                if (removed < 0) {
                    result.setContractConflicts(result.getContractConflicts() + 1);
                } else {
                    result.setContractDeleted(result.getContractDeleted() + removed);
                }
            }
        }
    }

    private List<Map<String, Object>> loadCleanupCandidates(String orgId, String sourceTable,
                                                             String targetTable, int limit) {
        return crmJdbcTemplate.queryForList("""
                SELECT id AS mapping_id, previous_source_id, target_id
                FROM mls_sync_mapping
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                  AND target_table = :targetTable
                  AND status = :status
                  AND missing_count >= :requiredMissingPasses
                ORDER BY id
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("targetTable", targetTable)
                .addValue("status", STATUS_STALE)
                .addValue("requiredMissingPasses",
                        MlsMirrorSyncProtection.REQUIRED_CONSECUTIVE_MISSING_PASSES)
                .addValue("limit", limit));
    }

    private String contractRelatedSummary(String contractId) {
        if (StringUtils.isBlank(contractId)) {
            return null;
        }
        Map<String, Object> counts = crmJdbcTemplate.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM sales_order WHERE contract_id = :contractId) AS sales_orders,
                  (SELECT COUNT(*) FROM contract_payment_plan WHERE contract_id = :contractId) AS payment_plans,
                  (SELECT COUNT(*) FROM contract_payment_record WHERE contract_id = :contractId) AS payment_records,
                  (SELECT COUNT(*) FROM contract_invoice WHERE contract_id = :contractId) AS invoices,
                  (SELECT COUNT(*) FROM contract_snapshot WHERE contract_id = :contractId) AS snapshots,
                  (SELECT COUNT(*) FROM contract_field WHERE resource_id = :contractId) AS field_values,
                  (SELECT COUNT(*) FROM contract_field_blob WHERE resource_id = :contractId) AS blob_values
                """, new MapSqlParameterSource().addValue("contractId", contractId));
        List<String> related = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (numericId(count) > 0) {
                related.add(name + "=" + numericId(count));
            }
        });
        return related.isEmpty() ? null : String.join(", ", related);
    }

    private void recordContractDeleteConflict(String orgId, String runId, String mappingId,
                                              String sourceId, String targetId, String related) {
        long now = System.currentTimeMillis();
        String message = "Missing external contract was retained because CRM related data exists: " + related;
        crmJdbcTemplate.update(RUN_ERROR_INSERT_SQL, new MapSqlParameterSource()
                .addValue("id", newId())
                .addValue("runId", runId)
                .addValue("organizationId", orgId)
                .addValue("stage", "contract_cleanup")
                .addValue("sourceTable", SOURCE_CONTRACT)
                .addValue("sourceId", sourceId)
                .addValue("targetTable", TABLE_CONTRACT)
                .addValue("targetId", targetId)
                .addValue("sourceUpdatedAt", null)
                .addValue("status", STATUS_CONFLICT)
                .addValue("errorCode", "DELETE_CONFLICT_RELATED_DATA")
                .addValue("errorMessage", message)
                .addValue("rowPayload", null)
                .addValue("retryable", true)
                .addValue("createTime", now)
                .addValue("updateTime", now));
        crmJdbcTemplate.update("""
                UPDATE mls_sync_mapping
                SET status = :status,
                    last_error = :lastError,
                    last_run_id = :runId,
                    update_time = :updateTime
                WHERE id = :mappingId
                """, new MapSqlParameterSource()
                .addValue("status", STATUS_CONFLICT)
                .addValue("lastError", message)
                .addValue("runId", runId)
                .addValue("updateTime", now)
                .addValue("mappingId", mappingId));
    }

    private List<Map<String, Object>> queryExternalRows(String sourceTable, String sql, long lastId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lastId", lastId)
                .addValue("limit", limit);
        return externalQueryRetry.execute(sourceTable, () -> externalJdbcTemplate.queryForList(sql, params));
    }

    private Map<String, MappingRecord> loadMappings(String orgId, String sourceTable, List<String> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String targetTable = switch (sourceTable) {
            case SOURCE_CUSTOMER -> TABLE_CUSTOMER;
            case SOURCE_CONTRACT -> TABLE_CONTRACT;
            case SOURCE_ORDER -> TABLE_ORDER;
            default -> throw new IllegalArgumentException("Unsupported MLS source table: " + sourceTable);
        };
        String sql = """
                SELECT mapping.id AS mapping_id, mapping.source_id, mapping.previous_source_id,
                       mapping.target_id, mapping.source_hash, mapping.status,
                       mapping.source_updated_at, mapping.update_time AS mapping_updated_at,
                       target_row.id AS existing_target_id,
                       target_row.update_time AS target_updated_at
                FROM mls_sync_mapping mapping
                LEFT JOIN %s target_row
                  ON target_row.id = mapping.target_id
                 AND target_row.organization_id = mapping.organization_id
                 AND mapping.target_table = :targetTable
                WHERE mapping.organization_id = :organizationId
                  AND mapping.source_table = :sourceTable
                  AND mapping.source_id IN (:sourceIds)
                """.formatted(targetTable);
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList(sql, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("targetTable", targetTable)
                .addValue("sourceIds", sourceIds));
        Map<String, MappingRecord> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(text(row.get("source_id")), new MappingRecord(
                    text(row.get("mapping_id")), text(row.get("previous_source_id")),
                    text(row.get("target_id")), text(row.get("source_hash")), text(row.get("status")),
                    toEpochMillis(row.get("source_updated_at")), toEpochMillis(row.get("mapping_updated_at")),
                    StringUtils.isNotBlank(text(row.get("existing_target_id"))),
                    toEpochMillis(row.get("target_updated_at"))));
        }
        return result;
    }

    private boolean targetMatchesLastSync(MappingRecord mapping, boolean targetUsesSourceTimestamp) {
        if (!mapping.targetExists) {
            return false;
        }
        Long expectedUpdateTime = targetUsesSourceTimestamp && mapping.sourceUpdatedAt != null
                ? mapping.sourceUpdatedAt
                : mapping.mappingUpdatedAt;
        return expectedUpdateTime != null && expectedUpdateTime.equals(mapping.targetUpdatedAt);
    }

    private boolean resolveMappedTargetExists(String table, String targetId, String orgId,
                                               MappingRecord mapping) {
        if (mapping == null || !targetId.equals(mapping.targetId)) {
            return targetExists(table, targetId, orgId);
        }
        if (mapping.targetExists || targetExists(table, targetId, orgId)) {
            return true;
        }
        if (targetExistsAny(table, targetId)) {
            throw new SyncRowException("TARGET_ORGANIZATION_CONFLICT",
                    "Mapped " + table + " target belongs to another organization: " + targetId);
        }
        return false;
    }

    private CustomerIndex loadCustomerIndex(String orgId) {
        CustomerIndex index = new CustomerIndex();
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT c.id, c.name, c.full_name, c.customer_source,
                       mapped.target_id AS mapped_target_id
                FROM customer c
                LEFT JOIN (
                    SELECT DISTINCT target_id
                    FROM mls_sync_mapping
                    WHERE organization_id = :organizationId
                      AND source_table = :sourceTable
                      AND target_table = :targetTable
                      AND target_id IS NOT NULL
                ) mapped ON mapped.target_id = c.id
                WHERE c.organization_id = :organizationId
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", SOURCE_CUSTOMER)
                .addValue("targetTable", TABLE_CUSTOMER));
        for (Map<String, Object> row : rows) {
            index.add(new CustomerRef(text(row.get("id")), text(row.get("name")),
                    text(row.get("full_name")), text(row.get("customer_source")),
                    StringUtils.isNotBlank(text(row.get("mapped_target_id")))));
        }
        return index;
    }

    private Set<String> loadSuccessfulTargetIds(String orgId, String sourceTable, String targetTable) {
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT target_id
                FROM mls_sync_mapping
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                  AND target_table = :targetTable
                  AND status = :status
                  AND target_id IS NOT NULL
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("targetTable", targetTable)
                .addValue("status", STATUS_SUCCESS));
        return rows.stream().map(row -> text(row.get("target_id")))
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toSet());
    }

    private CustomerMappingRepair loadCustomerMappingRepair(String orgId) {
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT source_id, target_id
                FROM mls_sync_mapping
                WHERE organization_id = :organizationId
                  AND source_table = :sourceTable
                  AND target_table = :targetTable
                  AND target_id IS NOT NULL
                ORDER BY target_id, source_id
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", SOURCE_CUSTOMER)
                .addValue("targetTable", TABLE_CUSTOMER));
        Map<String, List<String>> sourceIdsByTarget = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String sourceId = text(row.get("source_id"));
            String targetId = text(row.get("target_id"));
            if (StringUtils.isNotBlank(sourceId) && StringUtils.isNotBlank(targetId)) {
                sourceIdsByTarget.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(sourceId);
            }
        }
        Set<String> forceRewrite = new HashSet<>();
        Set<String> remap = new HashSet<>();
        for (List<String> sourceIds : sourceIdsByTarget.values()) {
            if (sourceIds.size() < 2) {
                continue;
            }
            sourceIds.sort(Comparator.comparingLong((String value) -> numericId(value))
                    .thenComparing(Comparator.naturalOrder()));
            forceRewrite.addAll(sourceIds);
            remap.addAll(sourceIds.subList(1, sourceIds.size()));
        }
        if (!remap.isEmpty()) {
            log.warn("MLS found {} customer source mappings sharing another source's target; "
                    + "they will be split into deterministic targets", remap.size());
        }
        return new CustomerMappingRepair(forceRewrite, remap);
    }

    private ContractIndex loadContractIndex(String orgId) {
        ContractIndex index = new ContractIndex();
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT c.id, c.number, c.customer_id, c.owner,
                       mapped.id AS mapping_id, mapped.previous_source_id,
                       mapped.target_id AS mapped_target_id, mapped.source_hash,
                       mapped.status AS mapping_status,
                       mapped.source_updated_at, mapped.update_time AS mapping_updated_at,
                       c.update_time AS target_updated_at
                FROM contract c
                LEFT JOIN mls_sync_mapping mapped
                  ON mapped.target_id = c.id
                 AND mapped.organization_id = :organizationId
                 AND mapped.source_table = :sourceTable
                 AND mapped.target_table = :targetTable
                WHERE c.organization_id = :organizationId
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("sourceTable", SOURCE_CONTRACT)
                .addValue("targetTable", TABLE_CONTRACT));
        for (Map<String, Object> row : rows) {
            String mappingId = text(row.get("mapping_id"));
            MappingRecord mapping = StringUtils.isBlank(mappingId) ? null : new MappingRecord(
                    mappingId, text(row.get("previous_source_id")), text(row.get("mapped_target_id")),
                    text(row.get("source_hash")), text(row.get("mapping_status")),
                    toEpochMillis(row.get("source_updated_at")), toEpochMillis(row.get("mapping_updated_at")),
                    true, toEpochMillis(row.get("target_updated_at")));
            index.add(new ContractRef(text(row.get("id")), text(row.get("number")),
                    text(row.get("customer_id")), text(row.get("owner")),
                    mapping != null, mapping));
        }
        return index;
    }

    private UserIndex loadUserIndex(String orgId) {
        UserIndex index = new UserIndex();
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT DISTINCT su.id, su.name
                FROM sys_user su
                LEFT JOIN sys_organization_user ou
                  ON ou.user_id = su.id AND ou.organization_id = :organizationId
                WHERE ou.organization_id IS NOT NULL OR su.id = :adminId
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("adminId", STATUS_ADMIN));
        index.add(STATUS_ADMIN, STATUS_ADMIN);
        for (Map<String, Object> row : rows) {
            String id = text(row.get("id"));
            String name = text(row.get("name"));
            if (StringUtils.isNotBlank(id)) {
                index.add(id, id);
            }
            if (StringUtils.isNotBlank(name)) {
                index.add(name, id);
            }
        }
        return index;
    }

    private Map<String, String> loadLegacyOrderIds(String orgId) {
        List<Map<String, Object>> rows = crmJdbcTemplate.queryForList("""
                SELECT id, order_no
                FROM sales_order
                WHERE organization_id = :organizationId
                  AND id REGEXP '^[0-9]+$'
                """, new MapSqlParameterSource().addValue("organizationId", orgId));
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String id = text(row.get("id"));
            if (StringUtils.isNotBlank(id)) {
                result.put(id, text(row.get("order_no")));
            }
        }
        return result;
    }

    private void upsertMapping(String orgId, String runId, String sourceTable, String sourceId,
                                String targetTable, String targetId, Long sourceUpdatedAt,
                                String sourceHash, String status, String lastError, long now) {
        crmJdbcTemplate.update(MAPPING_UPSERT_SQL, new MapSqlParameterSource()
                .addValue("mappingId", newId())
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("sourceId", sourceId)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("sourceUpdatedAt", sourceUpdatedAt)
                .addValue("sourceHash", sourceHash)
                .addValue("status", status)
                .addValue("lastError", lastError)
                .addValue("lastRunId", runId)
                .addValue("createTime", now)
                .addValue("updateTime", now));
    }

    private void upsertCheckpoint(String orgId, String sourceTable, Long cursorUpdatedAt, String cursorId,
                                  String runId, String status, String error) {
        long now = System.currentTimeMillis();
        crmJdbcTemplate.update(CHECKPOINT_UPSERT_SQL, new MapSqlParameterSource()
                .addValue("checkpointId", stableId("checkpoint:" + sourceTable, orgId, sourceTable))
                .addValue("organizationId", orgId)
                .addValue("sourceTable", sourceTable)
                .addValue("cursorUpdatedAt", cursorUpdatedAt)
                .addValue("cursorId", cursorId)
                .addValue("lastSuccessRunId", STATUS_SUCCESS.equals(status) ? runId : null)
                .addValue("lastSuccessTime", STATUS_SUCCESS.equals(status) ? now : null)
                .addValue("status", status)
                .addValue("lastError", error)
                .addValue("createTime", now)
                .addValue("updateTime", now));
    }

    private void createRun(String runId, String orgId, String triggerType, long startTime) {
        crmJdbcTemplate.update(RUN_INSERT_SQL, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("organizationId", orgId)
                .addValue("triggerType", limit(triggerType, 32))
                .addValue("status", STATUS_RUNNING)
                .addValue("stage", "customer")
                .addValue("startTime", startTime)
                .addValue("createTime", startTime)
                .addValue("updateTime", startTime));
    }

    private void failAbandonedRuns(String orgId, long now) {
        crmJdbcTemplate.update("""
                UPDATE mls_sync_run
                SET status = 'FAILED',
                    stage = 'aborted',
                    end_time = COALESCE(end_time, :now),
                    error_summary = COALESCE(error_summary, 'Previous process stopped before completion'),
                    update_time = :now
                WHERE organization_id = :organizationId
                  AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId)
                .addValue("now", now));
    }

    private void updateRun(String runId, MlsAgentDataSyncResult result, String status, String stage) {
        if (StringUtils.isBlank(runId)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("runId", runId)
                    .addValue("status", status)
                    .addValue("stage", stage)
                    .addValue("endTime", result.getEndTime())
                    .addValue("customerStartTime", null)
                    .addValue("customerEndTime", null)
                    .addValue("customerRead", result.getCustomerRead())
                    .addValue("customerCreated", result.getCustomerCreated())
                    .addValue("customerUpdated", result.getCustomerUpdated())
                    .addValue("customerSkipped", result.getCustomerSkipped())
                    .addValue("customerFailed", result.getCustomerFailed())
                    .addValue("customerError", errorSummary(result, "customer"))
                    .addValue("contractStartTime", null)
                    .addValue("contractEndTime", null)
                    .addValue("contractRead", result.getContractRead())
                    .addValue("contractCreated", result.getContractCreated())
                    .addValue("contractUpdated", result.getContractUpdated())
                    .addValue("contractSkipped", result.getContractSkipped())
                    .addValue("contractFailed", result.getContractFailed())
                    .addValue("contractDeleted", result.getContractDeleted())
                    .addValue("contractConflicts", result.getContractConflicts())
                    .addValue("contractError", errorSummary(result, "contract"))
                    .addValue("orderStartTime", null)
                    .addValue("orderEndTime", null)
                    .addValue("orderRead", result.getOrderRead())
                    .addValue("orderCreated", result.getOrderCreated())
                    .addValue("orderUpdated", result.getOrderUpdated())
                    .addValue("orderSkipped", result.getOrderSkipped())
                    .addValue("orderFailed", result.getOrderFailed())
                    .addValue("orderDeleted", result.getOrderDeleted())
                    .addValue("orderError", errorSummary(result, "order"))
                    .addValue("errorSummary", result.getWarnings().isEmpty() ? null : String.join("; ", result.getWarnings()))
                    .addValue("mirrorProtectionTriggered", result.isMirrorProtectionTriggered())
                    .addValue("updateTime", now);
            crmJdbcTemplate.update(RUN_UPDATE_SQL, params);
        } catch (Exception e) {
            log.warn("Unable to update MLS run audit, runId={}: {}", runId, safeMessage(e));
        }
    }

    private void updateStageStart(String runId, String stage, long time) {
        String column = stageColumn(stage, "start_time");
        crmJdbcTemplate.update("UPDATE mls_sync_run SET stage = :stage, " + column + " = :time, update_time = :time WHERE run_id = :runId",
                new MapSqlParameterSource().addValue("stage", stage).addValue("time", time).addValue("runId", runId));
    }

    private void updateStageEnd(String runId, String stage, long time) {
        String column = stageColumn(stage, "end_time");
        crmJdbcTemplate.update("UPDATE mls_sync_run SET " + column + " = :time, update_time = :time WHERE run_id = :runId",
                new MapSqlParameterSource().addValue("time", time).addValue("runId", runId));
    }

    private String stageColumn(String stage, String suffix) {
        return switch (stage) {
            case "customer", "contract", "order" -> stage + "_" + suffix;
            default -> throw new IllegalArgumentException("Unsupported MLS stage: " + stage);
        };
    }

    private boolean hasRecentRunningRun(String orgId) {
        try {
            Long count = crmJdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM mls_sync_run
                    WHERE organization_id = :organizationId
                      AND status = 'RUNNING'
                      AND start_time > :minStart
                    """, new MapSqlParameterSource()
                    .addValue("organizationId", orgId)
                    .addValue("minStart", System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12)), Long.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void recordFailure(String orgId, String runId, String stage, String sourceTable, String sourceId,
                               String targetTable, String targetId, Long sourceUpdatedAt, String sourceHash,
                               Exception exception, Map<String, Object> row) {
        recordFailure(orgId, runId, stage, sourceTable, sourceId, targetTable, targetId,
                sourceUpdatedAt, sourceHash, exception, row, true);
    }

    private void recordFailure(String orgId, String runId, String stage, String sourceTable, String sourceId,
                               String targetTable, String targetId, Long sourceUpdatedAt, String sourceHash,
                               Exception exception, Map<String, Object> row, boolean persistFailedMapping) {
        String message = limit(safeMessage(exception), MAX_ERROR_LENGTH);
        String errorCode = exception instanceof SyncRowException sync ? sync.code : "SYNC_ERROR";
        try {
            crmJdbcTemplate.update(RUN_ERROR_INSERT_SQL, new MapSqlParameterSource()
                    .addValue("id", newId())
                    .addValue("runId", runId)
                    .addValue("organizationId", orgId)
                    .addValue("stage", stage)
                    .addValue("sourceTable", sourceTable)
                    .addValue("sourceId", sourceId)
                    .addValue("targetTable", targetTable)
                    .addValue("targetId", targetId)
                    .addValue("sourceUpdatedAt", sourceUpdatedAt)
                    .addValue("status", STATUS_FAILED)
                    .addValue("errorCode", errorCode)
                    .addValue("errorMessage", message)
                    .addValue("rowPayload", null)
                    .addValue("retryable", true)
                    .addValue("createTime", System.currentTimeMillis())
                    .addValue("updateTime", System.currentTimeMillis()));
        } catch (Exception auditError) {
            log.error("Unable to persist MLS row error, stage={}, sourceId={}: {}", stage, sourceId,
                    safeMessage(auditError));
        }
        try {
            if (persistFailedMapping && StringUtils.isNotBlank(sourceId)) {
                upsertMapping(orgId, runId, sourceTable, sourceId, targetTable, targetId,
                        sourceUpdatedAt, sourceHash, STATUS_FAILED, message, System.currentTimeMillis());
            }
        } catch (Exception mappingError) {
            log.error("Unable to persist MLS mapping error, sourceId={}: {}", sourceId, safeMessage(mappingError));
        }
        log.warn("MLS {} row failed, sourceId={}, message={}", stage, sourceId, message);
    }

    private void applyCustomerOutcome(MlsAgentDataSyncResult result, ProcessOutcome outcome) {
        switch (outcome) {
            case CREATED -> result.setCustomerCreated(result.getCustomerCreated() + 1);
            case UPDATED -> result.setCustomerUpdated(result.getCustomerUpdated() + 1);
            case SKIPPED -> result.setCustomerSkipped(result.getCustomerSkipped() + 1);
        }
    }

    private void applyContractOutcome(MlsAgentDataSyncResult result, ProcessOutcome outcome) {
        switch (outcome) {
            case CREATED -> result.setContractCreated(result.getContractCreated() + 1);
            case UPDATED -> result.setContractUpdated(result.getContractUpdated() + 1);
            case SKIPPED -> result.setContractSkipped(result.getContractSkipped() + 1);
        }
    }

    private void applyOrderOutcome(MlsAgentDataSyncResult result, ProcessOutcome outcome) {
        switch (outcome) {
            case CREATED -> result.setOrderCreated(result.getOrderCreated() + 1);
            case UPDATED -> result.setOrderUpdated(result.getOrderUpdated() + 1);
            case SKIPPED -> result.setOrderSkipped(result.getOrderSkipped() + 1);
        }
    }

    private List<String> sourceIds(List<Map<String, Object>> rows) {
        return rows.stream().map(this::sourceId).filter(StringUtils::isNotBlank).toList();
    }

    private String sourceId(Map<String, Object> row) {
        return text(row.get("id"));
    }

    private long numericId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return StringUtils.isBlank(text(value)) ? 0 : Long.parseLong(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean targetExists(String table, String id, String orgId) {
        if (StringUtils.isBlank(id)) {
            return false;
        }
        String safeTable = switch (table) {
            case TABLE_CUSTOMER, TABLE_CONTRACT, TABLE_ORDER -> table;
            default -> throw new IllegalArgumentException("Unsupported target table: " + table);
        };
        Long count = crmJdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + safeTable
                        + " WHERE id = :id AND organization_id = :organizationId",
                new MapSqlParameterSource().addValue("id", id).addValue("organizationId", orgId), Long.class);
        return count != null && count > 0;
    }

    private boolean targetExistsAny(String table, String id) {
        if (StringUtils.isBlank(id)) {
            return false;
        }
        String safeTable = switch (table) {
            case TABLE_CUSTOMER, TABLE_CONTRACT, TABLE_ORDER -> table;
            default -> throw new IllegalArgumentException("Unsupported target table: " + table);
        };
        Long count = crmJdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + safeTable + " WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id), Long.class);
        return count != null && count > 0;
    }

    private String stableId(String namespace, String orgId, String sourceId) {
        return UUID.nameUUIDFromBytes((namespace + ":" + orgId + ":" + sourceId)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String hashValues(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                String text = canonical(value);
                digest.update((text.length() + ":" + text + "|").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormatHolder.toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String canonical(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value.toString().trim();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        return StringUtils.trimToNull(text);
    }

    private String usable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.length == 0 || bytes[0] == 0 ? "0" : "1";
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Number number) {
            return number.intValue() == 0 ? "0" : "1";
        }
        String valueText = text(value);
        if (valueText == null) {
            return null;
        }
        if ("0".equals(valueText) || "false".equalsIgnoreCase(valueText)) {
            return "0";
        }
        return "1";
    }

    private String auditUser(Object primary, Object... fallbacks) {
        String value = text(primary);
        if (StringUtils.isNotBlank(value)) {
            return limit(value, 50);
        }
        for (Object fallback : fallbacks) {
            value = text(fallback);
            if (StringUtils.isNotBlank(value)) {
                return limit(value, 50);
            }
        }
        return STATUS_ADMIN;
    }

    private Long defaultTime(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new SyncRowException("INVALID_NUMBER", "无法转换金额/数量: " + value);
        }
    }

    private Long toEpochMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(MLS_ZONE_ID).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay(MLS_ZONE_ID).toInstant().toEpochMilli();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return normalizeEpoch(number.longValue());
        }
        String valueText = text(value);
        if (StringUtils.isBlank(valueText)) {
            return null;
        }
        if (StringUtils.isNumeric(valueText)) {
            return normalizeEpoch(Long.parseLong(valueText));
        }
        try {
            return Timestamp.valueOf(valueText).getTime();
        } catch (IllegalArgumentException ignored) {
            // Try the less strict formats below.
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:m"))) {
            try {
                return LocalDateTime.parse(valueText, formatter).atZone(MLS_ZONE_ID).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy.M.d"))) {
            try {
                return LocalDate.parse(valueText, formatter).atStartOfDay(MLS_ZONE_ID).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try next format.
            }
        }
        throw new SyncRowException("INVALID_TIME", "无法转换日期: " + valueText);
    }

    private long normalizeEpoch(long value) {
        return Math.abs(value) < 100_000_000_000L ? value * 1000 : value;
    }

    private int normalizePageSize(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(value, MAX_PAGE_SIZE);
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return StringUtils.defaultIfBlank(message, throwable == null ? "unknown error" : throwable.getClass().getSimpleName());
    }

    private String errorSummary(MlsAgentDataSyncResult result, String stage) {
        long failureCount = switch (stage) {
            case "customer" -> result.getCustomerFailed();
            case "contract" -> result.getContractFailed();
            case "order" -> result.getOrderFailed();
            default -> throw new IllegalArgumentException("Unsupported MLS stage: " + stage);
        };
        return failureCount == 0 ? null
                : stage + " failed rows: " + failureCount + "; see mls_sync_run_error";
    }

    private void recordOwnerCandidate(Map<String, OwnerCandidate> candidates, String customerId,
                                      String owner, long updateTime, String sourceId) {
        if (StringUtils.isBlank(customerId)) {
            return;
        }
        OwnerCandidate current = candidates.get(customerId);
        OwnerCandidate incoming = new OwnerCandidate(owner, updateTime, numericId(sourceId));
        if (current == null || incoming.compareTo(current) > 0) {
            candidates.put(customerId, incoming);
        }
    }

    private enum ProcessOutcome { CREATED, UPDATED, SKIPPED }

    private record MirrorStageState(String sourceTable, String targetTable,
                                    boolean started, boolean complete) {
        private static MirrorStageState blocked(String sourceTable, String targetTable) {
            return new MirrorStageState(sourceTable, targetTable, false, false);
        }

        private boolean cleanupEligible() {
            return started && complete;
        }
    }

    private record PreparedOrder(Map<String, Object> row, String sourceId, String orderNo,
                                 ContractRef contract, String hash, int occurrence) {
    }

    private record OrderMappingCandidate(MappingRecord mapping, String targetOrderNo) {
    }

    private static final class OrderMappingPool {
        private final Map<String, List<OrderMappingCandidate>> byPreviousSourceId = new HashMap<>();
        private final Map<String, List<OrderMappingCandidate>> byHash = new HashMap<>();
        private final Set<String> usedMappingIds = new HashSet<>();

        private OrderMappingPool(List<OrderMappingCandidate> candidates) {
            for (OrderMappingCandidate candidate : candidates) {
                add(byPreviousSourceId, candidate.mapping.previousSourceId, candidate);
                add(byHash, candidate.mapping.sourceHash, candidate);
            }
        }

        private MappingRecord take(PreparedOrder order) {
            OrderMappingCandidate candidate = takeFirst(
                    byPreviousSourceId.get(order.sourceId),
                    value -> order.hash.equals(value.mapping.sourceHash));
            if (candidate == null) {
                candidate = takeFirst(byHash.get(order.hash), value -> true);
            }
            if (candidate == null) {
                candidate = takeFirst(byPreviousSourceId.get(order.sourceId),
                        value -> normalize(order.orderNo).equals(normalize(value.targetOrderNo)));
            }
            if (candidate == null) {
                return null;
            }
            usedMappingIds.add(candidate.mapping.mappingId);
            return candidate.mapping;
        }

        private OrderMappingCandidate takeFirst(List<OrderMappingCandidate> candidates,
                                                java.util.function.Predicate<OrderMappingCandidate> predicate) {
            if (candidates == null) {
                return null;
            }
            return candidates.stream()
                    .filter(value -> !usedMappingIds.contains(value.mapping.mappingId))
                    .filter(predicate)
                    .findFirst()
                    .orElse(null);
        }

        private void add(Map<String, List<OrderMappingCandidate>> index, String key,
                         OrderMappingCandidate candidate) {
            if (StringUtils.isNotBlank(key)) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
            }
        }
    }

    private static final class MappingRecord {
        private final String mappingId;
        private final String previousSourceId;
        private final String targetId;
        private final String sourceHash;
        private final String status;
        private final Long sourceUpdatedAt;
        private final Long mappingUpdatedAt;
        private final boolean targetExists;
        private final Long targetUpdatedAt;

        private MappingRecord(String mappingId, String previousSourceId,
                              String targetId, String sourceHash, String status,
                              Long sourceUpdatedAt, Long mappingUpdatedAt,
                              boolean targetExists, Long targetUpdatedAt) {
            this.mappingId = mappingId;
            this.previousSourceId = previousSourceId;
            this.targetId = targetId;
            this.sourceHash = sourceHash;
            this.status = status;
            this.sourceUpdatedAt = sourceUpdatedAt;
            this.mappingUpdatedAt = mappingUpdatedAt;
            this.targetExists = targetExists;
            this.targetUpdatedAt = targetUpdatedAt;
        }
    }

    private record CustomerMappingRepair(Set<String> forceRewriteSourceIds, Set<String> remapSourceIds) {
    }

    private record CustomerRef(String id, String name, String fullName, String source, boolean mapped) {
    }

    private static final class CustomerIndex {
        private final Map<String, List<CustomerRef>> names = new HashMap<>();
        private final Map<String, List<CustomerRef>> fullNames = new HashMap<>();
        private final Map<String, CustomerRef> byId = new HashMap<>();

        private void add(CustomerRef ref) {
            if (ref == null || StringUtils.isBlank(ref.id)) {
                return;
            }
            CustomerRef previous = byId.put(ref.id, ref);
            if (previous != null) {
                removeName(names, previous.name, ref.id);
                removeName(fullNames, previous.fullName, ref.id);
            }
            addName(names, ref.name, ref);
            addName(fullNames, ref.fullName, ref);
        }

        private void addName(Map<String, List<CustomerRef>> index, String name, CustomerRef ref) {
            String key = normalize(name);
            if (key != null) {
                List<CustomerRef> refs = index.computeIfAbsent(key, ignored -> new ArrayList<>());
                refs.removeIf(value -> value.id.equals(ref.id));
                refs.add(ref);
            }
        }

        private void removeName(Map<String, List<CustomerRef>> index, String name, String id) {
            String key = normalize(name);
            if (key != null) {
                List<CustomerRef> refs = index.get(key);
                if (refs != null) {
                    refs.removeIf(value -> value.id.equals(id));
                    if (refs.isEmpty()) {
                        index.remove(key);
                    }
                }
            }
        }

        private CustomerRef uniqueUnmappedCompanyMatch(String name) {
            String key = normalize(name);
            if (key == null) {
                return null;
            }
            List<CustomerRef> primary = companyCandidates(names, key, false);
            if (!primary.isEmpty()) {
                return primary.size() == 1 ? primary.getFirst() : null;
            }
            List<CustomerRef> full = companyCandidates(fullNames, key, false);
            return full.size() == 1 ? full.getFirst() : null;
        }

        private CustomerRef resolve(String name) {
            String key = normalize(name);
            if (key == null) {
                return null;
            }
            List<CustomerRef> mappedPrimary = companyCandidates(names, key, true);
            if (!mappedPrimary.isEmpty()) {
                return mappedPrimary.size() == 1 ? mappedPrimary.getFirst() : null;
            }
            List<CustomerRef> mappedFull = companyCandidates(fullNames, key, true);
            if (!mappedFull.isEmpty()) {
                return mappedFull.size() == 1 ? mappedFull.getFirst() : null;
            }
            List<CustomerRef> primary = companyCandidates(names, key, null);
            if (!primary.isEmpty()) {
                return primary.size() == 1 ? primary.getFirst() : null;
            }
            List<CustomerRef> full = companyCandidates(fullNames, key, null);
            return full.size() == 1 ? full.getFirst() : null;
        }

        private List<CustomerRef> companyCandidates(Map<String, List<CustomerRef>> index,
                                                     String key, Boolean mapped) {
            return index.getOrDefault(key, List.of()).stream()
                    .filter(value -> CUSTOMER_SOURCE_COMPANY.equals(value.source))
                    .filter(value -> mapped == null || value.mapped == mapped)
                    .toList();
        }
    }

    private record ContractRef(String id, String number, String customerId, String owner,
                               boolean mapped, MappingRecord mapping) {
    }

    private static final class ContractIndex {
        private final Map<String, List<ContractRef>> numbers = new HashMap<>();
        private final Map<String, ContractRef> byId = new HashMap<>();

        private void add(ContractRef ref) {
            if (ref == null || StringUtils.isBlank(ref.id) || StringUtils.isBlank(ref.number)) {
                return;
            }
            ContractRef previous = byId.put(ref.id, ref);
            if (previous != null) {
                String previousKey = normalize(previous.number);
                List<ContractRef> previousRefs = numbers.get(previousKey);
                if (previousRefs != null) {
                    previousRefs.removeIf(value -> value.id.equals(ref.id));
                    if (previousRefs.isEmpty()) {
                        numbers.remove(previousKey);
                    }
                }
            }
            String key = normalize(ref.number);
            List<ContractRef> refs = numbers.computeIfAbsent(key, ignored -> new ArrayList<>());
            refs.removeIf(value -> value.id.equals(ref.id));
            refs.add(ref);
        }

        private ContractRef uniqueUnmappedMatch(String number, String customerId) {
            String key = normalize(number);
            if (key == null) {
                return null;
            }
            List<ContractRef> refs = numbers.getOrDefault(key, List.of()).stream()
                    .filter(value -> !value.mapped)
                    .filter(value -> customerId == null || customerId.equals(value.customerId))
                    .toList();
            return refs.size() == 1 ? refs.getFirst() : null;
        }

        private ContractRef uniqueMappedMatch(String number, String customerId) {
            String key = normalize(number);
            if (key == null) {
                return null;
            }
            List<ContractRef> refs = numbers.getOrDefault(key, List.of()).stream()
                    .filter(ContractRef::mapped)
                    .filter(value -> customerId == null || customerId.equals(value.customerId))
                    .toList();
            return refs.size() == 1 ? refs.getFirst() : null;
        }

        private ContractRef resolveForOrder(String number) {
            String key = normalize(number);
            if (key == null) {
                return null;
            }
            List<ContractRef> refs = numbers.getOrDefault(key, List.of());
            List<ContractRef> mapped = refs.stream().filter(value -> value.mapped).toList();
            if (!mapped.isEmpty()) {
                return mapped.size() == 1 ? mapped.getFirst() : null;
            }
            return refs.size() == 1 ? refs.getFirst() : null;
        }
    }

    private static final class UserIndex {
        private final Map<String, Set<String>> values = new HashMap<>();

        private void add(String value, String id) {
            String key = normalize(value);
            if (key != null && StringUtils.isNotBlank(id)) {
                values.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
            }
        }

        private String resolve(String value) {
            String key = normalize(value);
            if (key == null) {
                return STATUS_ADMIN;
            }
            Set<String> ids = values.getOrDefault(key, Set.of());
            return ids.size() == 1 ? ids.iterator().next() : STATUS_ADMIN;
        }
    }

    private static final class OwnerCandidate implements Comparable<OwnerCandidate> {
        private final String owner;
        private final long updateTime;
        private final long sourceId;

        private OwnerCandidate(String owner, long updateTime, long sourceId) {
            this.owner = owner;
            this.updateTime = updateTime;
            this.sourceId = sourceId;
        }

        @Override
        public int compareTo(OwnerCandidate other) {
            int time = Long.compare(updateTime, other.updateTime);
            return time == 0 ? Long.compare(sourceId, other.sourceId) : time;
        }
    }

    private static final class SyncRowException extends RuntimeException {
        private final String code;

        private SyncRowException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static String normalize(String value) {
        String normalized = StringUtils.trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static final class HexFormatHolder {
        private static String toHex(byte[] bytes) {
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                builder.append(Character.forDigit(value & 0x0f, 16));
            }
            return builder.toString();
        }
    }
}
