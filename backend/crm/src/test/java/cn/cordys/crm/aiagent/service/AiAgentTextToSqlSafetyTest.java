package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentTextToSqlSafetyTest {
    private AiAgentSqlGuard guard() {
        var properties = new AiAgentSqlProperties();
        properties.setEnabled(true);
        properties.setAllowedTables(List.of("customer"));
        return new AiAgentSqlGuard(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT c.id FROM customer c JOIN secrets s ON c.id=s.id",
            "SELECT id FROM customer WHERE EXISTS (SELECT id FROM secrets)",
            "SELECT (SELECT password FROM secrets) AS value FROM customer",
            "SELECT id FROM customer ORDER BY (SELECT password FROM secrets)",
            "SELECT COUNT(*) FROM customer GROUP BY (SELECT password FROM secrets)",
            "WITH hidden AS (SELECT id FROM secrets) SELECT id FROM hidden",
            "WITH secrets AS (SELECT id FROM secrets) SELECT id FROM customer UNION SELECT id FROM secrets",
            "WITH earlier AS (SELECT id FROM secrets), secrets AS (SELECT id FROM customer) SELECT id FROM earlier",
            "SELECT id FROM customer UNION SELECT id FROM secrets",
            "SELECT id FROM information_schema.tables",
            "SELECT id FROM customer; DELETE FROM customer",
            "DELETE FROM customer",
            "UPDATE customer SET name='x'",
            "SELECT id INTO OUTFILE '/tmp/result' FROM customer",
            "SELECT id FROM customer FOR UPDATE",
            "SELECT id FROM customer LOCK IN SHARE MODE",
            "SELECT SLEEP(10) FROM customer",
            "SELECT COUNT(*) FROM customer HAVING SLEEP(10)=0",
            "SELECT id FROM customer ORDER BY SLEEP(10)",
            "SELECT COUNT(*) FROM customer GROUP BY SLEEP(10)",
            "SELECT SUM(SLEEP(10)) OVER () FROM customer",
            "SELECT SUM(id) OVER (ORDER BY SLEEP(10)) FROM customer",
            "SELECT GROUP_CONCAT(id ORDER BY SLEEP(10)) FROM customer",
            "SELECT GROUP_CONCAT((SELECT password FROM secrets)) FROM customer",
            "SELECT SLEEP(10) COLLATE utf8mb4_general_ci FROM customer",
            "SELECT CONVERT((SELECT password FROM secrets) USING utf8mb4) COLLATE utf8mb4_general_ci FROM customer",
            "SELECT id FROM customer WHERE id IS DISTINCT FROM SLEEP(10)",
            "SELECT JSON_OBJECT('value',SLEEP(10)) FROM customer",
            "SELECT JSON_ARRAYAGG(SLEEP(10)) FROM customer",
            "SELECT CAST(SLEEP(10) AS SIGNED) FROM customer",
            "SELECT CASE WHEN id=1 THEN SLEEP(10) ELSE 0 END FROM customer",
            "SELECT id FROM customer WHERE id IN (SELECT SLEEP(10) FROM customer)",
            "SELECT BENCHMARK(1000,1) FROM customer",
            "SELECT LOAD_FILE('/etc/passwd') FROM customer",
            "SELECT my_custom_function(id) FROM customer",
            "SELECT @value FROM customer",
            "SELECT @value:=id FROM customer",
            "WITH RECURSIVE hidden AS (SELECT id FROM customer) SELECT id FROM hidden",
            "SELECT id FROM secrets WHERE EXISTS (WITH secrets AS (SELECT id FROM customer) SELECT id FROM secrets)"
    })
    void rejectsUnsafeSqlAndHiddenTables(String sql) {
        assertThat(guard().validate(sql).allowed()).as(sql).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id FROM customer WHERE id=:userId",
            "SELECT id FROM customer WHERE id=?",
            "SELECT id FROM customer LIMIT :maxRows"
    })
    void modelCannotInjectServerBindings(String sql) {
        assertThatThrownBy(() -> AiAgentSqlParser.parse(sql, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsNestedReadOnlyQueriesAndSqlWordsInLiterals() {
        assertThat(guard().validate("WITH scoped AS (SELECT id FROM customer) SELECT id FROM scoped").allowed()).isTrue();
        assertThat(guard().validate("WITH first_cte AS (SELECT id FROM customer), second_cte AS (SELECT id FROM first_cte) SELECT id FROM second_cte").allowed()).isTrue();
        assertThat(guard().validate("SELECT id FROM customer WHERE name='delete; sleep(10)'").allowed()).isTrue();
        assertThat(guard().validate("SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS ranking FROM customer").allowed()).isTrue();
        assertThat(guard().validate("SELECT GROUP_CONCAT(DISTINCT name ORDER BY name SEPARATOR ',') FROM customer").allowed()).isTrue();
    }
}
