package com.atomeng.erp.qar.adapter.out.ai;

import com.atomeng.erp.qar.application.port.AtomAiCorePort;

/** atom.qar.ai.base-url 미설정 — 항상 미가용(AI 단계 스킵, reason=DISABLED) */
public class DisabledAtomAiCore implements AtomAiCorePort {

    @Override
    public Response review(Request request) {
        throw new AiUnavailableException("DISABLED: atom.qar.ai.base-url 미설정", null);
    }
}
