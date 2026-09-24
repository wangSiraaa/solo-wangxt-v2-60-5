package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebApiSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    private String body(Map<String, String> fields) throws Exception {
        return json.writeValueAsString(fields);
    }

    @Test
    void fullLifecycleOverHttpReplayAndVerify() throws Exception {
        Map<String, String> create = new LinkedHashMap<>();
        create.put("planNo", "P-HTTP-1");
        create.put("title", "via api");
        create.put("operator", "alice");
        create.put("idempotencyKey", "ck");
        MvcResult created = mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        long planId = json.readTree(created.getResponse().getContentAsString())
                .get("planId").asLong();

        act(planId, "START", "alice", null, "sk");
        MvcResult firstConfirm = act(planId, "CONFIRMATION", "bob", "ok", "cfk");
        JsonNode firstJson = json.readTree(firstConfirm.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(firstJson.get("seq").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(firstJson.get("replayed").asBoolean()).isFalse();

        // Idempotent replay through HTTP: same seq/hash, replayed=true.
        MvcResult replay = act(planId, "CONFIRMATION", "bob", "ok", "cfk");
        JsonNode replayJson = json.readTree(replay.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(replayJson.get("seq").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(replayJson.get("replayed").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(replayJson.get("eventHash").asText())
                .isEqualTo(firstJson.get("eventHash").asText());

        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("action", "MOCK_RECEIPT");
        receipt.put("operator", "system");
        receipt.put("channel", "ERP");
        receipt.put("receiptPayload", "{\"code\":\"OK\"}");
        receipt.put("idempotencyKey", "rck");
        mockMvc.perform(post("/api/plans/" + planId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body(receipt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        // Replay of the receipt as well.
        mockMvc.perform(post("/api/plans/" + planId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body(receipt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        act(planId, "WRITEOFF", "carol", "done", "wk");

        mockMvc.perform(get("/api/plans/" + planId + "/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.eventCount").value(5));

        mockMvc.perform(get("/api/plans/" + planId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(5));
    }

    @Test
    void invalidActionReturns400() throws Exception {
        Map<String, String> create = new LinkedHashMap<>();
        create.put("planNo", "P-HTTP-2");
        create.put("title", "x");
        create.put("operator", "a");
        create.put("idempotencyKey", "ck2");
        MvcResult created = mockMvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(body(create)))
                .andReturn();
        long planId = json.readTree(created.getResponse().getContentAsString())
                .get("planId").asLong();

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("action", "EXPLODE");
        bad.put("operator", "a");
        bad.put("idempotencyKey", "bk");
        mockMvc.perform(post("/api/plans/" + planId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body(bad)))
                .andExpect(status().isBadRequest());
    }

    private MvcResult act(long planId, String action, String operator,
                          String remark, String key) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("action", action);
        map.put("operator", operator);
        if (remark != null) {
            map.put("remark", remark);
        }
        map.put("idempotencyKey", key);
        return mockMvc.perform(post("/api/plans/" + planId + "/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(body(map)))
                .andExpect(status().isOk())
                .andReturn();
    }
}
