package com.vintic.backend.recommendation;

import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 벡터가 없는 상품의 벡터를 채운다. 기동 시 한 번 + 주기 배치.
//
// 상품 등록 시점에 벡터를 만들지만, 두 경우에 비어 있다: 그 기능(#49) 이전에 등록된 상품,
// 그리고 임베딩 호출이 실패한 상품(실패는 삼키고 null을 돌려주도록 설계했다 - ADR 3번).
// 벡터가 없으면 개인화 추천에서 순위를 못 받으므로 채워둔다.
//
// 기동 시 백필만으로는 부족해서 주기 배치를 함께 둔다. 백필은 기동할 때 한 번만 돌아,
// 그 뒤 임베딩 실패로 생긴 구멍은 다음 재기동까지 계속 남는다. 배치가 그 구멍을 메운다.
//
// 두 트리거 모두 기본은 꺼져 있고 배포 프로필(application-dev.yml)에서 켠다.
// 이 프로젝트의 MySqlIT 다수가 @ActiveProfiles("local")을 데이터소스 설정 모양만 빌려
// 쓰는데, 백그라운드 작업을 기본 활성화하면 그 테스트 컨텍스트에도 함께 떠서 실제로
// 다른 테스트를 건드린 사례가 있다(#57-2, application.yml의 payment.expiration 주석).
//
// 한 번에 배치 크기만큼만 처리한다. 한 건당 임베딩 호출이 하나씩(유료) 나가므로
// 벡터 없는 상품이 아무리 쌓여 있어도 비용 상한이 잡혀 있어야 하고, 기동 경로라면
// 기동 시간도 그만큼 늘어난다. 남은 것은 다음 실행이 이어서 채운다.
@Component
@Slf4j
public class ProductVectorBackfillRunner implements ApplicationRunner {

    private final ProductVectorRepository productVectorRepository;
    private final ProductVectorService productVectorService;
    private final boolean onStartup;
    private final boolean scheduled;
    private final int batchSize;

    public ProductVectorBackfillRunner(
            ProductVectorRepository productVectorRepository,
            ProductVectorService productVectorService,
            @Value("${recommendation.vector-backfill-on-startup:false}") boolean onStartup,
            @Value("${recommendation.vector-backfill-scheduled:false}") boolean scheduled,
            @Value("${recommendation.vector-backfill-batch-size:200}") int batchSize
    ) {
        this.productVectorRepository = productVectorRepository;
        this.productVectorService = productVectorService;
        this.onStartup = onStartup;
        this.scheduled = scheduled;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!onStartup) {
            return;
        }
        backfill("기동 시");
    }

    // 새벽 4시 30분. AI 호출 기록 정리(4시)와 같은 한산한 시간대를 쓰되 겹치지 않게 둔다.
    @Scheduled(cron = "${recommendation.vector-backfill-cron:0 30 4 * * *}")
    public void backfillPeriodically() {
        if (!scheduled) {
            return;
        }
        backfill("주기 배치");
    }

    private void backfill(String trigger) {
        try {
            List<Product> targets =
                    productVectorRepository.findProductsWithoutVector(PageRequest.of(0, batchSize));
            if (targets.isEmpty()) {
                // 채울 게 없는 게 정상 상태다. 매번 로그를 남기면 소음만 된다.
                return;
            }

            int embedded = productVectorService.refreshAll(targets);
            log.info("상품 벡터 백필({}) - 대상 {}건, 새로 임베딩 {}건", trigger, targets.size(), embedded);
        } catch (RuntimeException e) {
            // 백필이 실패해도 애플리케이션은 돌아야 한다. 추천이 해당 상품을 뒤로 미룰 뿐이다.
            log.warn("상품 벡터 백필({})에 실패했습니다. message={}", trigger, e.getMessage());
        }
    }
}
