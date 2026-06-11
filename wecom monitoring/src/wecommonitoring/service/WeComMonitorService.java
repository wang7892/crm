package wecommonitoring.service;

import wecommonitoring.client.ArchivePullClient;
import wecommonitoring.model.ArchivePullBatch;
import wecommonitoring.model.MessageDirection;
import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.repository.CheckpointRepository;
import wecommonitoring.repository.CrmIngestionRepository;
import wecommonitoring.repository.WeComMessageEventRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class WeComMonitorService {
    public static final String CHECKPOINT_ARCHIVE_SEQ = "ARCHIVE_SEQ";

    private final String organizationId;
    private final String corpId;
    private final Set<String> monitoredUserids;
    private final ArchivePullClient archivePullClient;
    private final CheckpointRepository checkpointRepository;
    private final WeComMessageEventRepository messageEventRepository;
    private final CrmIngestionRepository crmIngestionRepository;
    private final int seqOverlap;
    private final int maxPagesPerPoll;

    public WeComMonitorService(String organizationId, String corpId, List<String> monitoredUserids,
                               ArchivePullClient archivePullClient,
                               CheckpointRepository checkpointRepository,
                               WeComMessageEventRepository messageEventRepository,
                               CrmIngestionRepository crmIngestionRepository) {
        this(organizationId, corpId, monitoredUserids, archivePullClient, checkpointRepository,
                messageEventRepository, crmIngestionRepository, 0, 1);
    }

    public WeComMonitorService(String organizationId, String corpId, List<String> monitoredUserids,
                               ArchivePullClient archivePullClient,
                               CheckpointRepository checkpointRepository,
                               WeComMessageEventRepository messageEventRepository,
                               CrmIngestionRepository crmIngestionRepository,
                               int seqOverlap,
                               int maxPagesPerPoll) {
        this.organizationId = organizationId;
        this.corpId = corpId;
        this.monitoredUserids = new HashSet<>();
        for (String u : monitoredUserids) {
            if (u != null && !u.isBlank()) {
                this.monitoredUserids.add(u.trim());
            }
        }
        this.archivePullClient = archivePullClient;
        this.checkpointRepository = checkpointRepository;
        this.messageEventRepository = messageEventRepository;
        this.crmIngestionRepository = crmIngestionRepository;
        this.seqOverlap = Math.max(0, seqOverlap);
        this.maxPagesPerPoll = Math.max(1, maxPagesPerPoll);
    }

    public void pollOnce() throws Exception {
        long checkpointSeq = checkpointRepository.getLastSeq(corpId, CHECKPOINT_ARCHIVE_SEQ);
        long pullSeq = Math.max(0L, checkpointSeq - seqOverlap);
        long nextCheckpointSeq = checkpointSeq;
        int page = 0;

        while (page < maxPagesPerPoll) {
            page++;
            ArchivePullBatch batch = archivePullClient.pull(pullSeq);
            for (WeComNormalizedMessage msg : batch.getMessages()) {
                handleMessage(msg);
            }

            long nextSeq = batch.getNextSeq();
            if (nextSeq > nextCheckpointSeq) {
                nextCheckpointSeq = nextSeq;
            }
            System.out.printf("[POLL] page=%d, pull_seq=%d, next_seq=%d, raw_count=%d, normalized_count=%d, decode_failures=%d%n",
                    page, pullSeq, nextSeq, batch.getRawCount(), batch.getMessages().size(), batch.getDecodeFailureCount());

            if (nextSeq <= pullSeq || batch.getRawCount() == 0) {
                break;
            }
            pullSeq = nextSeq;
        }

        if (nextCheckpointSeq > checkpointSeq) {
            checkpointRepository.saveLastSeq(corpId, CHECKPOINT_ARCHIVE_SEQ, nextCheckpointSeq);
        } else {
            checkpointRepository.saveLastSeq(corpId, CHECKPOINT_ARCHIVE_SEQ, checkpointSeq);
        }
    }

    private void handleMessage(WeComNormalizedMessage msg) {
        if (!isExternalScoped(msg)) {
            System.out.printf("[SKIP] not external-scoped chat, msgId=%s, direction=%s%n",
                    msg.getWecomMsgId(), msg.getMessageDirection());
            return;
        }
        if (!isMonitoredParticipant(msg)) {
            System.out.printf("[SKIP] no monitored specialist involved, msgId=%s, direction=%s%n",
                    msg.getWecomMsgId(), msg.getMessageDirection());
            return;
        }
        String eventRowId = messageEventRepository.insertIfAbsent(organizationId, corpId, msg);
        if (eventRowId == null) {
            System.out.printf("[DEDUP] skip duplicated wecom_msg_id=%s%n", msg.getWecomMsgId());
            return;
        }
        Optional<String> crmId = crmIngestionRepository.insertPending(organizationId, corpId, msg);
        if (crmId.isPresent()) {
            messageEventRepository.updateCrmIngestionId(eventRowId, crmId.get());
            System.out.printf("[SUCCESS] wecom_msg_id=%s, direction=%s, monitor_event_id=%s, crm_ingestion_id=%s%n",
                    msg.getWecomMsgId(), msg.getMessageDirection(), eventRowId, crmId.get());
        } else {
            System.out.printf("[SUCCESS] wecom_msg_id=%s, direction=%s, monitor_event_id=%s, crm_ingestion=disabled_or_noop%n",
                    msg.getWecomMsgId(), msg.getMessageDirection(), eventRowId);
        }
    }

    /**
     * OUTBOUND：发送方须为监测名单中的专员；INBOUND：接收方 peer 须为监测名单中的专员（客户发来）。
     * 群聊 INBOUND：快照 JSON 中须出现至少一名监测 userid。
     */
    private boolean isMonitoredParticipant(WeComNormalizedMessage msg) {
        if (MessageDirection.isInbound(msg.getMessageDirection())) {
            if ("single".equalsIgnoreCase(msg.getChatType())) {
                return msg.getPeerUserid() != null && monitoredUserids.contains(msg.getPeerUserid());
            }
            if ("room".equalsIgnoreCase(msg.getChatType())) {
                return roomSnapshotContainsMonitored(msg.getRoomExternalSnapshotJson());
            }
            return false;
        }
        return msg.getSenderUserid() != null && monitoredUserids.contains(msg.getSenderUserid());
    }

    private boolean roomSnapshotContainsMonitored(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return false;
        }
        for (String uid : monitoredUserids) {
            if (snapshot.contains(uid)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExternalScoped(WeComNormalizedMessage msg) {
        if ("single".equalsIgnoreCase(msg.getChatType())) {
            if (MessageDirection.isInbound(msg.getMessageDirection())) {
                return notBlank(msg.getSenderExternalUserid()) && notBlank(msg.getPeerUserid());
            }
            return notBlank(msg.getExternalUserid());
        }
        if ("room".equalsIgnoreCase(msg.getChatType())) {
            return notBlank(msg.getRoomid());
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
