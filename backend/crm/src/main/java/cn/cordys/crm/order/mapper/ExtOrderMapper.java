package cn.cordys.crm.order.mapper;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.dto.condition.BaseCondition;
import cn.cordys.crm.order.domain.Order;
import cn.cordys.crm.order.dto.request.OrderPageRequest;
import cn.cordys.crm.order.dto.response.OrderGetResponse;
import cn.cordys.crm.order.dto.response.OrderListResponse;
import cn.cordys.crm.order.dto.response.OrderStatisticResponse;
import cn.cordys.crm.order.dto.response.OrderSummaryResponse;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExtOrderMapper {


    List<OrderListResponse> list(@Param("request") OrderPageRequest request, @Param("orgId") String orgId,
                                 @Param("userId") String userId, @Param("dataPermission") DeptDataPermissionDTO deptDataPermission, @Param("source") boolean source);

    OrderGetResponse getDetail(@Param("id") String id);

    List<OrderListResponse> getListByIds(@Param("ids") List<String> ids, @Param("userId") String userId, @Param("orgId") String orgId, @Param("dataPermission") DeptDataPermissionDTO deptDataPermission);

    int countByStage(@Param("stage") String stage);

    OrderStatisticResponse searchStatistic(@Param("request") BaseCondition request, @Param("orgId") String orgId, @Param("userId") String userId, @Param("dataPermission") DeptDataPermissionDTO dataPermission);

    List<OrderSummaryResponse> summaryList(@Param("request") OrderPageRequest request, @Param("orgId") String orgId,
                                           @Param("userId") String userId, @Param("dataPermission") DeptDataPermissionDTO deptDataPermission);

    Order findByOrderInfoColumns(@Param("order") Order order, @Param("orgId") String orgId);

    int updateContractRelation(@Param("order") Order order);
}
