package cn.cordys.common.constants;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contract fields that are rendered by the form system but persisted on the
 * contract table instead of contract_field.
 */
@Getter
public enum ContractPromotedField {

    ORDER_STATUS("177980251841700000", "orderStatus", "orderStatus", "orderExternalStatus", "contractOrderStatus"),
    CURRENCY("177977881820900000", "orderCurrency", "currency");

    private static final Map<String, ContractPromotedField> FIELD_ID_CACHE = Arrays.stream(values())
            .flatMap(field -> field.getFieldIds().stream().map(fieldId -> Map.entry(fieldId, field)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (prev, next) -> prev));

    private static final Map<String, ContractPromotedField> INTERNAL_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ContractPromotedField::getInternalKey, Function.identity()));

    private static final Map<String, ContractPromotedField> BUSINESS_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ContractPromotedField::getBusinessKey, Function.identity()));

    private final String fieldId;
    private final Set<String> fieldIds;
    private final String internalKey;
    private final String businessKey;

    ContractPromotedField(String fieldId, String internalKey, String businessKey, String... aliasFieldIds) {
        this.fieldId = fieldId;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(fieldId);
        ids.add(internalKey);
        ids.add(businessKey);
        ids.addAll(Arrays.asList(aliasFieldIds));
        this.fieldIds = Collections.unmodifiableSet(ids);
        this.internalKey = internalKey;
        this.businessKey = businessKey;
    }

    public static ContractPromotedField ofFieldId(String fieldId) {
        return fieldId == null ? null : FIELD_ID_CACHE.get(fieldId);
    }

    public static ContractPromotedField ofInternalKey(String internalKey) {
        return internalKey == null ? null : INTERNAL_KEY_CACHE.get(internalKey);
    }

    public static ContractPromotedField ofBusinessKey(String businessKey) {
        return businessKey == null ? null : BUSINESS_KEY_CACHE.get(businessKey);
    }

    public static ContractPromotedField of(String fieldId, String internalKey, String businessKey) {
        ContractPromotedField field = ofFieldId(fieldId);
        if (field != null) {
            return field;
        }
        field = ofInternalKey(internalKey);
        if (field != null) {
            return field;
        }
        return ofBusinessKey(businessKey);
    }
}
