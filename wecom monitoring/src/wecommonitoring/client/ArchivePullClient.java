package wecommonitoring.client;

import wecommonitoring.model.ArchivePullBatch;

/**
 * 从企业微信会话存档侧拉取一批数据并归一化（解密逻辑可在实现内完成）。
 */
public interface ArchivePullClient {
    /**
     * @param lastSeq 上次已成功处理的 seq；首次可为 0
     */
    ArchivePullBatch pull(long lastSeq) throws Exception;
}
