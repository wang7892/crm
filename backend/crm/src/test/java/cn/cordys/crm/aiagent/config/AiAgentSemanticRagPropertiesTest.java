package cn.cordys.crm.aiagent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentSemanticRagPropertiesTest {

    @Test
    void shouldEnableSemanticRagWithoutShadowModeByDefault() {
        AiAgentSemanticRagProperties properties = new AiAgentSemanticRagProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isShadowEnabled()).isFalse();
    }
}
