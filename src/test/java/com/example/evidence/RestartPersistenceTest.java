package com.example.evidence;

import com.example.evidence.EvidenceChainApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 6: after an application restart (fresh JVM-level Spring context,
 * same database), previously written chains still verify and idempotent
 * replays keep returning the original result without creating new events.
 */
class RestartPersistenceTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void chainsSurviveRestartAndReplayRemainsIdempotent() {
        TestApi api = new TestApi(url(""), http);

        // before "restart": create + start a plan with fixed idempotency keys
        var created = api.post("/api/plans",
                Map.of("planNo", "PL-RESTART-1", "title", "restart me"), "restart-create-key");
        long planId = ((Number) ((Map<?, ?>) TestApi.body(created).get("plan")).get("id")).longValue();
        var started = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START", "payload", Map.of("operator", "alice")), "restart-start-key");
        String startedHash = (String) ((Map<?, ?>) TestApi.body(started).get("event")).get("eventHash");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long eventsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId);

        // simulate a process restart: brand new application context on the same DB.
        // Command-line args have the highest precedence, overriding packaged application.yml.
        String[] args = {
                "--spring.datasource.url=jdbc:postgresql://localhost:" + PG.getPort() + "/postgres",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=postgres",
                "--spring.sql.init.mode=always",
                "--evidence.anchor-on-startup=false",
                "--server.port=0"
        };

        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(
                EvidenceChainApplication.class)
                .web(WebApplicationType.SERVLET)
                .build()
                .run(args)) {

            int restartedPort = (Integer) restarted.getEnvironment()
                    .getProperty("local.server.port", Integer.class);
            // schema init on restart must not fail (IF NOT EXISTS) and data must persist
            Long plans = jdbc.queryForObject("SELECT COUNT(*) FROM plan", Long.class);
            assertThat(plans).isGreaterThanOrEqualTo(1L);

            TestApi restartedApi = new TestApi("http://localhost:" + restartedPort,
                    new org.springframework.boot.test.web.client.TestRestTemplate());

            // chain verifies after restart
            Map<String, Object> verify = TestApi.body(restartedApi.get("/api/plans/" + planId + "/verify"));
            assertThat(verify.get("valid")).isEqualTo(true);

            // replay both requests with the same keys: same hashes, no new events
            var replayCreate = restartedApi.post("/api/plans",
                    Map.of("planNo", "PL-RESTART-1", "title", "restart me"), "restart-create-key");
            assertThat(TestApi.body(replayCreate).get("replayed")).isEqualTo(true);

            var replayStart = restartedApi.post("/api/plans/" + planId + "/actions",
                    Map.of("action", "START", "payload", Map.of("operator", "alice")),
                    "restart-start-key");
            Map<String, Object> replayBody = TestApi.body(replayStart);
            assertThat(replayBody.get("replayed")).isEqualTo(true);
            assertThat(((Map<?, ?>) replayBody.get("event")).get("eventHash")).isEqualTo(startedHash);
        }

        long eventsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId);
        assertThat(eventsAfter).isEqualTo(eventsBefore);
        List<Long> planRows = jdbc.queryForList(
                "SELECT COUNT(*) FROM plan WHERE plan_no = 'PL-RESTART-1'", Long.class);
        assertThat(planRows.get(0)).isEqualTo(1L);
    }
}
