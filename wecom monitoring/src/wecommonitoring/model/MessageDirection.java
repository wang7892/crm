package wecommonitoring.model;

/**
 * 消息方向：联系专员 → 客户，或客户 → 联系专员。
 */
public final class MessageDirection {
    public static final String OUTBOUND = "OUTBOUND";
    public static final String INBOUND = "INBOUND";

    private MessageDirection() {
    }

    public static boolean isInbound(String direction) {
        return INBOUND.equalsIgnoreCase(direction);
    }

    public static boolean isOutbound(String direction) {
        return direction == null || direction.isBlank() || OUTBOUND.equalsIgnoreCase(direction);
    }
}
