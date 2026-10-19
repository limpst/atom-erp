package com.atomeng.erp.aip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 기록된 워커 계약(services/aip-worker/contract/openapi.json — FastAPI 가 생성, Python 시험이 앱과 일치를 보장)으로
 * 요청 · 응답 JSON 을 검증한다(JSON Schema 2020-12, OpenAPI 3.1 components 참조).
 */
final class OpenApiContract {

    static final ObjectMapper M = new ObjectMapper();
    private final JsonNode spec;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private OpenApiContract(JsonNode spec) {
        this.spec = spec;
    }

    static Path specPath() {
        String root = System.getProperty("atom.repo.root", Path.of("").toAbsolutePath().getParent().getParent().toString());
        return Path.of(root, "services", "aip-worker", "contract", "openapi.json");
    }

    static OpenApiContract load() throws Exception {
        return new OpenApiContract(M.readTree(Files.readString(specPath())));
    }

    JsonNode spec() {
        return spec;
    }

    private JsonSchema schemaFor(JsonNode refOrSchema) {
        ObjectNode root = M.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.set("components", spec.path("components"));
        root.setAll((ObjectNode) refOrSchema.deepCopy());
        return factory.getSchema(root);
    }

    Set<ValidationMessage> validateRequest(String path, JsonNode body) {
        JsonNode s = spec.path("paths").path(path).path("post").path("requestBody").path("content")
                .path("application/json").path("schema");
        if (s.isMissingNode()) {
            throw new IllegalArgumentException("계약에 요청 본문 없음: " + path);
        }
        return schemaFor(s).validate(body);
    }

    Set<ValidationMessage> validateResponse(String path, String method, String status, JsonNode body) {
        JsonNode s = spec.path("paths").path(path).path(method).path("responses").path(status).path("content")
                .path("application/json").path("schema");
        if (s.isMissingNode()) {
            throw new IllegalArgumentException("계약에 응답 없음: " + method + " " + path + " " + status);
        }
        return schemaFor(s).validate(body);
    }
}
