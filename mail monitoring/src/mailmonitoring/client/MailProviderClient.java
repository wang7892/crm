package mailmonitoring.client;

import mailmonitoring.model.MailBatchResult;

public interface MailProviderClient {
    MailBatchResult fetchNewEvents(String mailbox, String cursor);
}
