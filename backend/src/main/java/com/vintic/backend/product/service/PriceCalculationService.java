package com.vintic.backend.product.service;

import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.MarketPriceDataLoader.MarketPriceRow;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class PriceCalculationService {

    private static final double KREAM_WEIGHT = 0.7;
    private static final double EBAY_WEIGHT = 0.3;
    private static final double PRICE_RANGE_RATE = 0.05;
    private static final String UNKNOWN_CONDITION_GRADE = "UNKNOWN";
    // 상태 불문 전체 매물의 계수. 중고 시세 경로에서 상태 비율의 분모(기준선)가 된다.
    private static final String ALL_CONDITION_GRADE = "ALL";

    private final MarketPriceDataLoader marketPriceDataLoader;
    private final ConditionRateProvider conditionRateProvider;
    private final UsedMarketPriceProvider usedMarketPriceProvider;

    public PriceCalculationService(
            MarketPriceDataLoader marketPriceDataLoader,
            ConditionRateProvider conditionRateProvider,
            UsedMarketPriceProvider usedMarketPriceProvider
    ) {
        this.marketPriceDataLoader = marketPriceDataLoader;
        this.conditionRateProvider = conditionRateProvider;
        this.usedMarketPriceProvider = usedMarketPriceProvider;
    }

    public CalculatePriceResponse calculate(CalculatePriceRequest request) {
        // 1순위: 같은 모델의 중고 실거래 시세 (#86).
        //
        // 우리 서비스는 중고 경매다. 중고 실거래가 있으면 "새제품가 x 상태계수"라는
        // 추정을 거칠 이유가 없다. 매칭이 없을 때만 기존 KREAM/eBay 방식으로 넘어간다 -
        // 기존에 "시세 정보 없음"이 나오던 요청 일부가 이 경로로 값을 받게 되고,
        // 응답이 나빠지는 경로는 없다.
        Optional<UsedMarketPriceProvider.UsedMarketPrice> usedMarket =
                usedMarketPriceProvider.find(request.brand(), request.modelName());
        if (usedMarket.isPresent()) {
            return calculateFromUsedMarket(request, usedMarket.get());
        }

        List<MarketPriceRow> kreamRows = marketPriceDataLoader.loadKreamRows();
        List<MarketPriceRow> ebayRows = marketPriceDataLoader.loadEbayRows();

        List<MarketPriceRow> kreamMatches = findMatches(kreamRows, request);
        List<MarketPriceRow> ebayMatches = findMatches(ebayRows, request);

        int kreamAveragePrice = calculateAveragePrice(kreamMatches);
        int ebayAveragePrice = calculateAveragePrice(ebayMatches);

        if (kreamAveragePrice == 0 && ebayAveragePrice == 0) {
            return new CalculatePriceResponse(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    "시세 정보 없음",
                    "입력한 브랜드, 모델명, 색상, 사이즈와 일치하는 KREAM/eBay 시세 데이터를 찾지 못했습니다. 추천 가격 산정을 위해서는 유사 거래 데이터가 추가로 필요합니다.",
                    List.of(),
                    List.of()
            );
        }

        int baseMarketPrice = calculateBaseMarketPrice(kreamAveragePrice, ebayAveragePrice);

        String normalizedConditionGrade = normalizeConditionGrade(request.conditionGrade());
        ConditionRateProvider.ConditionRate rate =
                conditionRateProvider.resolve(request.modelName(), normalizedConditionGrade);
        double conditionRate = rate.rate();
        double componentRate = getComponentRate(request.componentStatus());

        int calculatedPrice = (int) Math.round(baseMarketPrice * conditionRate * componentRate);
        int recommendedPrice = roundToNearestThousand(calculatedPrice);

        int minRecommendedPrice = calculateMinRecommendedPrice(recommendedPrice);
        int maxRecommendedPrice = calculateMaxRecommendedPrice(recommendedPrice);
        String priceRange = makePriceRange(minRecommendedPrice, maxRecommendedPrice);

        String reason = makeReason(
                kreamMatches.size(),
                ebayMatches.size(),
                kreamAveragePrice,
                ebayAveragePrice,
                baseMarketPrice,
                recommendedPrice,
                normalizedConditionGrade,
                rate,
                request.componentStatus(),
                componentRate,
                priceRange
        );

        return new CalculatePriceResponse(
                recommendedPrice,
                baseMarketPrice,
                kreamAveragePrice,
                ebayAveragePrice,
                minRecommendedPrice,
                maxRecommendedPrice,
                priceRange,
                reason,
                toResponseMatches(kreamMatches),
                toResponseMatches(ebayMatches)
        );
    }

    /**
     * 중고 실거래 시세로 계산한다.
     *
     * <p>상태 반영은 절대 계수가 아니라 "전체 매물 대비 비율"이다. 시세 중앙값이
     * 상태 불문 전체 매물에서 나온 값이라, 분모도 같은 모집단(ALL)이어야 한다.
     * 처음엔 UNKNOWN(상태 단서 없는 매물)을 분모로 썼는데, 그 모집단에는 새상품
     * 매물이 빠져 있어 전체보다 낮고, 그만큼 모든 등급이 일괄로 높게 추천되는
     * 편향이 있었다. #61/#86에서 측정한 값만 조합한다.
     *
     * <p>권장 범위는 ±5% 같은 임의 폭 대신 실거래 IQR(25~75% 구간)을 쓴다.
     * 넓어 보일 수 있지만 그게 실제 분포다.
     */
    private CalculatePriceResponse calculateFromUsedMarket(
            CalculatePriceRequest request, UsedMarketPriceProvider.UsedMarketPrice market) {

        String normalizedConditionGrade = normalizeConditionGrade(request.conditionGrade());
        ConditionRateProvider.ConditionRate gradeRate =
                conditionRateProvider.resolve(request.modelName(), normalizedConditionGrade);
        // 등급 계수가 이 모델 전용 실측이면 기준선도 같은 모델 것을 쓴다.
        // 감가 속도가 모델마다 달라(에어포스1 0.44 vs 993 0.61) 분자와 분모의
        // 모델이 어긋나면 비율이 왜곡된다.
        ConditionRateProvider.ConditionRate baselineRate =
                gradeRate.basis() == ConditionRateProvider.Basis.MEASURED_MODEL
                        ? conditionRateProvider.resolve(request.modelName(), ALL_CONDITION_GRADE)
                        : conditionRateProvider.resolve(null, ALL_CONDITION_GRADE);
        // 상태를 모르면 전체 매물 중앙값을 그대로 쓴다. UNKNOWN 계수(상태를 안 적은
        // 매물의 시세)를 적용하면 "판매자의 침묵"과 "Vision이 못 읽음"을 같은
        // 신호로 취급하게 된다.
        double conditionRatio = UNKNOWN_CONDITION_GRADE.equals(normalizedConditionGrade)
                ? 1.0
                : gradeRate.rate() / baselineRate.rate();
        double componentRate = getComponentRate(request.componentStatus());

        int recommendedPrice =
                roundToNearestThousand((int) Math.round(market.medianPrice() * conditionRatio * componentRate));
        int minRecommendedPrice =
                roundToNearestThousand((int) Math.round(market.q1Price() * conditionRatio * componentRate));
        int maxRecommendedPrice =
                roundToNearestThousand((int) Math.round(market.q3Price() * conditionRatio * componentRate));
        String priceRange = makePriceRange(minRecommendedPrice, maxRecommendedPrice);

        // UNKNOWN은 비율을 1.0으로 고정하므로 계수 출처를 밝힐 것이 없다.
        String rateBasisText = UNKNOWN_CONDITION_GRADE.equals(normalizedConditionGrade)
                ? ""
                : makeRateBasisText(gradeRate);

        String reason = String.format(
                "당근마켓·후르츠패밀리에 올라온 %s 중고 매물 %d건을 근거로 계산했습니다. "
                        + "실거래가 중앙값은 %,d원이고, 매물의 절반이 %,d원 ~ %,d원 사이에 있습니다. "
                        + "상품 상태 %s(%s)는 전체 매물 시세 대비 %.0f%% 수준으로 반영했습니다.%s %s "
                        + "이를 바탕으로 최종 추천가는 %,d원이며, 판매 권장 범위는 실거래 분포를 따라 %s입니다.",
                market.modelDisplay(),
                market.listingCount(),
                market.medianPrice(),
                market.q1Price(),
                market.q3Price(),
                normalizedConditionGrade,
                getConditionDescription(normalizedConditionGrade),
                conditionRatio * 100,
                rateBasisText,
                makeComponentText(request.componentStatus(), componentRate),
                recommendedPrice,
                priceRange
        );

        // KREAM/eBay 필드는 이 경로에서 쓰이지 않았음을 그대로 드러낸다(0 / 빈 목록).
        // 어느 근거로 계산했는지는 reason이 밝힌다.
        return new CalculatePriceResponse(
                recommendedPrice,
                market.medianPrice(),
                0,
                0,
                minRecommendedPrice,
                maxRecommendedPrice,
                priceRange,
                reason,
                List.of(),
                List.of()
        );
    }

    private List<MarketPriceRow> findMatches(List<MarketPriceRow> rows, CalculatePriceRequest request) {
        return rows.stream()
                .filter(row -> equalsIgnoreCase(row.brand(), request.brand()))
                .filter(row -> containsBothWays(row.model(), request.modelName()))
                .filter(row -> containsBothWays(row.colorway(), request.color()))
                .filter(row -> row.sizeKr().equals(request.size()))
                .sorted(Comparator.comparingInt(MarketPriceRow::price))
                .toList();
    }

    private int calculateAveragePrice(List<MarketPriceRow> rows) {
        return (int) Math.round(
                rows.stream()
                        .mapToInt(MarketPriceRow::price)
                        .average()
                        .orElse(0)
        );
    }

    private int calculateBaseMarketPrice(int kreamAveragePrice, int ebayAveragePrice) {
        if (kreamAveragePrice > 0 && ebayAveragePrice > 0) {
            return (int) Math.round(kreamAveragePrice * KREAM_WEIGHT + ebayAveragePrice * EBAY_WEIGHT);
        }

        if (kreamAveragePrice > 0) {
            return kreamAveragePrice;
        }

        return ebayAveragePrice;
    }

    private String normalizeConditionGrade(String conditionGrade) {
        if (conditionGrade == null || conditionGrade.isBlank()) {
            return UNKNOWN_CONDITION_GRADE;
        }

        return conditionGrade.trim().toUpperCase();
    }

    private double getComponentRate(String componentStatus) {
        if (componentStatus == null || componentStatus.isBlank()) {
            return 0.97;
        }

        return switch (componentStatus.trim().toUpperCase()) {
            case "FULL" -> 1.00;
            case "PARTIAL" -> 0.97;
            case "NONE" -> 0.95;
            default -> 0.97;
        };
    }

    private int roundToNearestThousand(int price) {
        return (int) Math.round(price / 1000.0) * 1000;
    }

    private int calculateMinRecommendedPrice(int recommendedPrice) {
        return roundToNearestThousand((int) Math.round(recommendedPrice * (1 - PRICE_RANGE_RATE)));
    }

    private int calculateMaxRecommendedPrice(int recommendedPrice) {
        return roundToNearestThousand((int) Math.round(recommendedPrice * (1 + PRICE_RANGE_RATE)));
    }

    private String makePriceRange(int minRecommendedPrice, int maxRecommendedPrice) {
        return String.format("%,d원 ~ %,d원", minRecommendedPrice, maxRecommendedPrice);
    }

    private String makeReason(
            int kreamCount,
            int ebayCount,
            int kreamAveragePrice,
            int ebayAveragePrice,
            int baseMarketPrice,
            int recommendedPrice,
            String normalizedConditionGrade,
            ConditionRateProvider.ConditionRate rate,
            String componentStatus,
            double componentRate,
            String priceRange
    ) {
        String marketPriceText = makeMarketPriceText(
                kreamCount,
                ebayCount,
                kreamAveragePrice,
                ebayAveragePrice,
                baseMarketPrice
        );

        String conditionText = makeConditionText(normalizedConditionGrade, rate);
        String componentText = makeComponentText(componentStatus, componentRate);
        String comparisonText = makeComparisonText(kreamAveragePrice, ebayAveragePrice, recommendedPrice);

        return String.format(
                "%s %s %s 이를 바탕으로 최종 추천가는 %,d원으로 산정했으며, 판매 권장 범위는 %s입니다. %s",
                marketPriceText,
                conditionText,
                componentText,
                recommendedPrice,
                priceRange,
                comparisonText
        );
    }

    private String makeMarketPriceText(
            int kreamCount,
            int ebayCount,
            int kreamAveragePrice,
            int ebayAveragePrice,
            int baseMarketPrice
    ) {
        if (kreamAveragePrice > 0 && ebayAveragePrice > 0) {
            return String.format(
                    "KREAM 유사 거래 %d건의 평균가 %,d원과 eBay 유사 거래 %d건의 평균가 %,d원을 각각 %.0f%%, %.0f%% 비율로 반영해 기준 시세 %,d원을 계산했습니다.",
                    kreamCount,
                    kreamAveragePrice,
                    ebayCount,
                    ebayAveragePrice,
                    KREAM_WEIGHT * 100,
                    EBAY_WEIGHT * 100,
                    baseMarketPrice
            );
        }

        if (kreamAveragePrice > 0) {
            return String.format(
                    "KREAM 유사 거래 %d건의 평균가 %,d원을 기준 시세로 사용했습니다.",
                    kreamCount,
                    kreamAveragePrice
            );
        }

        return String.format(
                "KREAM 유사 거래는 찾지 못했지만, eBay 유사 거래 %d건의 평균가 %,d원을 기준 시세로 사용했습니다.",
                ebayCount,
                ebayAveragePrice
        );
    }

    private String makeConditionText(
            String normalizedConditionGrade, ConditionRateProvider.ConditionRate rate) {
        String description = getConditionDescription(normalizedConditionGrade);
        double conditionRate = rate.rate();
        String basis = makeRateBasisText(rate);

        if (UNKNOWN_CONDITION_GRADE.equals(normalizedConditionGrade)) {
            return String.format(
                    "상품 상태 등급이 명확하지 않아 기본 반영률 %.0f%%를 적용했습니다.%s",
                    conditionRate * 100,
                    basis
            );
        }

        if ("기타 상태".equals(description)) {
            return String.format(
                    "상품 상태 등급 %s는 사전에 정의되지 않은 값이므로 기본 반영률 %.0f%%를 적용했습니다.%s",
                    normalizedConditionGrade,
                    conditionRate * 100,
                    basis
            );
        }

        return String.format(
                "상품 상태는 %s(%s)로 판단하여 %.0f%% 반영률을 적용했습니다.%s",
                normalizedConditionGrade,
                description,
                conditionRate * 100,
                basis
        );
    }

    // 반영률이 실측에서 나온 값인지 밝힌다.
    //
    // 실측 계수를 쓴 것과 기본값으로 떨어진 것이 구분되지 않으면, 사용자는 두 값을 같은
    // 신뢰도로 받아들이고 우리도 이번 보정이 실제로 어떤 요청에 적용됐는지 알 수 없다.
    private String makeRateBasisText(ConditionRateProvider.ConditionRate rate) {
        return switch (rate.basis()) {
            case MEASURED_MODEL -> String.format(
                    " (이 모델의 당근마켓 실거래 %d건과 KREAM 시세를 대조해 산출한 값입니다)",
                    rate.sampleSize());
            case MEASURED_COMMON -> String.format(
                    " (모델별 실거래 표본이 부족해, 여러 모델의 실거래 %d건으로 산출한 공통값을 적용했습니다)",
                    rate.sampleSize());
            case DEFAULT -> " (실거래 표본이 부족해 기본값을 사용했습니다)";
        };
    }

    private String getConditionDescription(String conditionGrade) {
        return switch (conditionGrade) {
            case "DS" -> "새상품";
            case "S" -> "거의 새상품";
            case "A" -> "양호한 중고";
            case "B" -> "사용감 있음";
            case "C" -> "하자 있음";
            default -> "기타 상태";
        };
    }

    private String makeComponentText(String componentStatus, double componentRate) {
        String normalizedStatus = componentStatus == null ? "UNKNOWN" : componentStatus.trim().toUpperCase();

        return switch (normalizedStatus) {
            case "FULL" -> String.format(
                    "구성품이 모두 포함되어 있어 %.0f%% 반영률을 적용했습니다.",
                    componentRate * 100
            );
            case "PARTIAL" -> String.format(
                    "구성품이 일부 포함되어 있어 %.0f%% 반영률을 적용했습니다.",
                    componentRate * 100
            );
            case "NONE" -> String.format(
                    "구성품이 없어 %.0f%% 반영률을 적용했습니다.",
                    componentRate * 100
            );
            default -> String.format(
                    "구성품 상태를 명확히 판단하기 어려워 %.0f%% 반영률을 적용했습니다.",
                    componentRate * 100
            );
        };
    }

    private String makeComparisonText(int kreamAveragePrice, int ebayAveragePrice, int recommendedPrice) {
        if (kreamAveragePrice > 0) {
            return makePriceComparisonText("KREAM 평균가", kreamAveragePrice, recommendedPrice);
        }

        if (ebayAveragePrice > 0) {
            return makePriceComparisonText("eBay 평균가", ebayAveragePrice, recommendedPrice);
        }

        return "";
    }

    private String makePriceComparisonText(String sourceName, int averagePrice, int recommendedPrice) {
        if (averagePrice == 0) {
            return "";
        }

        double differenceRate = ((double) recommendedPrice - averagePrice) / averagePrice * 100;
        int roundedDifferenceRate = (int) Math.round(Math.abs(differenceRate));

        if (roundedDifferenceRate == 0) {
            return String.format(
                    "추천가는 %s와 거의 동일한 수준입니다.",
                    sourceName
            );
        }

        if (differenceRate > 0) {
            return String.format(
                    "추천가는 %s 대비 약 %d%% 높은 수준입니다.",
                    sourceName,
                    roundedDifferenceRate
            );
        }

        return String.format(
                "추천가는 %s 대비 약 %d%% 낮은 수준입니다.",
                sourceName,
                roundedDifferenceRate
        );
    }

    private List<CalculatePriceResponse.MatchedMarketPrice> toResponseMatches(List<MarketPriceRow> rows) {
    return rows.stream()
            .limit(5)
            .map(row -> new CalculatePriceResponse.MatchedMarketPrice(
                    row.source(),
                    row.brand(),
                    row.model(),
                    row.colorway(),
                    row.sizeKr(),
                    row.conditionGrade(),
                    toComponentStatus(row.boxIncluded()),
                    row.price(),
                    row.url()
            ))
            .toList();
    }

private String toComponentStatus(Boolean boxIncluded) {
    if (boxIncluded == null) {
        return "NONE";
    }

    return boxIncluded ? "FULL" : "NONE";
}

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        return normalize(a).equals(normalize(b));
    }

    private boolean containsBothWays(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        String normalizedA = normalize(a);
        String normalizedB = normalize(b);

        return normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA);
    }

    private String normalize(String value) {
        return value
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "");
    }
}