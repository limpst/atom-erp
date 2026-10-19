package com.atomeng.erp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** 기록된 AIP 워커 계약(services/aip-worker/contract/openapi.json)으로 요청 · 응답 검증(모듈 시험 OpenApiContract 와 같은 방식) */
public final class AipContract {

    static final ObjectMapper M = new ObjectMapper();
    private final JsonNode spec;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private AipContract(JsonNode spec) {
        this.spec = spec;
    }

    public static AipContract load() throws Exception {
        String root = System.getProperty("atom.repo.root");
        return new AipContract(M.readTree(Files.readString(Path.of(root, "services", "aip-worker", "contract", "openapi.json"))));
    }

    private Set<ValidationMessage> validate(JsonNode schema, JsonNode body) {
        ObjectNode root = M.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.set("components", spec.path("components"));
        root.setAll((ObjectNode) schema.deepCopy());
        return factory.getSchema(root).validate(body);
    }

    public Set<ValidationMessage> request(String path, JsonNode body) {
        return validate(spec.path("paths").path(path).path("post").path("requestBody").path("content")
                .path("application/json").path("schema"), body);
    }

    public Set<ValidationMessage> response(String path, JsonNode body) {
        return validate(spec.path("paths").path(path).path("post").path("responses").path("200").path("content")
                .path("application/json").path("schema"), body);
    }
}
