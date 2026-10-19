package com.atomeng.erp.fcd.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CardServiceKeyGuardTest {

    @Test
    @DisplayName("SEC-003: 운영 모드에서 PAN 토큰화 HMAC 키가 개발용 기본값 · 미설정 · 32바이트 미만이면 기동 실패")
    void devKeyRejectedInOidcMode() {
        assertThatThrownBy(() -> CardService.requireUsableKey(CardService.DEV_KEY, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CardService.requireUsableKey(null, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CardService.requireUsableKey("short-key", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> CardService.requireUsableKey("x".repeat(32), true)).doesNotThrowAnyException();
        // 개발/테스트 헤더 인증 모드는 합성 데이터용 기본 키 허용
        assertThatCode(() -> CardService.requireUsableKey(CardService.DEV_KEY, false)).doesNotThrowAnyException();
    }
}
