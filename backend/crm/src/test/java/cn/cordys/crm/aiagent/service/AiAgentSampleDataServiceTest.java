package cn.cordys.crm.aiagent.service;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentSampleDataServiceTest {
    @Test void readsThreeRowsTenColumnsAndClipsLongStringsWithoutRunningTotalCount() {
        var sources=mock(AiAgentDatasourceQueryService.class);
        var service=new AiAgentSampleDataService(null,null,sources,null);
        var context=new AiAgentContext(); context.setDatasourceId("source"); context.setDatasourceType("MYSQL"); context.setSelectedQueryTables(List.of("products"));
        var table=new Table(); table.setName("products"); var columns=new ArrayList<Column>();
        for(int i=0;i<12;i++){var c=new Column(); c.setName("field_"+i); columns.add(c);} table.setColumns(columns); context.setDatasourceSchema(List.of(table));
        var result=new AiAgentQueryResult();
        for(int i=0;i<4;i++) {
            var row=new LinkedHashMap<String,Object>(); for(int j=0;j<10;j++) row.put("sample_"+j,j==0 ? "x".repeat(150) : j); result.getRows().add(row);
        }
        when(sources.execute(anyString(),same(context),eq(3),eq(0L),eq(false))).thenReturn(result);
        String data=service.read(context);
        List<?> rows=JSON.parseObject(data.substring(data.indexOf('[')),List.class);
        assertThat(rows).hasSize(3);
        assertThat((Map<?,?>)rows.getFirst()).hasSize(10);
        assertThat(((Map<?,?>)rows.getFirst()).get("field_0").toString()).hasSize(100);
        verify(sources).execute(argThat(sql -> sql.contains("field_9") && !sql.contains("field_10")),same(context),eq(3),eq(0L),eq(false));
    }
    @Test void missingSamplesDoNotBlockQueries() {
        var sources=mock(AiAgentDatasourceQueryService.class); var service=new AiAgentSampleDataService(null,null,sources,null);
        var c=new AiAgentContext(); c.setDatasourceId("source"); c.setSelectedQueryTables(List.of("missing"));
        assertThat(service.read(c)).isEmpty();
    }
}
