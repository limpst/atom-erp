package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import com.atomeng.erp.fcd.domain.ingest.FileParser;
import com.atomeng.erp.fcd.domain.ingest.FileParser.ParseResult;
import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.MappingProfile;
import com.atomeng.erp.fcd.domain.ingest.MappingProfileYaml;
import com.atomeng.erp.fcd.domain.ingest.MappingProfileYaml.ProfileFormatException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 온보딩 YAML 매핑 프로파일 — Python {@code fcd-onboard profile-from-header} 가 만든 파일을 Java 수집이 그대로 읽는지.
 * 고정 파일(src/test/resources/fcd/onboard)은 atom-fcd {@code tools/gen_erp_onboard_fixtures.py} 로 생성(모두 합성 값),
 * Python 쪽 {@code test_java_fixture_matches_python_generator} 가 생성기와의 일치를 검사한다.
 */
class MappingProfileYamlTest {

    static MappingProfile yaml(String name) {
        return MappingProfileYaml.parse(new String(IngestTest.resource("/fcd/onboard/" + name), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("기본 프로파일 3종 YAML = V0034 시드와 같은 값")
    void defaultProfilesRoundTrip() {
        List<MappingProfile> fromYaml = List.of(yaml("P-ACARD-APPROVAL-CSV-1.yaml"), yaml("P-BCARD-USAGE-CSV-1.yaml"),
                yaml("P-CCARD-PURCHASE-XLSX-1.yaml"));
        List<MappingProfile> seed = IngestTest.profiles();
        for (int i = 0; i < seed.size(); i++) {
            MappingProfile a = fromYaml.get(i);
            MappingProfile b = seed.get(i);
            // 이름(name)은 시드가 짧은 표기라 제외하고 나머지 전부 일치
            assertThat(a.profileCode()).isEqualTo(b.profileCode());
            assertThat(a.cardCompany()).isEqualTo(b.cardCompany());
            assertThat(a.fileType()).isEqualTo(b.fileType());
            assertThat(a.kind()).isEqualTo(b.kind());
            assertThat(a.headerSignature()).isEqualTo(b.headerSignature());
            assertThat(a.columnMap()).isEqualTo(b.columnMap());
            assertThat(a.dateFormat()).isEqualTo(b.dateFormat());
            assertThat(a.cancelMode()).isEqualTo(b.cancelMode());
            assertThat(a.cancelValues()).isEqualTo(b.cancelValues());
            assertThat(a.partialCancelValues()).isEqualTo(b.partialCancelValues());
            assertThat(a.footerStopPattern()).isEqualTo(b.footerStopPattern());
            assertThat(a.active()).isTrue();
        }
    }

    @Test
    @DisplayName("온보딩 생성 프로파일(CP949 · 공백 · 괄호 헤더) → 자동 선택 · 수집 · 취소 · 합계 행")
    void generatedProfileParsesSample() {
        MappingProfile x = yaml("P-XCARD-APPROVAL-CSV-1.yaml");
        assertThat(x.profileCode()).isEqualTo("P-XCARD-APPROVAL-CSV-1");
        assertThat(x.columnMap()).containsEntry("card_ref", "카드 번호[마스킹]").containsEntry("amount", "이용금액(원)")
                .doesNotContainValue("이용자명");
        List<MappingProfile> all = new ArrayList<>(IngestTest.profiles());
        all.add(x);
        byte[] data = IngestTest.resource("/fcd/onboard/xcard_approval_sample_cp949.csv");
        ParseResult r = FileParser.parse(data, IngestTest.ONE_CARD, all, null);
        assertThat(r.needsMapping()).isFalse();
        assertThat(r.profile().profileCode()).isEqualTo("P-XCARD-APPROVAL-CSV-1");
        assertThat(r.profileScore()).isEqualTo(1.0);
        assertThat(r.encoding()).isEqualTo("CP949");
        assertThat(r.headerRow()).isEqualTo(2);
        assertThat(r.errors()).isEmpty();
        assertThat(r.rowsTotal()).isEqualTo(5);
        assertThat(r.rows()).hasSize(5).allSatisfy(c -> assertThat(c.cardId()).isEqualTo(IngestTest.CARD_A));
        assertThat(r.rows().stream().filter(c -> c.kind().equals("CANCEL")).map(CanonicalRow::cancelAmount))
                .containsExactly(8900L);
        assertThat(r.rows().get(0).precision()).isEqualTo("MINUTE");
        // 강제 지정만으로도 사전 밖 헤더("이용금액(원)" 등)로 헤더 행을 찾는다
        ParseResult forced = FileParser.parse(data, IngestTest.ONE_CARD, List.of(), x);
        assertThat(forced.rows()).hasSize(5);
    }

    @Test
    @DisplayName("형식 표식 · 필수 키 · 열거값 검증, 임의 타입 태그 거부(SafeConstructor)")
    void rejectsBadYaml() {
        String ok = new String(IngestTest.resource("/fcd/onboard/P-XCARD-APPROVAL-CSV-1.yaml"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MappingProfileYaml.parse(ok.replace("fcd-mapping-profile/v1", "other/v9")))
                .isInstanceOf(ProfileFormatException.class);
        assertThatThrownBy(() -> MappingProfileYaml.parse(ok.replace("cancel_mode: NEGATIVE_AMOUNT", "cancel_mode: X")))
                .isInstanceOf(ProfileFormatException.class);
        assertThatThrownBy(() -> MappingProfileYaml.parse(ok.replace("profile_id: P-XCARD-APPROVAL-CSV-1", "profile_id: ''")))
                .isInstanceOf(ProfileFormatException.class);
        assertThatThrownBy(() -> MappingProfileYaml.parse("!!java.io.File [\"x\"]")).isInstanceOf(RuntimeException.class);
    }
}
