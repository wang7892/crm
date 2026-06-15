package cn.cordys.common.constants;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
public enum OrderPromotedField {

    PROCESS_ORDER_NO("processOrderNo", "processOrderNo"),
    PROCESSOR("orderProcessor", "processor"),
    MERCHANDISER("orderMerchandiser", "merchandiser"),
    STATUS("orderExternalStatus", "status"),
    COLOR("orderColor", "color"),
    COLOR_CODE("orderColorCode", "colorCode"),
    COMPOSITION("orderComposition", "composition"),
    MATERIAL_NAME("orderMaterialName", "materialName"),
    MATERIAL_TYPE("orderMaterialType", "materialType"),
    PROCESS_TECHNOLOGY("orderProcessTechnology", "processTechnology"),
    ORDER_TIME("orderTime", "orderTime"),
    QUANTITY("orderQuantity", "quantity"),
    UNIT("orderUnit", "unit"),
    UNIT_PRICE("orderUnitPrice", "unitPrice"),
    AMOUNT("orderAmount", "amount"),
    CURRENCY("orderCurrency", "currency");

    private static final Map<String, OrderPromotedField> FIELD_ID_CACHE = Arrays.stream(values())
            .flatMap(field -> field.getFieldIds().stream().map(fieldId -> Map.entry(fieldId, field)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (prev, next) -> prev));

    private static final Map<String, OrderPromotedField> INTERNAL_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(OrderPromotedField::getInternalKey, Function.identity()));

    private static final Map<String, OrderPromotedField> BUSINESS_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(OrderPromotedField::getBusinessKey, Function.identity()));

    private final String internalKey;
    private final String businessKey;
    private final Set<String> fieldIds;

    OrderPromotedField(String internalKey, String businessKey, String... aliasFieldIds) {
        this.internalKey = internalKey;
        this.businessKey = businessKey;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(internalKey);
        ids.add(businessKey);
        ids.addAll(Arrays.asList(aliasFieldIds));
        this.fieldIds = Collections.unmodifiableSet(ids);
    }

    public static OrderPromotedField ofFieldId(String fieldId) {
        if (fieldId == null) {
            return null;
        }
        return FIELD_ID_CACHE.get(fieldId);
    }

    public static OrderPromotedField ofInternalKey(String internalKey) {
        if (internalKey == null) {
            return null;
        }
        return INTERNAL_KEY_CACHE.get(internalKey);
    }

    public static OrderPromotedField ofBusinessKey(String businessKey) {
        if (businessKey == null) {
            return null;
        }
        return BUSINESS_KEY_CACHE.get(businessKey);
    }

    public static OrderPromotedField of(String fieldId, String internalKey, String businessKey) {
        OrderPromotedField promotedField = ofFieldId(fieldId);
        if (promotedField != null) {
            return promotedField;
        }
        promotedField = ofInternalKey(internalKey);
        if (promotedField != null) {
            return promotedField;
        }
        return ofBusinessKey(businessKey);
    }
}
