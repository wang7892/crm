package wecommonitoring.client;

import wecommonitoring.model.MessageDirection;
import wecommonitoring.model.WeComMediaItem;
import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.util.SimpleJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WeComMessageNormalizer {
    private static final int CONTENT_PREVIEW_MAX = 600;
    private static final Set<String> KNOWN_TYPES = Set.of(
            "text", "image", "revoke", "agree", "disagree", "voice", "video", "card",
            "location", "emotion", "file", "link", "weapp", "chatrecord", "todo",
            "vote", "collect", "redpacket", "external_redpacket", "meeting", "docmsg",
            "markdown", "news", "calendar", "mixed", "meeting_voice_call",
            "voip_doc_share", "voiptext", "qydiskfile", "qy_disk_file", "sphfeed",
            "solitaire", "note", "switch", "meeting_control"
    );

    private final Set<String> monitoredUserids;

    WeComMessageNormalizer(Set<String> monitoredUserids) {
        this.monitoredUserids = monitoredUserids == null ? Set.of() : Set.copyOf(monitoredUserids);
    }

    Optional<WeComNormalizedMessage> normalize(String decryptedPayload, Map<String, Object> envelope) {
        if (decryptedPayload == null || decryptedPayload.isBlank()) {
            return Optional.empty();
        }
        Object parsed = SimpleJson.parse(decryptedPayload);
        Map<String, Object> msg = SimpleJson.asObject(parsed);
        if (msg.isEmpty()) {
            return Optional.empty();
        }

        String from = trim(SimpleJson.getString(msg, "from"));
        List<String> toList = toStringList(SimpleJson.getArray(msg, "tolist"));
        String msgType = normalizeType(defaultString(firstNonBlank(SimpleJson.getString(msg, "msgtype"),
                SimpleJson.getString(msg, "msg_type"), SimpleJson.getString(msg, "action")), "unknown"));
        String roomid = trim(firstNonBlank(SimpleJson.getString(msg, "roomid"), SimpleJson.getString(msg, "room_id")));
        boolean room = notBlank(roomid);
        String chatType = room ? "room" : "single";
        String msgId = firstNonBlank(SimpleJson.getString(msg, "msgid"), SimpleJson.getString(msg, "msg_id"),
                SimpleJson.getString(envelope, "msgid"), seqMessageId(envelope));
        long sendTime = normalizeMillis(firstLong(SimpleJson.asLong(msg.get("msgtime")),
                SimpleJson.asLong(msg.get("send_time")), SimpleJson.asLong(envelope.get("msgtime")),
                System.currentTimeMillis()));

        String explicitExternal = trim(firstNonBlank(
                SimpleJson.getString(msg, "external_userid"),
                SimpleJson.getString(msg, "matched_external_userid"),
                SimpleJson.getString(msg, "sender_external_userid")));

        boolean outbound = notBlank(from) && monitoredUserids.contains(from);
        String direction = outbound ? MessageDirection.OUTBOUND : MessageDirection.INBOUND;
        String senderUserid = outbound ? from : null;
        String senderExternalUserid = null;
        String peerUserid = null;
        String externalUserid = null;
        String matchedExternalUserid = null;

        if (outbound) {
            if (room) {
                externalUserid = firstNonBlank(explicitExternal, uniqueExternalCandidate(from, toList, true));
                matchedExternalUserid = externalUserid;
            } else {
                externalUserid = firstNonBlank(explicitExternal, firstExternalOrNotMonitored(toList));
                matchedExternalUserid = externalUserid;
            }
        } else {
            if (room) {
                peerUserid = uniqueMonitoredParticipant(from, toList);
                if (notBlank(explicitExternal)) {
                    senderExternalUserid = explicitExternal;
                    externalUserid = explicitExternal;
                    matchedExternalUserid = explicitExternal;
                } else if (isLikelyExternalUserid(from)) {
                    senderExternalUserid = from;
                    externalUserid = from;
                    matchedExternalUserid = from;
                }
            } else {
                senderExternalUserid = firstNonBlank(explicitExternal, from);
                externalUserid = senderExternalUserid;
                matchedExternalUserid = senderExternalUserid;
                peerUserid = firstMonitored(toList);
            }
        }

        String snapshotJson = buildRoomSnapshotJson(roomid, from, toList);
        List<WeComMediaItem> mediaItems = buildMediaItems(msgType, msg);
        String contentText = buildContentText(msgType, msg, mediaItems);
        String extraJson = buildExtraJson(envelope, msg, snapshotJson);

        return Optional.of(new WeComNormalizedMessage(
                defaultString(msgId, "wecom-" + sendTime + "-" + Math.abs(decryptedPayload.hashCode())),
                direction,
                senderUserid,
                senderExternalUserid,
                peerUserid,
                chatType,
                externalUserid,
                roomid,
                snapshotJson,
                msgType,
                contentText,
                sendTime,
                matchedExternalUserid,
                extraJson,
                mediaItems
        ));
    }

    private String buildContentText(String msgType, Map<String, Object> msg, List<WeComMediaItem> mediaItems) {
        String type = normalizeType(defaultString(msgType, "unknown"));
        Map<String, Object> body = messageBody(msg, type);
        String media = mediaSummary(mediaItems);
        String content = switch (type) {
            case "text" -> defaultString(SimpleJson.getString(body, "content"), "");
            case "image" -> "[image]" + media;
            case "voice" -> "[voice]" + durationSuffix(mediaItems) + media;
            case "video" -> "[video]" + durationSuffix(mediaItems) + fieldSummary(body,
                    "filename", "file_name", "video_play_length") + media;
            case "file" -> "[file]" + fieldSummary(body, "filename", "file_name", "filesize") + media;
            case "emotion" -> "[emotion]" + fieldSummary(body, "type", "width", "height", "imagesize") + media;
            case "revoke" -> "[revoke]" + fieldSummary(body, "pre_msgid", "pre_msg_id", "msgid");
            case "agree" -> "[agree chat archive]" + fieldSummary(body, "userid", "externalopenid", "agree_time");
            case "disagree" -> "[disagree chat archive]" + fieldSummary(body, "userid", "externalopenid", "disagree_time");
            case "card" -> "[card]" + fieldSummary(body, "corpname", "userid", "external_userid");
            case "location" -> "[location]" + fieldSummary(body, "title", "address", "latitude", "longitude", "zoom");
            case "link" -> "[link]" + fieldSummary(body, "title", "description", "link_url", "url");
            case "weapp" -> "[weapp]" + fieldSummary(body, "title", "username", "displayname", "description");
            case "chatrecord" -> "[chatrecord]" + fieldSummary(body, "title", "item", "recorditem") + media;
            case "mixed" -> "[mixed]" + fieldSummary(body, "item", "msgtype") + media;
            case "todo" -> "[todo]" + fieldSummary(body, "title", "content");
            case "vote" -> "[vote]" + fieldSummary(body, "votetitle", "title", "voteitem", "item");
            case "collect" -> "[collect]" + fieldSummary(body, "room_name", "creator", "create_time", "title");
            case "redpacket", "external_redpacket" -> "[redpacket]" + fieldSummary(body, "type", "wish", "totalcnt", "totalamount");
            case "meeting" -> "[meeting]" + fieldSummary(body, "topic", "title", "starttime", "endtime", "address");
            case "docmsg" -> "[doc]" + fieldSummary(body, "title", "link_url", "doc_creator");
            case "markdown" -> "[markdown]" + fieldSummary(body, "content");
            case "news" -> "[news]" + fieldSummary(body, "title", "description", "url", "picurl");
            case "calendar" -> "[calendar]" + fieldSummary(body, "title", "creatorname", "starttime", "endtime", "place");
            case "meeting_voice_call" -> "[meeting_voice_call]" + fieldSummary(body, "voiceid", "meetingid", "duration") + media;
            case "voip_doc_share" -> "[voip_doc_share]" + fieldSummary(body, "filename", "file_name", "md5sum") + media;
            case "voiptext" -> "[voiptext]" + fieldSummary(body,
                    "content", "invitetype", "invite_type", "inviteType",
                    "callduration", "call_duration", "callDuration", "duration");
            case "qydiskfile", "qy_disk_file" -> "[qydiskfile]" + fieldSummary(body, "filename", "file_name", "fileext", "filesize") + media;
            case "sphfeed" -> "[sphfeed]" + fieldSummary(body, "feed_type", "sph_name", "feed_desc", "feed_url");
            case "solitaire" -> "[solitaire]" + fieldSummary(body, "theme", "title", "content");
            case "note" -> "[note]" + fieldSummary(body, "title", "content");
            case "switch" -> "[switch corporate log]";
            case "meeting_control" -> "[meeting_control]" + fieldSummary(body, "meetingid", "control_type");
            default -> "[" + type + "]" + genericSummary(body) + media;
        };
        return limitPreview(content);
    }

    private List<WeComMediaItem> buildMediaItems(String msgType, Map<String, Object> msg) {
        List<WeComMediaItem> mediaItems = new ArrayList<>();
        collectMediaItems(normalizeType(msgType), msg, mediaItems);
        return mediaItems;
    }

    private void collectMediaItems(String fallbackType, Object node, List<WeComMediaItem> out) {
        if (node == null) {
            return;
        }
        if (node instanceof String s) {
            Object parsed = parseEmbeddedJson(s);
            if (parsed != null) {
                collectMediaItems(fallbackType, parsed, out);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectMediaItems(fallbackType, item, out);
            }
            return;
        }
        Map<String, Object> map = SimpleJson.asObject(node);
        if (map.isEmpty()) {
            return;
        }

        String localType = resolveMediaType(fallbackType, map);
        String sdkFileId = trim(firstNonBlank(
                SimpleJson.getString(map, "sdkfileid"),
                SimpleJson.getString(map, "sdk_file_id"),
                SimpleJson.getString(map, "fileid"),
                SimpleJson.getString(map, "file_id")));
        if (sdkFileId != null) {
            out.add(mediaItem(out.size(), localType, map, sdkFileId));
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String childType = fallbackTypeForKey(entry.getKey(), localType);
            collectMediaItems(childType, entry.getValue(), out);
        }
    }

    private String resolveMediaType(String fallbackType, Map<String, Object> map) {
        String normalizedFallback = normalizeType(fallbackType);
        String explicitType = trim(firstNonBlank(
                SimpleJson.getString(map, "msgtype"),
                SimpleJson.getString(map, "msg_type")));
        if (explicitType != null) {
            return normalizeType(explicitType);
        }
        String genericType = trim(SimpleJson.getString(map, "type"));
        if (genericType != null && !genericType.matches("\\d+")) {
            return normalizeType(genericType);
        }
        return defaultString(normalizedFallback, "file");
    }

    private WeComMediaItem mediaItem(int index, String mediaType, Map<String, Object> body, String sdkFileId) {
        String fileName = trim(firstNonBlank(
                SimpleJson.getString(body, "filename"),
                SimpleJson.getString(body, "file_name"),
                SimpleJson.getString(body, "title")));
        Long sizeBytes = firstLong(
                SimpleJson.asLong(body.get("filesize")),
                SimpleJson.asLong(body.get("file_size")),
                SimpleJson.asLong(body.get("voice_size")),
                SimpleJson.asLong(body.get("image_size")),
                SimpleJson.asLong(body.get("imagesize")),
                SimpleJson.asLong(body.get("video_size")),
                SimpleJson.asLong(body.get("size")));
        Integer durationMs = normalizeDurationMillis(firstLong(
                SimpleJson.asLong(body.get("play_length")),
                SimpleJson.asLong(body.get("duration")),
                SimpleJson.asLong(body.get("duration_ms")),
                SimpleJson.asLong(body.get("voice_play_length")),
                SimpleJson.asLong(body.get("video_play_length")),
                null));
        String md5OrSha = trim(firstNonBlank(
                SimpleJson.getString(body, "sha256"),
                SimpleJson.getString(body, "sha256_hex"),
                SimpleJson.getString(body, "md5sum"),
                SimpleJson.getString(body, "md5")));
        String type = normalizeType(defaultString(mediaType, "file"));
        String mimeType = guessMimeType(type, fileName, SimpleJson.getString(body, "fileext"));
        return new WeComMediaItem(index, type, sdkFileId, fileName, mimeType, sizeBytes,
                durationMs, md5OrSha, SimpleJson.toJson(body));
    }

    private Map<String, Object> messageBody(Map<String, Object> msg, String type) {
        String normalized = normalizeType(type);
        Map<String, Object> body = mapBody(msg.get(normalized));
        if (!body.isEmpty()) {
            return body;
        }
        String[] aliases = switch (normalized) {
            case "docmsg" -> new String[]{"doc"};
            case "markdown", "news" -> new String[]{"info"};
            case "voiptext" -> new String[]{"info", "voip_text"};
            case "external_redpacket" -> new String[]{"redpacket"};
            case "qy_disk_file" -> new String[]{"qydiskfile"};
            default -> new String[0];
        };
        for (String alias : aliases) {
            body = mapBody(msg.get(alias));
            if (!body.isEmpty()) {
                return body;
            }
        }
        return msg;
    }

    private Map<String, Object> mapBody(Object value) {
        Map<String, Object> body = SimpleJson.asObject(value);
        if (!body.isEmpty()) {
            return body;
        }
        String text = trim(SimpleJson.asString(value));
        if (text != null) {
            Object parsed = parseEmbeddedJson(text);
            body = SimpleJson.asObject(parsed);
            if (!body.isEmpty()) {
                return body;
            }
        }
        return Map.of();
    }

    private String fieldSummary(Map<String, Object> map, String... fields) {
        List<String> parts = new ArrayList<>();
        for (String field : fields) {
            Object value = map.get(field);
            String summary = valueSummary(value);
            if (summary != null) {
                parts.add(field + "=" + summary);
            }
        }
        return parts.isEmpty() ? "" : " " + String.join(", ", parts);
    }

    private String genericSummary(Map<String, Object> map) {
        String summary = fieldSummary(map,
                "title", "content", "description", "text", "name", "filename", "file_name",
                "url", "link_url", "topic", "address", "theme");
        if (!summary.isBlank()) {
            return summary;
        }
        return "";
    }

    private String valueSummary(Object value) {
        if (value == null) {
            return null;
        }
        String direct = trim(SimpleJson.asString(value));
        if (direct != null) {
            return preview(direct);
        }
        List<Object> array = SimpleJson.asArray(value);
        if (!array.isEmpty()) {
            return array.size() + " item(s)";
        }
        Map<String, Object> map = SimpleJson.asObject(value);
        if (!map.isEmpty()) {
            String nested = genericSummary(map).trim();
            return nested.isBlank() ? map.size() + " field(s)" : nested;
        }
        return preview(String.valueOf(value));
    }

    private String mediaSummary(List<WeComMediaItem> mediaItems) {
        if (mediaItems == null || mediaItems.isEmpty()) {
            return "";
        }
        WeComMediaItem first = mediaItems.get(0);
        List<String> parts = new ArrayList<>();
        parts.add("media=" + mediaItems.size());
        if (first.getFileName() != null) {
            parts.add("file=" + preview(first.getFileName()));
        }
        if (first.getSizeBytes() != null) {
            parts.add("size=" + first.getSizeBytes());
        }
        return " " + String.join(", ", parts);
    }

    private String durationSuffix(List<WeComMediaItem> mediaItems) {
        if (mediaItems == null || mediaItems.isEmpty() || mediaItems.get(0).getDurationMs() == null) {
            return "";
        }
        return " duration_ms=" + mediaItems.get(0).getDurationMs();
    }

    private String fallbackTypeForKey(String key, String fallbackType) {
        String normalizedKey = normalizeType(key);
        if (KNOWN_TYPES.contains(normalizedKey) || Set.of("doc", "info", "redpacket").contains(normalizedKey)) {
            return normalizedKey;
        }
        return normalizeType(fallbackType);
    }

    private String normalizeType(String rawType) {
        String value = trim(rawType);
        if (value == null) {
            return "unknown";
        }
        String lower = value.toLowerCase().replace('-', '_');
        if (lower.startsWith("chatrecord")) {
            lower = lower.substring("chatrecord".length());
        }
        return switch (lower) {
            case "image", "pic", "picture" -> "image";
            case "voice", "audio" -> "voice";
            case "video" -> "video";
            case "file", "qydiskfile", "qy_disk_file" -> lower;
            case "emotion", "emoji" -> "emotion";
            case "doc", "docmsg", "online_doc" -> "docmsg";
            case "externalredpacket" -> "external_redpacket";
            case "meetingvoicecall" -> "meeting_voice_call";
            case "voipdocshare" -> "voip_doc_share";
            case "meetingcontrol" -> "meeting_control";
            default -> lower;
        };
    }

    private Object parseEmbeddedJson(String value) {
        String trimmed = trim(value);
        if (trimmed == null || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return SimpleJson.parse(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildRoomSnapshotJson(String roomid, String from, List<String> toList) {
        if (!notBlank(roomid)) {
            return null;
        }
        LinkedHashSet<String> participants = new LinkedHashSet<>();
        if (notBlank(from)) {
            participants.add(from);
        }
        participants.addAll(toList);
        List<String> monitored = participants.stream().filter(monitoredUserids::contains).toList();
        List<String> external = participants.stream()
                .filter(v -> !monitoredUserids.contains(v))
                .filter(this::isLikelyExternalUserid)
                .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("roomid", roomid);
        snapshot.put("from", from);
        snapshot.put("tolist", toList);
        snapshot.put("participants", new ArrayList<>(participants));
        snapshot.put("monitored_userids", monitored);
        snapshot.put("external_userids", external);
        return SimpleJson.toJson(snapshot);
    }

    private String buildExtraJson(Map<String, Object> envelope, Map<String, Object> msg, String roomSnapshotJson) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", "wecom-chatdata");
        extra.put("seq", envelope == null ? null : envelope.get("seq"));
        extra.put("publickey_ver", envelope == null ? null : envelope.get("publickey_ver"));
        if (roomSnapshotJson != null && !roomSnapshotJson.isBlank()) {
            try {
                extra.put("room_snapshot", SimpleJson.parse(roomSnapshotJson));
            } catch (Exception ex) {
                extra.put("room_snapshot", roomSnapshotJson);
            }
        }
        extra.put("payload", msg);
        return SimpleJson.toJson(extra);
    }

    private String uniqueExternalCandidate(String from, List<String> toList, boolean requireLikelyExternal) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String item : toList) {
            if (!notBlank(item) || item.equals(from) || monitoredUserids.contains(item)) {
                continue;
            }
            if (requireLikelyExternal && !isLikelyExternalUserid(item)) {
                continue;
            }
            candidates.add(item);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private String uniqueMonitoredParticipant(String from, List<String> toList) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (monitoredUserids.contains(from)) {
            candidates.add(from);
        }
        for (String item : toList) {
            if (monitoredUserids.contains(item)) {
                candidates.add(item);
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private String firstMonitored(List<String> toList) {
        for (String item : toList) {
            if (monitoredUserids.contains(item)) {
                return item;
            }
        }
        return null;
    }

    private String firstExternalOrNotMonitored(List<String> toList) {
        for (String item : toList) {
            if (isLikelyExternalUserid(item)) {
                return item;
            }
        }
        for (String item : toList) {
            if (notBlank(item) && !monitoredUserids.contains(item)) {
                return item;
            }
        }
        return null;
    }

    private boolean isLikelyExternalUserid(String userid) {
        String value = trim(userid);
        return value != null && (value.startsWith("wm") || value.startsWith("wo") || value.startsWith("external_"));
    }

    private List<String> toStringList(List<Object> raw) {
        List<String> list = new ArrayList<>();
        for (Object item : raw) {
            String s = trim(SimpleJson.asString(item));
            if (s != null) {
                list.add(s);
            }
        }
        return list;
    }

    private Integer normalizeDurationMillis(Long raw) {
        if (raw == null || raw <= 0) {
            return null;
        }
        if (raw < 10000) {
            return Math.toIntExact(raw * 1000);
        }
        return Math.toIntExact(Math.min(raw, Integer.MAX_VALUE));
    }

    private long normalizeMillis(Long raw) {
        if (raw == null || raw <= 0) {
            return System.currentTimeMillis();
        }
        return raw < 10_000_000_000L ? raw * 1000 : raw;
    }

    @SafeVarargs
    private final <T> T firstLong(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String t = trim(value);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private String seqMessageId(Map<String, Object> envelope) {
        Long seq = envelope == null ? null : SimpleJson.asLong(envelope.get("seq"));
        return seq == null ? null : "seq-" + seq;
    }

    private String defaultString(String value, String defaultValue) {
        return notBlank(value) ? value : defaultValue;
    }

    private String preview(String value) {
        String compact = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "...";
    }

    private String limitPreview(String value) {
        String compact = value == null ? "" : value.replace('\r', ' ').trim();
        return compact.length() <= CONTENT_PREVIEW_MAX ? compact : compact.substring(0, CONTENT_PREVIEW_MAX) + "...";
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String guessMimeType(String type, String fileName, String fileExt) {
        String ext = firstNonBlank(fileExt, extensionOf(fileName));
        if (ext == null) {
            return switch (defaultString(type, "").toLowerCase()) {
                case "image" -> "image/*";
                case "voice", "meeting_voice_call" -> "audio/*";
                case "video" -> "video/*";
                default -> null;
            };
        }
        String e = ext.toLowerCase().replaceFirst("^\\.", "");
        return switch (e) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "amr" -> "audio/amr";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "zip" -> "application/zip";
            default -> null;
        };
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length() ? fileName.substring(dot + 1) : null;
    }
}
