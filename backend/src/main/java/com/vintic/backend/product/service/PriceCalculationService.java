package com.vintic.backend.product.service;

import com.vintic.backend.product.dto.CalculatePriceRequest;
import com.vintic.backend.product.dto.CalculatePriceResponse;
import com.vintic.backend.product.service.MarketPriceDataLoader.MarketPriceRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class PriceCalculationService {

    // eBay는 기준 시세에서 뺐다(#89 실측). 해외 호가라 국내 실거래보다 중앙값 기준
    // 2.5배 높고, 당근 실거래 예측 오차가 KREAM 단독 25% vs 혼합(0.7/0.3) 38% vs
    // eBay 단독 155%였다. 섞을수록 나빠지는 것을 측정으로 확인했다.
    // 응답에는 계속 실어 참고 정보로만 보여준다.
    private static final double PRICE_RANGE_RATE = 0.05;
    private static final String UNKNOWN_CONDITION_GRADE = "UNKNOWN";
    // 상태 불문 전체 매물의 계수. 중고 시세 경로에서 상태 비율의 분모(기준선)가 된다.
    private static final String ALL_CONDITION_GRADE = "ALL";
    private static final String UNKNOWN_COMPONENT_STATUS = "UNKNOWN";

    private final MarketPriceDataLoader marketPriceDataLoader;
    private final ConditionRateProvider conditionRateProvider;
    private final UsedMarketPriceProvider usedMarketPriceProvider;
    private final ColorPremiumProvider colorPremiumProvider;
    private final ComponentRateProvider componentRateProvider;

    public PriceCalculationService(
            MarketPriceDataLoader marketPriceDataLoader,
            ConditionRateProvider conditionRateProvider,
            UsedMarketPriceProvider usedMarketPriceProvider,
            ColorPremiumProvider colorPremiumProvider,
            ComponentRateProvider componentRateProvider
    ) {
        this.marketPriceDataLoader = marketPriceDataLoader;
        this.conditionRateProvider = conditionRateProvider;
        this.usedMarketPriceProvider = usedMarketPriceProvider;
        this.colorPremiumProvider = colorPremiumProvider;
        this.componentRateProvider = componentRateProvider;
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

        // 중고 시세에 없는 모델은 그 자체가 커버리지 구멍이다. 어떤 모델을 확대해야
        // 하는지 감이 아니라 실요청 빈도로 정하기 위해 기록한다(#89).
        // 메트릭 태그가 아니라 로그인 이유: 모델명은 값의 종류가 무한해서 태그로 쓰면
        // 카디널리티가 터진다(#51에서 정한 규칙).
        log.info("중고 시세 미보유(2순위 폴백): brand={}, model={}", request.brand(), request.modelName());

        List<MarketPriceRow> kreamRows = marketPriceDataLoader.loadKreamRows();
        List<MarketPriceRow> ebayRows = marketPriceDataLoader.loadEbayRows();

        List<MarketPriceRow> kreamMatches = findMatches(kreamRows, request);
        List<MarketPriceRow> ebayMatches = findMatches(ebayRows, request);

        int kreamAveragePrice = calculateAveragePrice(kreamMatches);
        int ebayAveragePrice = calculateAveragePrice(ebayMatches);

        // KREAM이 없으면 가격을 만들지 않는다. eBay만으로 추천가를 내면 155% 오차의
        // 숫자를 판매자에게 주게 된다 - 틀린 값보다 "없음"이 낫다. (현재 데이터에서
        // eBay 전용 모델은 없어서 실제로는 기존과 같은 요청만 이 분기에 온다)
        if (kreamAveragePrice == 0) {
            // 어떤 근거로도 가격을 못 준 요청. 커버리지 확대 우선순위의 1급 근거다(#89).
            log.info("시세 정보 없음 응답: brand={}, model={}, color={}, size={}",
                    request.brand(), request.modelName(), request.color(), request.size());
            String noDataReason = ebayAveragePrice > 0
                    ? "국내 시세(중고 실거래·KREAM)를 찾지 못했습니다. eBay 해외 매물은 확인되지만 국내 실거래와 차이가 커 추천가 근거로 쓰지 않았으며, 참고로만 표시합니다."
                    : "입력한 브랜드, 모델명, 색상, 사이즈와 일치하는 시세 데이터를 찾지 못했습니다. 추천 가격 산정을 위해서는 유사 거래 데이터가 추가로 필요합니다.";
            return new CalculatePriceResponse(
                    0,
                    0,
                    0,
                    ebayAveragePrice,
                    0,
                    0,
                    "시세 정보 없음",
                    noDataReason,
                    List.of(),
                    toResponseMatches(ebayMatches)
            );
        }

        int baseMarketPrice = kreamAveragePrice;

        String normalizedConditionGrade = normalizeConditionGrade(request.conditionGrade());
        ConditionRateProvider.ConditionRate rate =
                conditionRateProvider.resolve(request.modelName(), normalizedConditionGrade);
        double conditionRate = rate.rate();
        // KREAM 기준가는 새제품이라 기준 모집단이 풀박스다. 실측 계수는 "구성품 불문 전체
        // 대비" 값이므로 그대로 곱하면 모든 매물을 풀박스로 계산하게 된다 - FULL 대비로 환산한다.
        ComponentRateProvider.ComponentRate componentRate =
                componentRateProvider.resolveAgainstFull(request.componentStatus());

        int calculatedPrice =
                (int) Math.round(baseMarketPrice * conditionRate * componentRate.rate());
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

        // 같은 색상 계열의 시세가 서 있으면 그것이 더 좁은 근거다 (#93).
        // 색상 표기는 계열로 정규화해 비교하므로 "그레이"/"gray"/"회색"이 같은 버킷에 붙는다.
        // 버킷이 없으면(표본 10건 미만이거나 색상 미판독) 모델 시세로 폴백 -
        // 색상 시세는 통계적으로 설 때만 쓰고, 정확도가 나빠지는 경로는 없다.
        Optional<UsedMarketPriceProvider.ColorPrice> colorPrice =
                usedMarketPriceProvider.findColor(request.brand(), request.modelName(), request.color());
        // 당근 색상 버킷이 없으면 KREAM 색상 프리미엄이 중간 폴백이다:
        // 당근 모델 시세 x (KREAM에서 이 색이 모델 평균 대비 몇 배인가).
        Optional<ColorPremiumProvider.ColorPremium> colorPremium = colorPrice.isPresent()
                ? Optional.empty()
                : colorPremiumProvider.find(request.brand(), request.modelName(), request.color());
        double premiumRate = colorPremium
                .map(ColorPremiumProvider.ColorPremium::premium)
                .orElse(1.0);

        int baseMedian = colorPrice.map(UsedMarketPriceProvider.ColorPrice::medianPrice)
                .orElse((int) Math.round(market.medianPrice() * premiumRate));
        int baseQ1 = colorPrice.map(UsedMarketPriceProvider.ColorPrice::q1Price)
                .orElse((int) Math.round(market.q1Price() * premiumRate));
        int baseQ3 = colorPrice.map(UsedMarketPriceProvider.ColorPrice::q3Price)
                .orElse((int) Math.round(market.q3Price() * premiumRate));

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

        // 구성품도 상태와 같은 이유로 "전체 매물 대비 비율"이다(#104).
        //
        // 시세 중앙값이 구성품을 가리지 않고 뽑은 값이라(build_used_market_prices.py는 박스를
        // 보지 않는다) 여기 곱할 계수의 분모도 같은 모집단이어야 한다. 절대 계수(FULL=1.00)를
        // 쓰면 풀박스 매물이 "박스 없는 매물까지 섞인 중앙값"을 그대로 받아 저평가된다.
        // 상태 계수에서 #86이 고친 것과 같은 함정이었다.
        //
        // 구성품을 모르면 전체 매물 중앙값을 그대로 쓴다. 미상 계수를 적용하면 "판매자가
        // 안 적음"과 "Vision이 못 읽음"을 같은 신호로 취급하게 된다 - 상태 UNKNOWN과 같은 판단이다.
        // 이 분기가 PARTIAL과 미상을 갈라준다(그 전까지 둘 다 0.97이라 계산이 같았다).
        String normalizedComponentStatus = normalizeComponentStatus(request.componentStatus());
        ComponentRateProvider.ComponentRate componentRate =
                componentRateProvider.resolve(normalizedComponentStatus);
        double componentRatio = UNKNOWN_COMPONENT_STATUS.equals(normalizedComponentStatus)
                ? 1.0
                : componentRate.rate();

        int recommendedPrice =
                roundToNearestThousand((int) Math.round(baseMedian * conditionRatio * componentRatio));
        int minRecommendedPrice =
                roundToNearestThousand((int) Math.round(baseQ1 * conditionRatio * componentRatio));
        int maxRecommendedPrice =
                roundToNearestThousand((int) Math.round(baseQ3 * conditionRatio * componentRatio));
        String priceRange = makePriceRange(minRecommendedPrice, maxRecommendedPrice);

        // UNKNOWN은 비율을 1.0으로 고정하므로 계수 출처를 밝힐 것이 없다.
        String rateBasisText = UNKNOWN_CONDITION_GRADE.equals(normalizedConditionGrade)
                ? ""
                : makeRateBasisText(gradeRate);

        // 어떤 표본을 근거로 했는지 - 색상 버킷 / KREAM 프리미엄 보정 / 모델 전체 순으로 밝힌다
        String sourceText;
        if (colorPrice.isPresent()) {
            sourceText = String.format(
                    "당근마켓·후르츠패밀리에 올라온 %s 중고 매물 중 같은 색상 계열(%s) %d건을 근거로 계산했습니다.",
                    market.modelDisplay(), colorPrice.get().colorFamily(), colorPrice.get().listingCount());
        } else if (colorPremium.isPresent()) {
            ColorPremiumProvider.ColorPremium premium = colorPremium.get();
            sourceText = String.format(
                    "당근마켓·후르츠패밀리에 올라온 %s 중고 매물 %d건을 기준으로 하되, "
                            + "KREAM 체결 %d건에서 이 색상 계열(%s)이 모델 평균 대비 %+.0f%% 수준인 것을 반영했습니다.",
                    market.modelDisplay(), market.listingCount(),
                    premium.tradeCount(), premium.colorFamily(), (premium.premium() - 1.0) * 100);
        } else {
            sourceText = String.format(
                    "당근마켓·후르츠패밀리에 올라온 %s 중고 매물 %d건을 근거로 계산했습니다.",
                    market.modelDisplay(), market.listingCount());
        }

        String reason = String.format(
                "%s 실거래가 중앙값은 %,d원이고, 매물의 절반이 %,d원 ~ %,d원 사이에 있습니다. "
                        + "상품 상태 %s(%s)는 전체 매물 시세 대비 %.0f%% 수준으로 반영했습니다.%s %s "
                        + "이를 바탕으로 최종 추천가는 %,d원이며, 판매 권장 범위는 실거래 분포를 따라 %s입니다.",
                sourceText,
                baseMedian,
                baseQ1,
                baseQ3,
                normalizedConditionGrade,
                getConditionDescription(normalizedConditionGrade),
                conditionRatio * 100,
                rateBasisText,
                // 실제로 곱한 값(미상이면 1.0)을 문구에도 그대로 쓴다. 계수 출처는 그대로 넘긴다.
                makeComponentText(
                        request.componentStatus(),
                        new ComponentRateProvider.ComponentRate(
                                componentRatio, componentRate.basis(), componentRate.sampleSize())),
                recommendedPrice,
                priceRange
        );

        // KREAM/eBay 필드는 이 경로에서 쓰이지 않았음을 그대로 드러낸다(0 / 빈 목록).
        // 어느 근거로 계산했는지는 reason이 밝힌다.
        return new CalculatePriceResponse(
                recommendedPrice,
                baseMedian,
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

    private String normalizeConditionGrade(String conditionGrade) {
        if (conditionGrade == null || conditionGrade.isBlank()) {
            return UNKNOWN_CONDITION_GRADE;
        }

        return conditionGrade.trim().toUpperCase();
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
            ComponentRateProvider.ComponentRate componentRate,
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
        // 기준 시세는 KREAM 단독이다(#89 실측 - 클래스 상단 주석 참고).
        String kreamText = String.format(
                "KREAM 새제품 유사 거래 %d건의 평균가 %,d원을 기준 시세로 사용했습니다.",
                kreamCount,
                kreamAveragePrice
        );

        if (ebayAveragePrice > 0) {
            return kreamText + String.format(
                    " eBay 해외 매물 %d건(평균 %,d원)은 국내 실거래와 차이가 커 참고로만 표시합니다.",
                    ebayCount,
                    ebayAveragePrice
            );
        }

        return kreamText;
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

    private String makeComponentText(
            String componentStatus, ComponentRateProvider.ComponentRate componentRate) {
        String normalizedStatus = normalizeComponentStatus(componentStatus);
        double percent = componentRate.rate() * 100;
        // 미상은 보정 없이 전체 매물 시세를 그대로 쓰므로 계수 출처를 밝힐 것이 없다.
        String basis = UNKNOWN_COMPONENT_STATUS.equals(normalizedStatus)
                ? ""
                : makeComponentBasisText(componentRate);

        return switch (normalizedStatus) {
            case "FULL" -> String.format(
                    "구성품이 모두 포함되어 있어 %.0f%% 반영률을 적용했습니다.%s", percent, basis);
            case "PARTIAL" -> String.format(
                    "구성품이 일부 포함되어 있어 %.0f%% 반영률을 적용했습니다.%s", percent, basis);
            case "NONE" -> String.format(
                    "구성품이 없어 %.0f%% 반영률을 적용했습니다.%s", percent, basis);
            default -> String.format(
                    "구성품 상태를 명확히 판단하기 어려워 %.0f%% 반영률을 적용했습니다.", percent);
        };
    }

    // 구성품 반영률이 실측에서 나온 값인지 밝힌다. 상태 계수의 makeRateBasisText와 같은 이유다 -
    // 실측 계수와 기본값이 구분되지 않으면 사용자는 둘을 같은 신뢰도로 받아들인다.
    private String makeComponentBasisText(ComponentRateProvider.ComponentRate componentRate) {
        return switch (componentRate.basis()) {
            case MEASURED -> String.format(
                    " (당근마켓 실거래 %d건으로 산출한 값입니다)", componentRate.sampleSize());
            case DEFAULT -> " (실거래 표본이 부족해 기본값을 사용했습니다)";
        };
    }

    private String normalizeComponentStatus(String componentStatus) {
        if (componentStatus == null || componentStatus.isBlank()) {
            return UNKNOWN_COMPONENT_STATUS;
        }
        String normalized = componentStatus.trim().toUpperCase();
        return switch (normalized) {
            case "FULL", "PARTIAL", "NONE" -> normalized;
            // 정의되지 않은 값은 미상으로 본다. 임의 문자열이 와도 계산은 돌아야 한다.
            default -> UNKNOWN_COMPONENT_STATUS;
        };
    }

    private String makeComparisonText(int kreamAveragePrice, int ebayAveragePrice, int recommendedPrice) {
        // 이 경로에서 KREAM은 항상 있다(없으면 위에서 "시세 정보 없음"으로 끝난다)
        return makePriceComparisonText("KREAM 평균가", kreamAveragePrice, recommendedPrice);
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