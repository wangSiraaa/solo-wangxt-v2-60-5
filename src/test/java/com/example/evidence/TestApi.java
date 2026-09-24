package com.example.evidence;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/** Small fluent HTTP helper used by the acceptance tests. */
public class TestApi {

    private final String base;
    private final org.springframework.boot.test.web.client.TestRestTemplate http;

    public TestApi(String base, org.springframework.boot.test.web.client.TestRestTemplate http) {
        this.base = base;
        this.http = http;
    }

    public ResponseEntity<Map> post(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(base + path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    public ResponseEntity<Map> post(String path, Object body) {
        return post(path, body, null);
    }

    public ResponseEntity<Map> get(String path) {
        return http.getForEntity(base + path, Map.class);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(ResponseEntity<Map> response) {
        return response.getBody();
    }
}
