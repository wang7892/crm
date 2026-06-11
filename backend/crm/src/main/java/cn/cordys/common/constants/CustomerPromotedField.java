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
public enum CustomerPromotedField {

    WECOM_EXTERNAL_ID("177676244643300000", "customerWecomExternalId", "wecomExternalId"),
    ROOMID("177898123865800000", "customerRoomid", "roomid"),
    EMAIL("177676248585700000", "customerEmail", "email"),
    FULL_NAME("177855464453000000", "customerFullName", "fullName"),
    CREDIT_LIMIT("177855488944900000", "customerCreditLimit", "creditLimit"),
    CUSTOMS_CODE("177855497741600000", "customerCustomsCode", "customsCode"),
    REGION("177855499908200000", "customerRegion", "region"),
    PHONE("177855548250600000", "customerPhone", "phone"),
    ADDRESS("177855517842000000", "customerAddress", "address", "177855551784200000"),
    REMARK("177855575351400000", "customerRemark", "remark"),
    CUSTOMER_AVAILABLE("345735959565836387", "customerAvailable", "customerAvailable", "customerLevel"),
    CUSTOMER_SOURCE("177986537827400000", "customerSource", "customerSource");

    private static final Map<String, CustomerPromotedField> FIELD_ID_CACHE = Arrays.stream(values())
            .flatMap(field -> field.getFieldIds().stream().map(fieldId -> Map.entry(fieldId, field)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (prev, next) -> prev));

    private static final Map<String, CustomerPromotedField> INTERNAL_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CustomerPromotedField::getInternalKey, Function.identity()));

    private static final Map<String, CustomerPromotedField> BUSINESS_KEY_CACHE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CustomerPromotedField::getBusinessKey, Function.identity()));

    private static final Set<String> FIELD_IDS = Set.copyOf(FIELD_ID_CACHE.keySet());

    private final String fieldId;
    private final Set<String> fieldIds;
    private final String internalKey;
    private final String businessKey;

    CustomerPromotedField(String fieldId, String internalKey, String businessKey, String... aliasFieldIds) {
        this.fieldId = fieldId;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(fieldId);
        ids.add(businessKey);
        ids.addAll(Arrays.asList(aliasFieldIds));
        this.fieldIds = Collections.unmodifiableSet(ids);
        this.internalKey = internalKey;
        this.businessKey = businessKey;
    }

    public static CustomerPromotedField ofFieldId(String fieldId) {
        if (fieldId == null) {
            return null;
        }
        return FIELD_ID_CACHE.get(fieldId);
    }

    public static CustomerPromotedField ofInternalKey(String internalKey) {
        if (internalKey == null) {
            return null;
        }
        return INTERNAL_KEY_CACHE.get(internalKey);
    }

    public static CustomerPromotedField ofBusinessKey(String businessKey) {
        if (businessKey == null) {
            return null;
        }
        return BUSINESS_KEY_CACHE.get(businessKey);
    }

    public static CustomerPromotedField of(String fieldId, String internalKey, String businessKey) {
        CustomerPromotedField promotedField = ofFieldId(fieldId);
        if (promotedField != null) {
            return promotedField;
        }
        promotedField = ofInternalKey(internalKey);
        if (promotedField != null) {
            return promotedField;
        }
        return ofBusinessKey(businessKey);
    }

    public boolean matches(String fieldId, String internalKey, String businessKey) {
        return this == of(fieldId, internalKey, businessKey);
    }

    public static boolean isPromotedFieldId(String fieldId) {
        if (fieldId == null) {
            return false;
        }
        return FIELD_ID_CACHE.containsKey(fieldId);
    }

    public static Set<String> fieldIds() {
        return FIELD_IDS;
    }
}
