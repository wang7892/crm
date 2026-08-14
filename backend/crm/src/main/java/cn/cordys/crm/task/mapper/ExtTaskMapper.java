package cn.cordys.crm.task.mapper;

import cn.cordys.common.dto.OptionDTO;
import cn.cordys.crm.task.domain.CrmTask;
import cn.cordys.crm.task.dto.request.TaskPageRequest;
import cn.cordys.crm.task.dto.response.TaskListResponse;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExtTaskMapper {

    List<TaskListResponse> selectTaskPage(@Param("request") TaskPageRequest request,
                                          @Param("organizationId") String organizationId,
                                          @Param("userId") String userId,
                                          @Param("manager") boolean manager,
                                          @Param("now") long now);

    TaskListResponse selectTaskById(@Param("id") String id,
                                    @Param("organizationId") String organizationId);

    List<OptionDTO> selectTaskExecutorOptions(@Param("organizationId") String organizationId);

    int countTaskExecutor(@Param("organizationId") String organizationId,
                          @Param("userId") String userId);

    int countCustomer(@Param("organizationId") String organizationId,
                      @Param("customerId") String customerId);

    int countContactSpecialist(@Param("organizationId") String organizationId,
                               @Param("userId") String userId);

    int countTaskBusinessKey(@Param("organizationId") String organizationId,
                             @Param("businessKey") String businessKey);

    int insertShipmentTask(@Param("task") CrmTask task);

    List<OptionDTO> selectCustomerOptions(@Param("organizationId") String organizationId,
                                          @Param("keyword") String keyword);
}
