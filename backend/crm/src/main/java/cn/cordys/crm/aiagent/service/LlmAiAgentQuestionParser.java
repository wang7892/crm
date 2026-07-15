package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class LlmAiAgentQuestionParser {

    private static final Logger log = LoggerFactory.getLogger(LlmAiAgentQuestionParser.class);

    private final AiAgentLlmProperties properties;
    private final AiAgentLlmClient aiAgentLlmClient;
    private final AiAgentIntentValidator intentValidator;
    private final AiAgentSemanticSchemaService schemaService;
    private final AiAgentParsedQuestionNormalizer parsedQuestionNormalizer;

    public LlmAiAgentQuestionParser(AiAgentLlmProperties properties,
                                    AiAgentLlmClient aiAgentLlmClient,
                                    AiAgentIntentValidator intentValidator,
                                    AiAgentSemanticSchemaService schemaService,
                                    AiAgentParsedQuestionNormalizer parsedQuestionNormalizer) {
        this.properties = properties;
        this.aiAgentLlmClient = aiAgentLlmClient;
        this.intentValidator = intentValidator;
        this.schemaService = schemaService;
        this.parsedQuestionNormalizer = parsedQuestionNormalizer;
    }

    public ParsedAiAgentQuestion parse(String rawQuestion) {
        return parse(rawQuestion, null);
    }

    public ParsedAiAgentQuestion parse(String rawQuestion, String preferredProvider) {
        return parse(rawQuestion, preferredProvider, null);
    }

    public ParsedAiAgentQuestion parse(String rawQuestion, String preferredProvider, AiAgentContext context) {
        if (!properties.isEnabled()) {
            return null;
        }
        String question = StringUtils.abbreviate(StringUtils.defaultString(rawQuestion).trim(),
                Math.max(100, properties.getMaxInputLength()));
        if (StringUtils.isBlank(question)) {
            return null;
        }
        String content;
        try {
            content = aiAgentLlmClient.chat(systemPrompt(), question, preferredProvider);
        } catch (RuntimeException e) {
            log.warn("AI agent LLM parse failed, falling back to deterministic rules: question={}, error={}",
                    StringUtils.abbreviate(question, 120), e.toString());
            log.debug("AI agent LLM parse failure detail", e);
            return null;
        }
        if (StringUtils.isBlank(content)) {
            return null;
        }
        ParsedAiAgentQuestion parsed = parseJsonObject(extractJson(content));
        if (parsed == null) {
            return null;
        }
        return parsedQuestionNormalizer.normalize(parsed, rawQuestion, "LLM");
    }

    private ParsedAiAgentQuestion parseJsonObject(String content) {
        try {
            return JSON.parseObject(content, ParsedAiAgentQuestion.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String extractJson(String content) {
        String text = StringUtils.defaultString(content).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String cleanText(String value) {
        String result = StringUtils.defaultString(value)
                .replace("<", "")
                .replace(">", "")
                .replace("`", "")
                .replace("“", "")
                .replace("”", "")
                .trim();
        return StringUtils.isBlank(result) ? null : StringUtils.abbreviate(result, 128);
    }

    private String systemPrompt() {
        String intents = intentValidator.allowedIntents().stream().sorted().collect(Collectors.joining("\n- "));
        return """
                你是 CRM 智能体的问题解析器。
                你只负责把用户问题解析成 JSON，不负责回答业务问题。
                不要编造客户、合同、订单、销售、金额、状态。
                优先从允许的 intent 中选择。
                如果问题可以通过数据库字段查询回答，优先选择 CRM_DATABASE_QUERY，并输出 queryPlan。
                不要输出 SQL，不要把 SQL 放入 candidateSql。
                queryPlan 只能使用白名单实体、字段、operator 和 queryType。
                如果不能确定 intent 或 queryPlan，返回 intent=null。
                如果缺少关键参数，设置 needClarification=true。
                只输出 JSON，不输出 Markdown，不输出解释。
                品种是外部合同表的一个字段，不要理解为订单中的字段
                客户来源分为公司客户和展会客户，不要把公司客户和展会客户混淆，问的是展会客户就返回展会客户，问的是公司客户就返回公司客户
                
                允许的 intent:
                - %s
                
                主要 intent 含义和示例：
                - SPECIALIST_CUSTOMER_LIST：查询某个销售/负责人/联系专员名下客户。只在问题明确是“某销售/负责人/联系专员负责或名下客户”时使用。例：“小郑有哪些客户？”、“小郑名下客户有哪些？”
                - CUSTOMER_CONTRACT_STATUS_LIST：查询某个客户的合同/订单。例：“DAISY签订的合同有哪些？”、“DAISY签过哪些订单？”
                - CUSTOMER_NEW_ORDER_CHECK：查询某个客户的新订单。例：“DAISY这个月有没有新订单？”
                - SALES_CUSTOMER_NEW_ORDER_CHECK：查询某个销售负责客户的新订单。例：“小郑负责的客户这个月有没有新订单？”
                - SPECIALIST_COMMUNICATION_SUMMARY：查询某个销售与客户的沟通统计。例：“小郑这个月和客户沟通的情况怎么样？”
                - LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER：查询沟通少但订单金额高的客户。例：“沟通少但是订单金额高的客户有哪些？”
                - CUSTOMER_ATTENTION_SIGNAL_LIST：查询需要关注的风险客户。例：“哪些客户有新订单但没有跟进记录？”、“有订单但最近没有沟通的客户有哪些？”
                - CUSTOMER_NAME_SEARCH：按客户名称关键词搜索。例：“帮我列一下和印尼有关的客户”
                - VISIBLE_CUSTOMER_LIST：查询当前可见客户列表。例：“我名下有哪些客户？”
                - CRM_DATABASE_QUERY：通用数据库查询。例：“客户来源是展会客户的客户有哪些？”、“名字中带有印尼的客户有哪些？”、“赵芳有哪些客户她没有跟进？”、“每个销售名下客户数量是多少？”、“MLS_242241 这个订单是什么状态？”
                
                参数抽取规则：
                - 用户明确问“订单、订单号、加工单号、加工商、跟单员、联系专员、订单状态、颜色、色号、成分、原料名称、原料类型、加工工艺、下单时间、数量、单价、金额、币种”时，优先使用 CRM_DATABASE_QUERY，entity=sales_order。
                - 普通订单问题不要默认使用 contract_info；contract_info 只用于用户明确说“外部订单/外部合同/contract_info”时。
                - “小郑有哪些客户？”中，小郑是 specialistName，不是 customerName。
                - “名字中带有印尼的客户有哪些？”、“客户名称包含印尼的客户有哪些？”不是查询销售名下客户，必须使用 CRM_DATABASE_QUERY，不要把“名字中带有印尼”提取成 specialistName。
                - “DAISY签订的合同有哪些？”中，DAISY 是 customerName，不要提取成 DAISY签订。
                - “新订单但没有跟进记录”优先选择 CUSTOMER_ATTENTION_SIGNAL_LIST，不要追问时间范围；默认时间范围由后端处理。
                - “沟通少但是订单金额高”优先选择 LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER，不要提取 customerName。
                - 如果用户问的是所有可见客户的聚合列表，不要要求补充某个客户名。
                - “客户来源是展会客户的客户有哪些？”使用 CRM_DATABASE_QUERY，entity=customer，queryType=LIST，filter field=customer_source/operator=eq/value=展会客户。
                - “名字中带有印尼的客户有哪些？”使用 CRM_DATABASE_QUERY，entity=customer，queryType=LIST，filter field=name_keyword/operator=like/value=印尼。
                - “客户名称包含 ABC 的客户有哪些？”使用 CRM_DATABASE_QUERY，entity=customer，queryType=LIST，filter field=name_keyword/operator=like/value=ABC。
                - “印度的客户订的订单原料都是什么？”、“印度地区的客户订的订单原料有哪些？”中，印度表示客户地区/国家，不是客户名称；使用 CRM_DATABASE_QUERY，entity=sales_order，queryType=LIST，filter field=customer_region/operator=like/value=印度，再加 filter field=material_name/operator=not_null，selectFields=customer_name,customer_region,owner_name,material_name,material_type,composition,status,order_no。
                - “2026年没有签订过订单的客户有哪些？”是负向排除问题，不能返回 2026 年订单列表；如 queryPlan 无法表达 NOT EXISTS，可仍返回 CRM_DATABASE_QUERY/sales_order 并由后端专用兜底处理，不要编造答案。
                - “哪些客户2025年签订的有合同，2026年没有签订合同？”需要按客户列表明细回答，后端会同时对比 CRM订单、CRM合同和外部合同/订单数据源。
                - “赵芳有哪些客户她没有跟进？”使用 CRM_DATABASE_QUERY，entity=customer，queryType=LIST，filter field=owner_name/operator=like/value=赵芳，再加 filter field=follow_time/operator=is_null。
                - “某销售没有跟进的客户有哪些？”不要只返回该销售客户列表，必须把“没有跟进”作为数据库过滤条件。
                - “每个销售名下客户数量是多少？”使用 CRM_DATABASE_QUERY，entity=customer，queryType=AGGREGATE，groupBy=owner_name，metric=count(id)。
                - “黄雪梅负责的订单有哪些？”使用 CRM_DATABASE_QUERY，entity=sales_order，queryType=LIST，filter field=owner_name/operator=like/value=黄雪梅，selectFields=order_no,customer_name,contract_name,owner_name,status,material_name,composition,order_time,amount。
                - “黄雪梅负责的订单原料都有哪些？”使用 CRM_DATABASE_QUERY，entity=sales_order，queryType=LIST，filter field=owner_name/operator=like/value=黄雪梅，selectFields=order_no,customer_name,owner_name,material_name,material_type,composition,status。
                - “MLS_242241 这个订单是什么状态？”使用 CRM_DATABASE_QUERY，entity=sales_order，queryType=LIST，filter field=order_no/operator=like/value=MLS_242241，selectFields=order_no,customer_name,owner_name,status,material_name,composition,order_time。
                - 相对时间范围（本月、近30天、本季度等）在 queryPlan filter 中使用 operator=between，value=CURRENT_TIME_WINDOW。
                
                %s
                
                JSON 字段:
                intent, customerName, specialistName, keyword, orderNo, productName, timeRange,
                activeOnly, sqlRequired, candidateSql, confidence, needClarification, clarificationQuestion, queryPlan
                
                queryPlan JSON 字段:
                intent, queryType, entity, selectFields, filters, metrics, groupBy, orderBy, limit, needClarification, clarificationQuestion
                """.formatted(intents, schemaService.schemaPrompt());
    }
}
