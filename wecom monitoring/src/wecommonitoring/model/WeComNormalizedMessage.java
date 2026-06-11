package wecommonitoring.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则引擎输出：一条与「联系专员 / 客户」相关的归一化消息（含方向）。
 */
public class WeComNormalizedMessage {
    private final String wecomMsgId;
    private final String messageDirection;
    /** 发送方为企业成员时：userid；客户发送时为空 */
    private final String senderUserid;
    /** 发送方为外部联系人时：external_userid；专员发送时为空 */
    private final String senderExternalUserid;
    /** 单聊中另一方企业成员 userid：客户来信时表示接收方专员（应在监测名单中） */
    private final String peerUserid;
    private final String chatType;
    /** 单聊场景下客户 external_userid（会话中的客户侧标识） */
    private final String externalUserid;
    private final String roomid;
    private final String roomExternalSnapshotJson;
    private final String msgType;
    private final String contentText;
    private final long sendTimeMillis;
    private final String matchedExternalUserid;
    private final String extraJson;
    private final List<WeComMediaItem> mediaItems;

    public WeComNormalizedMessage(String wecomMsgId, String messageDirection,
                                  String senderUserid, String senderExternalUserid, String peerUserid,
                                  String chatType, String externalUserid, String roomid, String roomExternalSnapshotJson,
                                  String msgType, String contentText, long sendTimeMillis,
                                  String matchedExternalUserid, String extraJson) {
        this(wecomMsgId, messageDirection, senderUserid, senderExternalUserid, peerUserid, chatType,
                externalUserid, roomid, roomExternalSnapshotJson, msgType, contentText, sendTimeMillis,
                matchedExternalUserid, extraJson, List.of());
    }

    public WeComNormalizedMessage(String wecomMsgId, String messageDirection,
                                  String senderUserid, String senderExternalUserid, String peerUserid,
                                  String chatType, String externalUserid, String roomid, String roomExternalSnapshotJson,
                                  String msgType, String contentText, long sendTimeMillis,
                                  String matchedExternalUserid, String extraJson, List<WeComMediaItem> mediaItems) {
        this.wecomMsgId = wecomMsgId;
        this.messageDirection = messageDirection == null || messageDirection.isBlank()
                ? MessageDirection.OUTBOUND
                : messageDirection.trim().toUpperCase();
        this.senderUserid = blankToNull(senderUserid);
        this.senderExternalUserid = blankToNull(senderExternalUserid);
        this.peerUserid = blankToNull(peerUserid);
        this.chatType = chatType;
        this.externalUserid = externalUserid;
        this.roomid = roomid;
        this.roomExternalSnapshotJson = roomExternalSnapshotJson;
        this.msgType = msgType;
        this.contentText = contentText;
        this.sendTimeMillis = sendTimeMillis;
        this.matchedExternalUserid = matchedExternalUserid;
        this.extraJson = extraJson;
        this.mediaItems = mediaItems == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(mediaItems));
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    public String getWecomMsgId() {
        return wecomMsgId;
    }

    public String getMessageDirection() {
        return messageDirection;
    }

    public String getSenderUserid() {
        return senderUserid;
    }

    public String getSenderExternalUserid() {
        return senderExternalUserid;
    }

    public String getPeerUserid() {
        return peerUserid;
    }

    public String getChatType() {
        return chatType;
    }

    public String getExternalUserid() {
        return externalUserid;
    }

    public String getRoomid() {
        return roomid;
    }

    public String getRoomExternalSnapshotJson() {
        return roomExternalSnapshotJson;
    }

    public String getMsgType() {
        return msgType;
    }

    public String getContentText() {
        return contentText;
    }

    public long getSendTimeMillis() {
        return sendTimeMillis;
    }

    public String getMatchedExternalUserid() {
        return matchedExternalUserid;
    }

    public String getExtraJson() {
        return extraJson;
    }

    public List<WeComMediaItem> getMediaItems() {
        return mediaItems;
    }
}
