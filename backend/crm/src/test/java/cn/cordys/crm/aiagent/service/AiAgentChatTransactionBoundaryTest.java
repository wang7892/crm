package cn.cordys.crm.aiagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentChatTransactionBoundaryTest {

    @Test
    void chatOrchestrationDoesNotHoldDatabaseTransactionDuringModelCalls() throws Exception {
        Transactional chatTransaction = AiAgentChatService.class.getAnnotation(Transactional.class);

        assertThat(chatTransaction).isNotNull();
        assertThat(chatTransaction.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(transactionOf("saveMessage").propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactionOf("saveToolLogs").propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactionOf("touch").propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private Transactional transactionOf(String methodName) throws NoSuchMethodException {
        Method method = switch (methodName) {
            case "saveMessage" -> AiAgentAuditService.class.getMethod(
                    methodName, String.class, String.class, String.class, String.class, Object.class, String.class);
            case "saveToolLogs" -> AiAgentAuditService.class.getMethod(
                    methodName, String.class, java.util.List.class, String.class);
            case "touch" -> AiAgentAuditService.class.getMethod(methodName, String.class, String.class);
            default -> throw new IllegalArgumentException(methodName);
        };
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).as(methodName + " transaction annotation").isNotNull();
        return annotation;
    }
}
