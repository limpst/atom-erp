package com.atomeng.erp.aip.adapter.out.worker;

import com.atomeng.erp.aip.api.AipJobApi.AipDisabledException;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/** atom.aip.worker.base-url 미설정(기본) — 워커 미연결. 동기 호출은 DISABLED, 비동기 Job 은 QUEUED 로 대기한다. */
public class DisabledAipWorker implements AipWorkerPort {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Response post(String path, JsonNode body, Duration timeout) {
        throw new AipDisabledException("DISABLED: atom.aip.worker.base-url 미설정");
    }
}
