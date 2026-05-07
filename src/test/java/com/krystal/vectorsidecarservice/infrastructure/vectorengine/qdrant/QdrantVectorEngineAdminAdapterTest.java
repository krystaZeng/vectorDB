package com.krystal.vectorsidecarservice.infrastructure.vectorengine.qdrant;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantVectorEngineAdminAdapterTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder().baseUrl("http://qdrant.test");
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @Test
    void shouldCreateAliasWhenItDoesNotExist() {
        expectAliasLookup(aliasesJson());
        server.expect(requestTo("http://qdrant.test/collections/aliases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"alias_name\":\"doc_active\"")))
                .andExpect(content().string(containsString("\"collection_name\":\"doc_v1\"")))
                .andRespond(withSuccess("{\"status\":\"ok\",\"result\":true}", MediaType.APPLICATION_JSON));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureAlias(
                new VectorEngineAdminPort.EnsureAliasCommand("doc_active", "doc_v1")
        );

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.CREATED);
        server.verify();
    }

    @Test
    void shouldTreatExistingAliasToExpectedCollectionAsIdempotentSuccess() {
        expectAliasLookup(aliasesJson(aliasJson("doc_active", "doc_v1")));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureAlias(
                new VectorEngineAdminPort.EnsureAliasCommand("doc_active", "doc_v1")
        );

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
    }

    @Test
    void shouldRejectExistingAliasToDifferentCollection() {
        expectAliasLookup(aliasesJson(aliasJson("doc_active", "doc_v0")));

        assertThatThrownBy(() -> adapter().ensureAlias(
                new VectorEngineAdminPort.EnsureAliasCommand("doc_active", "doc_v1")
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant alias doc_active already points to doc_v0, expected doc_v1");
        server.verify();
    }

    @Test
    void shouldRecheckAliasTargetAfterCreateConflict() {
        expectAliasLookup(aliasesJson());
        expectAliasCreateConflict();
        expectAliasLookup(aliasesJson(aliasJson("doc_active", "doc_v1")));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureAlias(
                new VectorEngineAdminPort.EnsureAliasCommand("doc_active", "doc_v1")
        );

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
    }

    @Test
    void shouldRejectCreateConflictWhenAliasPointsToDifferentCollection() {
        expectAliasLookup(aliasesJson());
        expectAliasCreateConflict();
        expectAliasLookup(aliasesJson(aliasJson("doc_active", "doc_v0")));

        assertThatThrownBy(() -> adapter().ensureAlias(
                new VectorEngineAdminPort.EnsureAliasCommand("doc_active", "doc_v1")
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant alias doc_active already points to doc_v0, expected doc_v1");
        server.verify();
    }

    private QdrantVectorEngineAdminAdapter adapter() {
        return new QdrantVectorEngineAdminAdapter(
                new ObjectMapper(),
                restClientBuilder.build(),
                true,
                ""
        );
    }

    private void expectAliasLookup(String body) {
        server.expect(requestTo("http://qdrant.test/aliases"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectAliasCreateConflict() {
        server.expect(requestTo("http://qdrant.test/collections/aliases"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"result\":null}"));
    }

    private String aliasesJson(String... aliases) {
        return "{\"status\":\"ok\",\"result\":{\"aliases\":[" + String.join(",", aliases) + "]}}";
    }

    private String aliasJson(String aliasName, String collectionName) {
        return "{\"alias_name\":\"" + aliasName + "\",\"collection_name\":\"" + collectionName + "\"}";
    }
}
