package com.vintic.backend.ai.purchase.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// 모델 표기(한/영, 띄어쓰기·하이픈 변형)를 시세 CSV의 model_key로 접는다 (#95).
//
// 사용자는 "뉴발 990"이라 쓰고, 판매자는 "990v6"라 쓰고, 시세 CSV는 nb990이라 부른다.
// 문자열로 비교하면 셋이 전부 다른 신발이 된다. Product.model이 Vision 초안 기반 자유
// 텍스트(정확 표기율 22%)라 구조화 필드 동등 비교로는 pre-filter가 거의 다 탈락한다.
// 그래서 파서·Matcher·pre-filter·시세 조회가 전부 이 표 하나로 같은 키에 도달하게 한다.
//
// crawler/calibration/model_aliases.py와 같은 발상이지만 키 체계는 used_market_prices.csv를
// 따른다 - 파서가 고른 키로 바로 시세를 찾을 수 있어야 하기 때문이다. 표를 고칠 때는
// data/model_aliases.csv만 고치면 되고 코드는 건드리지 않는다.
//
// 매칭 규칙:
//   - 별칭·입력 모두 소문자 + 영숫자/한글만 남긴다 ("뉴발 990" == "뉴발990" == "뉴발-990").
//   - 긴 별칭부터 검사한다 ("덩크하이"가 "덩크"에 먹히지 않게).
//   - 숫자만 있는 모델(530/990/1461...)은 브랜드 표기가 붙은 별칭만 둔다. "990" 단독을 허용하면
//     가격·사이즈·품번 숫자에 붙는다.
@Component
@Slf4j
public class ModelAliases {

    private static final String CSV_PATH = "data/model_aliases.csv";

    public record ModelInfo(String brand, String modelKey, String modelDisplay) {

        // 확인 화면·프롬프트에 보여줄 이름. "New Balance 990" 형태.
        public String fullName() {
            return brand + " " + modelDisplay;
        }
    }

    // 원문에서 어느 구간이 모델 표기였는지도 함께 돌려준다. 파서가 그 구간을 지워야
    // 남은 텍스트를 자유 조건으로 넘길 수 있다.
    public record Match(ModelInfo model, String matchedAlias) {
    }

    private record Alias(String raw, String normalized, ModelInfo model) {
    }

    private final List<Alias> aliasesLongestFirst;
    private final Map<String, ModelInfo> byKey;
    private final Map<String, List<String>> aliasesByKey;

    public ModelAliases() {
        List<Alias> aliases = load();
        aliases.sort(Comparator.comparingInt((Alias a) -> a.normalized().length()).reversed());
        this.aliasesLongestFirst = List.copyOf(aliases);

        Map<String, ModelInfo> keyMap = new LinkedHashMap<>();
        Map<String, List<String>> aliasMap = new LinkedHashMap<>();
        for (Alias alias : aliases) {
            keyMap.putIfAbsent(alias.model().modelKey(), alias.model());
            aliasMap.computeIfAbsent(alias.model().modelKey(), k -> new ArrayList<>()).add(alias.raw());
        }
        this.byKey = Map.copyOf(keyMap);
        this.aliasesByKey = aliasMap;
        log.info("모델 별칭 표 로드 - 모델 {}개, 별칭 {}개", byKey.size(), aliases.size());
    }

    public Optional<Match> find(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        for (Alias alias : aliasesLongestFirst) {
            if (normalized.contains(alias.normalized())) {
                return Optional.of(new Match(alias.model(), alias.raw()));
            }
        }
        return Optional.empty();
    }

    public Optional<ModelInfo> byKey(String modelKey) {
        if (modelKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(modelKey.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean isKnownKey(String modelKey) {
        return byKey(modelKey).isPresent();
    }

    // 프롬프트에 카탈로그를 실어 보내기 위한 목록. 키 등록 순서를 유지한다.
    public List<ModelInfo> catalog() {
        return List.copyOf(byKey.values());
    }

    public List<String> aliasesOf(String modelKey) {
        return List.copyOf(aliasesByKey.getOrDefault(modelKey, List.of()));
    }

    // 소문자 + 영숫자/한글만 남긴다. 공백·하이픈·점·괄호 등 표기 흔들림을 전부 지운다.
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]", "");
    }

    private List<Alias> load() {
        ClassPathResource resource = new ClassPathResource(CSV_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("모델 별칭 표가 없습니다: " + CSV_PATH);
        }
        List<Alias> aliases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] columns = trimmed.split(",", -1);
                if (columns.length < 4) {
                    throw new IllegalStateException("모델 별칭 표 형식 오류(4열 필요): " + line);
                }
                String raw = columns[0].trim();
                String normalized = normalize(raw);
                if (normalized.isEmpty()) {
                    throw new IllegalStateException("정규화하면 비는 별칭: " + line);
                }
                ModelInfo model = new ModelInfo(columns[1].trim(), columns[2].trim().toLowerCase(Locale.ROOT), columns[3].trim());
                aliases.add(new Alias(raw, normalized, model));
            }
        } catch (IOException e) {
            throw new IllegalStateException("모델 별칭 표를 읽지 못했습니다: " + CSV_PATH, e);
        }
        if (aliases.isEmpty()) {
            throw new IllegalStateException("모델 별칭 표가 비어 있습니다: " + CSV_PATH);
        }
        return aliases;
    }
}
