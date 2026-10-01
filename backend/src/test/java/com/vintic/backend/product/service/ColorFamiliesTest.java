package com.vintic.backend.product.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ColorFamiliesTest {

    @Test
    void 표기가_달라도_같은_계열_키로_접힌다() {
        // gray/grey/회색/차콜 - 문자로는 전부 다르지만 같은 버킷에 붙어야 한다 (#93의 핵심)
        assertThat(ColorFamilies.colorKey("gray")).contains("grey");
        assertThat(ColorFamilies.colorKey("grey")).contains("grey");
        assertThat(ColorFamilies.colorKey("회색")).contains("grey");
        assertThat(ColorFamilies.colorKey("차콜")).contains("grey");
        assertThat(ColorFamilies.colorKey("Wolf Gray")).contains("grey");
    }

    @Test
    void 투톤은_계열_조합_키가_된다() {
        assertThat(ColorFamilies.colorKey("Black/White")).contains("black+white");
        assertThat(ColorFamilies.colorKey("화이트 블랙")).contains("black+white");
    }

    @Test
    void 두_계열에_걸치는_색은_두_계열을_모두_가진다() {
        assertThat(ColorFamilies.families("오프화이트")).isEqualTo(Set.of("white", "cream"));
        // "오프화이트"의 "화이트"가 이중으로 잡혀도 결과 집합은 같아야 한다(긴 표기 우선)
        assertThat(ColorFamilies.colorKey("오프화이트")).contains("cream+white");
    }

    @Test
    void 영문은_단어_단위로만_매칭한다() {
        // "titanium"이 부분 문자열 "tan"에 걸리면 안 된다
        assertThat(ColorFamilies.families("Titanium")).isEmpty();
    }

    @Test
    void 세_계열_이상이면_색상_시세를_시도하지_않는다() {
        // 색이 셋 이상 적힌 표기는 판독이 혼란스러워 버킷 키를 만들지 않는다
        assertThat(ColorFamilies.colorKey("black white red")).isEmpty();
    }

    @Test
    void 색상_표기가_없으면_빈_결과다() {
        assertThat(ColorFamilies.colorKey(null)).isEmpty();
        assertThat(ColorFamilies.colorKey("Panda")).isEmpty();
    }
}
