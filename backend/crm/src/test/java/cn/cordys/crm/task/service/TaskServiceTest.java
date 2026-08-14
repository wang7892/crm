package cn.cordys.crm.task.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.crm.aiagent.service.AiAgentLlmClient;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.task.constants.TaskStatus;
import cn.cordys.crm.task.domain.CrmTask;
import cn.cordys.crm.task.domain.CrmTaskAttachment;
import cn.cordys.crm.task.dto.request.TaskAddRequest;
import cn.cordys.crm.task.dto.request.TaskReassignRequest;
import cn.cordys.crm.task.dto.request.TaskReportSaveRequest;
import cn.cordys.crm.task.dto.response.TaskListResponse;
import cn.cordys.crm.task.mapper.ExtTaskMapper;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceTest {

    private static final String ORGANIZATION_ID = "org-1";
    private static final String ASSIGNEE_ID = "user-1";

    private TaskService service;
    private BaseMapper<CrmTask> taskMapper;
    private BaseMapper<CrmTaskAttachment> taskAttachmentMapper;
    private BaseMapper<Customer> customerMapper;
    private ExtTaskMapper extTaskMapper;
    private AiAgentLlmClient aiAgentLlmClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new TaskService();
        taskMapper = mock(BaseMapper.class);
        taskAttachmentMapper = mock(BaseMapper.class);
        customerMapper = mock(BaseMapper.class);
        extTaskMapper = mock(ExtTaskMapper.class);
        aiAgentLlmClient = mock(AiAgentLlmClient.class);

        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "taskAttachmentMapper", taskAttachmentMapper);
        ReflectionTestUtils.setField(service, "customerMapper", customerMapper);
        ReflectionTestUtils.setField(service, "extTaskMapper", extTaskMapper);
        ReflectionTestUtils.setField(service, "aiAgentLlmClient", aiAgentLlmClient);
        ReflectionTestUtils.setField(service, "taskAttachmentStorageService",
                mock(TaskAttachmentStorageService.class));
    }

    @Test
    void shouldRejectReportWithoutTextOrAttachment() {
        CrmTask task = task(TaskStatus.IN_PROGRESS);
        when(taskMapper.selectByPrimaryKey(task.getId())).thenReturn(task);
        when(taskAttachmentMapper.countByExample(any(CrmTaskAttachment.class))).thenReturn(0L);
        TaskReportSaveRequest request = reportRequest(task.getId(), "   ");

        assertThatThrownBy(() -> service.submitReport(request, ASSIGNEE_ID, ORGANIZATION_ID, false))
                .isInstanceOf(GenericException.class);

        verify(taskMapper, never()).updateById(any(CrmTask.class));
    }

    @Test
    void shouldCompleteTaskWhenReportContainsText() {
        CrmTask task = task(TaskStatus.IN_PROGRESS);
        when(taskMapper.selectByPrimaryKey(task.getId())).thenReturn(task);
        when(taskAttachmentMapper.countByExample(any(CrmTaskAttachment.class))).thenReturn(0L);
        stubTaskResponse(task.getId(), TaskStatus.COMPLETED, task.getDeadline());

        TaskListResponse result = service.submitReport(
                reportRequest(task.getId(), "Customer confirmed the next step."),
                ASSIGNEE_ID, ORGANIZATION_ID, false);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.COMPLETED.name());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED.name());
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getReportSubmittedAt()).isNotNull();
        verify(taskMapper).updateById(task);
    }

    @Test
    void shouldRejectReassigningCompletedTask() {
        CrmTask task = task(TaskStatus.COMPLETED);
        when(taskMapper.selectByPrimaryKey(task.getId())).thenReturn(task);
        TaskReassignRequest request = new TaskReassignRequest();
        request.setId(task.getId());
        request.setAssigneeId("user-2");

        assertThatThrownBy(() -> service.reassign(request, "manager-1", ORGANIZATION_ID))
                .isInstanceOf(GenericException.class);

        verify(taskMapper, never()).updateById(any(CrmTask.class));
    }

    @Test
    void shouldRejectExecutionByNonAssignee() {
        CrmTask task = task(TaskStatus.PENDING);
        when(taskMapper.selectByPrimaryKey(task.getId())).thenReturn(task);

        assertThatThrownBy(() -> service.start(task.getId(), "user-2", ORGANIZATION_ID, false))
                .isInstanceOf(GenericException.class);

        verify(taskMapper, never()).updateById(any(CrmTask.class));
    }

    @Test
    void shouldExposeOverdueStatusWithoutPersistingIt() {
        CrmTask task = task(TaskStatus.PENDING);
        task.setDeadline(System.currentTimeMillis() - 1_000);
        when(taskMapper.selectByPrimaryKey(task.getId())).thenReturn(task);
        stubTaskResponse(task.getId(), TaskStatus.PENDING, task.getDeadline());

        TaskListResponse result = service.get(task.getId(), ASSIGNEE_ID, ORGANIZATION_ID, false);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.OVERDUE.name());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING.name());
        verify(taskMapper, never()).updateById(any(CrmTask.class));
    }

    @Test
    void shouldCreateTaskWhenAiReplyGenerationFails() {
        when(extTaskMapper.countTaskExecutor(ORGANIZATION_ID, ASSIGNEE_ID)).thenReturn(1);
        when(aiAgentLlmClient.chatText(anyString(), anyString(), eq(null)))
                .thenThrow(new IllegalStateException("LLM unavailable"));
        when(extTaskMapper.selectTaskById(anyString(), eq(ORGANIZATION_ID)))
                .thenAnswer(invocation -> response(invocation.getArgument(0), TaskStatus.PENDING,
                        System.currentTimeMillis() + 60_000));
        when(taskAttachmentMapper.selectListByLambda(any())).thenReturn(List.of());

        TaskAddRequest request = new TaskAddRequest();
        request.setName("Follow up customer");
        request.setAssigneeId(ASSIGNEE_ID);
        request.setDescription("Confirm delivery plan");
        request.setDeadline(System.currentTimeMillis() + 60_000);

        TaskListResponse result;
        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("task-new");
            result = service.add(request, "manager-1", ORGANIZATION_ID);
        }

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getAiReply()).isNull();
        assertThat(result.getId()).isEqualTo(captor.getValue().getId());
    }

    @Test
    void shouldCreateShipmentTaskForValidContactSpecialistAndCustomer() {
        Customer customer = customer("customer-1");
        when(extTaskMapper.countTaskBusinessKey(ORGANIZATION_ID, "SHIPMENT:order-row-1:1000")).thenReturn(0);
        when(extTaskMapper.countCustomer(ORGANIZATION_ID, customer.getId())).thenReturn(1);
        when(extTaskMapper.countContactSpecialist(ORGANIZATION_ID, ASSIGNEE_ID)).thenReturn(1);
        when(customerMapper.selectByPrimaryKey(customer.getId())).thenReturn(customer);
        when(aiAgentLlmClient.chatText(anyString(), anyString(), eq(null))).thenReturn("订单已经发货，请注意查收。");
        when(extTaskMapper.insertShipmentTask(any(CrmTask.class))).thenReturn(1);

        boolean created;
        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            created = service.createShipmentTask(shipmentCommand(ASSIGNEE_ID, customer.getId()));
        }

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(extTaskMapper).insertShipmentTask(captor.capture());
        assertThat(created).isTrue();
        assertThat(captor.getValue().getAssigneeId()).isEqualTo(ASSIGNEE_ID);
        assertThat(captor.getValue().getCustomerId()).isEqualTo(customer.getId());
        assertThat(captor.getValue().getDeadline()).isEqualTo(86_405_000L);
        assertThat(captor.getValue().getBusinessKey()).isEqualTo("SHIPMENT:order-row-1:1000");
    }

    @Test
    void shouldLeaveShipmentTaskForManagerWhenContactSpecialistIsMissing() {
        Customer customer = customer("customer-1");
        when(extTaskMapper.countCustomer(ORGANIZATION_ID, customer.getId())).thenReturn(1);
        when(extTaskMapper.countContactSpecialist(ORGANIZATION_ID, "admin")).thenReturn(0);
        when(customerMapper.selectByPrimaryKey(customer.getId())).thenReturn(customer);
        when(extTaskMapper.insertShipmentTask(any(CrmTask.class))).thenReturn(1);

        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            service.createShipmentTask(shipmentCommand("admin", customer.getId()));
        }

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(extTaskMapper).insertShipmentTask(captor.capture());
        assertThat(captor.getValue().getAssigneeId()).isNull();
        assertThat(captor.getValue().getCustomerId()).isEqualTo(customer.getId());
        assertThat(captor.getValue().getDescription()).contains("数据库中没有联系专员");
    }

    @Test
    void shouldLeaveShipmentTaskForManagerWithoutCustomerRelationWhenCustomerIsMissing() {
        when(extTaskMapper.countCustomer(ORGANIZATION_ID, "missing-customer")).thenReturn(0);
        when(extTaskMapper.insertShipmentTask(any(CrmTask.class))).thenReturn(1);

        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            service.createShipmentTask(shipmentCommand(ASSIGNEE_ID, "missing-customer"));
        }

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(extTaskMapper).insertShipmentTask(captor.capture());
        assertThat(captor.getValue().getAssigneeId()).isNull();
        assertThat(captor.getValue().getCustomerId()).isNull();
        assertThat(captor.getValue().getDescription()).contains("数据库中没有客户");
        verify(extTaskMapper, never()).countContactSpecialist(anyString(), anyString());
    }

    @Test
    void shouldKeepContractLookupIssueInManagerAssignmentDescription() {
        when(extTaskMapper.insertShipmentTask(any(CrmTask.class))).thenReturn(1);
        TaskService.ShipmentTaskCommand command = new TaskService.ShipmentTaskCommand(
                ORGANIZATION_ID, "SHIPMENT_ORDER:key:2026-08-11", "SO-001", null, null,
                1_000L, 5_000L, "待分配原因：同一合同编号匹配到多个合同，无法确定客户和联系专员。");

        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            service.createShipmentTask(command);
        }

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(extTaskMapper).insertShipmentTask(captor.capture());
        assertThat(captor.getValue().getAssigneeId()).isNull();
        assertThat(captor.getValue().getCustomerId()).isNull();
        assertThat(captor.getValue().getDescription()).contains("同一合同编号匹配到多个合同");
    }

    @Test
    void shouldSkipExistingShipmentTaskBeforeCallingLlm() {
        when(extTaskMapper.countTaskBusinessKey(ORGANIZATION_ID, "SHIPMENT:order-row-1:1000")).thenReturn(1);

        boolean created = service.createShipmentTask(shipmentCommand(ASSIGNEE_ID, "customer-1"));

        assertThat(created).isFalse();
        verify(aiAgentLlmClient, never()).chatText(anyString(), anyString(), eq(null));
        verify(extTaskMapper, never()).insertShipmentTask(any(CrmTask.class));
    }

    @Test
    void shouldTreatConcurrentShipmentTaskInsertAsDuplicate() {
        Customer customer = customer("customer-1");
        when(extTaskMapper.countCustomer(ORGANIZATION_ID, customer.getId())).thenReturn(1);
        when(extTaskMapper.countContactSpecialist(ORGANIZATION_ID, ASSIGNEE_ID)).thenReturn(1);
        when(customerMapper.selectByPrimaryKey(customer.getId())).thenReturn(customer);
        doThrow(new DuplicateKeyException("duplicate business key"))
                .when(extTaskMapper).insertShipmentTask(any(CrmTask.class));

        boolean created;
        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            created = service.createShipmentTask(shipmentCommand(ASSIGNEE_ID, customer.getId()));
        }

        assertThat(created).isFalse();
    }

    @Test
    void shouldCreateShipmentTaskWhenAiReplyGenerationFails() {
        Customer customer = customer("customer-1");
        when(extTaskMapper.countCustomer(ORGANIZATION_ID, customer.getId())).thenReturn(1);
        when(extTaskMapper.countContactSpecialist(ORGANIZATION_ID, ASSIGNEE_ID)).thenReturn(1);
        when(customerMapper.selectByPrimaryKey(customer.getId())).thenReturn(customer);
        when(aiAgentLlmClient.chatText(anyString(), anyString(), eq(null)))
                .thenThrow(new IllegalStateException("LLM unavailable"));
        when(extTaskMapper.insertShipmentTask(any(CrmTask.class))).thenReturn(1);

        boolean created;
        try (MockedStatic<IDGenerator> idGenerator = mockStatic(IDGenerator.class)) {
            idGenerator.when(IDGenerator::nextStr).thenReturn("shipment-task-1");
            created = service.createShipmentTask(shipmentCommand(ASSIGNEE_ID, customer.getId()));
        }
        assertThat(created).isTrue();

        ArgumentCaptor<CrmTask> captor = ArgumentCaptor.forClass(CrmTask.class);
        verify(extTaskMapper).insertShipmentTask(captor.capture());
        assertThat(captor.getValue().getAiReply()).isNull();
    }

    private void stubTaskResponse(String id, TaskStatus status, long deadline) {
        when(extTaskMapper.selectTaskById(id, ORGANIZATION_ID)).thenReturn(response(id, status, deadline));
        when(taskAttachmentMapper.selectListByLambda(any())).thenReturn(List.of());
    }

    private CrmTask task(TaskStatus status) {
        CrmTask task = new CrmTask();
        task.setId("task-1");
        task.setOrganizationId(ORGANIZATION_ID);
        task.setAssigneeId(ASSIGNEE_ID);
        task.setStatus(status.name());
        task.setDeadline(System.currentTimeMillis() + 60_000);
        return task;
    }

    private TaskReportSaveRequest reportRequest(String id, String content) {
        TaskReportSaveRequest request = new TaskReportSaveRequest();
        request.setId(id);
        request.setReportContent(content);
        return request;
    }

    private TaskListResponse response(String id, TaskStatus status, long deadline) {
        TaskListResponse response = new TaskListResponse();
        response.setId(id);
        response.setStatus(status.name());
        response.setDeadline(deadline);
        return response;
    }

    private Customer customer(String id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setOrganizationId(ORGANIZATION_ID);
        customer.setName("Test Customer");
        return customer;
    }

    private TaskService.ShipmentTaskCommand shipmentCommand(String ownerId, String customerId) {
        return new TaskService.ShipmentTaskCommand(ORGANIZATION_ID, "SHIPMENT:order-row-1:1000",
                "SO-001", ownerId, customerId, 1_000L, 5_000L, null);
    }
}
