package mailmonitoring.client;

import mailmonitoring.model.MailBatchResult;
import mailmonitoring.model.MailEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MockMailProviderClient implements MailProviderClient {
    private final List<MailEvent> seedEvents = new ArrayList<>();
    private final AtomicInteger pointer = new AtomicInteger(0);

    public MockMailProviderClient(String organizationId, String sourceMailbox, String targetMailbox) {
        seedEvents.add(new MailEvent(
                organizationId,
                sourceMailbox,
                targetMailbox,
                "<msg-001@mail>",
                "thread-001",
                sourceMailbox,
                List.of(targetMailbox),
                List.of(),
                "报价确认",
                "这是第一封测试邮件",
                Instant.now(),
                List.of()
        ));
        seedEvents.add(new MailEvent(
                organizationId,
                sourceMailbox,
                targetMailbox,
                "<msg-001@mail>",
                "thread-001",
                sourceMailbox,
                List.of(targetMailbox),
                List.of(),
                "重复邮件",
                "用于测试幂等防重",
                Instant.now(),
                List.of()
        ));
        seedEvents.add(new MailEvent(
                organizationId,
                sourceMailbox,
                targetMailbox,
                "<msg-002@mail>",
                "thread-002",
                sourceMailbox,
                List.of(targetMailbox, "other@company.com"),
                List.of(),
                "第二封邮件",
                "用于测试正常入库",
                Instant.now(),
                List.of()
        ));
    }

    @Override
    public MailBatchResult fetchNewEvents(String mailbox, String cursor) {
        int current = pointer.get();
        if (current >= seedEvents.size()) {
            return new MailBatchResult(List.of(), String.valueOf(current));
        }
        MailEvent event = seedEvents.get(current);
        pointer.incrementAndGet();
        return new MailBatchResult(List.of(event), String.valueOf(pointer.get()));
    }
}
