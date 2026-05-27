package com.krystal.vectorsidecarservice.infrastructure.vectorengine.qdrant;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

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
    void shouldCreateCollectionWhenItDoesNotExist() {
        expectCollectionLookupNotFound("doc_v1");
        server.expect(requestTo("http://qdrant.test/collections/doc_v1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("\"size\":3")))
                .andExpect(content().string(containsString("\"distance\":\"Cosine\"")))
                .andRespond(withSuccess("{\"status\":\"ok\",\"result\":true}", MediaType.APPLICATION_JSON));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE"));

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.CREATED);
        server.verify();
    }

    @Test
    void shouldTreatExistingUnnamedCollectionWithMatchingConfigAsIdempotentSuccess() {
        expectCollectionLookup("doc_v1", collectionJson(unnamedVectorJson(3, "Cosine")));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE"));

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
    }

    @Test
    void shouldNormalizeDistanceCaseWhenValidatingExistingCollection() {
        expectCollectionLookup("doc_v1", collectionJson(unnamedVectorJson(3, "cosine")));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE"));

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
    }

    @Test
    void shouldRejectExistingCollectionWithDifferentDimension() {
        expectCollectionLookup("doc_v1", collectionJson(unnamedVectorJson(4, "Cosine")));

        assertThatThrownBy(() -> adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE")))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant collection vector dimension mismatch: collection=doc_v1, expected=3, actual=4");
        server.verify();
    }

    @Test
    void shouldRejectExistingCollectionWithDifferentDistance() {
        expectCollectionLookup("doc_v1", collectionJson(unnamedVectorJson(3, "Dot")));

        assertThatThrownBy(() -> adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE")))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant collection distance mismatch: collection=doc_v1, expected=COSINE, actual=DOT");
        server.verify();
    }

    @Test
    void shouldRejectNamedDefaultCollectionWhenCommandExpectsUnnamedVector() {
        expectCollectionLookup("doc_v1", collectionJson(namedVectorsJson(namedVectorJson("default", 3, "Cosine"))));

        assertThatThrownBy(() -> adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE")))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant collection vector mode mismatch: collection=doc_v1, expected unnamed vector, actual named vectors=[default]");
        server.verify();
    }

    @Test
    void shouldAllowExtraNamedVectorsWhenExpectedNamedVectorMatches() {
        expectCollectionLookup("doc_v1", collectionJson(namedVectorsJson(
                namedVectorJson("image", 512, "Cosine"),
                namedVectorJson("text", 768, "Dot")
        )));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureCollection(ensureCollectionCommand("doc_v1", "image", 512, "COSINE"));

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
    }

    @Test
    void shouldMatchNamedVectorCaseSensitively() {
        expectCollectionLookup("doc_v1", collectionJson(namedVectorsJson(namedVectorJson("image", 512, "Cosine"))));

        assertThatThrownBy(() -> adapter().ensureCollection(ensureCollectionCommand("doc_v1", "IMAGE", 512, "COSINE")))
                .isInstanceOf(BizException.class)
                .hasMessage("qdrant named vector not found: collection=doc_v1, vectorName=IMAGE, actual named vectors=[image]");
        server.verify();
    }

    @Test
    void shouldRecheckCollectionConfigAfterCreateConflict() {
        expectCollectionLookupNotFound("doc_v1");
        server.expect(requestTo("http://qdrant.test/collections/doc_v1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"result\":null}"));
        expectCollectionLookup("doc_v1", collectionJson(unnamedVectorJson(3, "Cosine")));

        VectorEngineAdminPort.EnsureResult result = adapter().ensureCollection(ensureCollectionCommand("doc_v1", "default", 3, "COSINE"));

        assertThat(result.status()).isEqualTo(VectorEngineAdminPort.EnsureStatus.ALREADY_EXISTS);
        server.verify();
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

    @Test
    void shouldUpsertPointWithPayload() {
        server.expect(requestTo("http://qdrant.test/collections/doc_active/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(containsString("\"id\":123")))
                .andExpect(content().string(containsString("\"vector\"")))
                .andExpect(content().string(containsString("\"docType\":\"news\"")))
                .andRespond(withSuccess("{\"status\":\"ok\",\"result\":{\"operation_id\":1}}", MediaType.APPLICATION_JSON));

        VectorEngineDataPort.UpsertPointResult result = adapter().upsertPoint(
                new VectorEngineDataPort.UpsertPointCommand(
                        "doc_active",
                        "default",
                        123L,
                        List.of(0.1f, 0.2f, 0.3f),
                        Map.of("docType", "news")
                )
        );

        assertThat(result.status()).isEqualTo(VectorEngineDataPort.UpsertPointStatus.UPSERTED);
        server.verify();
    }

    @Test
    void shouldDeletePoint() {
        server.expect(requestTo("http://qdrant.test/collections/doc_active/points/delete?wait=true"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"points\":[123]")))
                .andRespond(withSuccess("{\"status\":\"ok\",\"result\":{\"operation_id\":2}}", MediaType.APPLICATION_JSON));

        VectorEngineDataPort.DeletePointResult result = adapter().deletePoint(
                new VectorEngineDataPort.DeletePointCommand(
                        "doc_active",
                        123L
                )
        );

        assertThat(result.status()).isEqualTo(VectorEngineDataPort.DeletePointStatus.DELETED);
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

    private VectorEngineAdminPort.EnsureCollectionCommand ensureCollectionCommand(
            String collectionName,
            String vectorName,
            int vectorDim,
            String distance
    ) {
        return new VectorEngineAdminPort.EnsureCollectionCommand(
                collectionName,
                vectorDim,
                distance,
                vectorName,
                "N",
                null,
                null,
                null
        );
    }

    private void expectCollectionLookup(String collectionName, String body) {
        server.expect(requestTo("http://qdrant.test/collections/" + collectionName))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectCollectionLookupNotFound(String collectionName) {
        server.expect(requestTo("http://qdrant.test/collections/" + collectionName))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"result\":null}"));
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

    private String collectionJson(String vectorsJson) {
        return "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":" + vectorsJson + "}}}}";
    }

    private String unnamedVectorJson(int size, String distance) {
        return "{\"size\":" + size + ",\"distance\":\"" + distance + "\",\"on_disk\":true}";
    }

    private String namedVectorsJson(String... vectors) {
        return "{" + String.join(",", vectors) + "}";
    }

    private String namedVectorJson(String name, int size, String distance) {
        return "\"" + name + "\":{\"size\":" + size + ",\"distance\":\"" + distance + "\",\"datatype\":\"float32\"}";
    }
}
