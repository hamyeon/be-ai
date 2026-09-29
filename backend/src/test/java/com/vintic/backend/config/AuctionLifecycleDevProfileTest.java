package com.vintic.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// dev profile(실제 배포 profile - backend/docker-compose.yml의 SPRING_PROFILES_ACTIVE=dev)에서
// 경매 시작/종료 스케줄러가 켜져 있는지 확인한다. base(application.yml)의 기본값은 테스트 컨텍스트
// 오염 방지를 위해 false다(payment.expiration과 동일한 이유) - 이 값이 dev.yml에서 실제로
// override되는지는 어떤 기존 테스트도 확인하지 않고 있었다. Spring Context를 띄우지 않고 YAML
// 값만 파싱하므로 DB/Docker 없이 항상 실행된다 - application-dev.yml의 이 설정이 바뀌면 즉시
// 회귀를 잡아낸다.
class AuctionLifecycleDevProfileTest {

    @Test
    void dev_profile에서_경매_시작_스케줄러가_활성화되어_있다() throws Exception {
        assertThat(devProperties().getProperty("auction.lifecycle.start.enabled")).isEqualTo("true");
    }

    @Test
    void dev_profile에서_경매_종료_스케줄러가_활성화되어_있다() throws Exception {
        assertThat(devProperties().getProperty("auction.lifecycle.end.enabled")).isEqualTo("true");
    }

    private Properties devProperties() throws Exception {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-dev.yml"));
        return factory.getObject();
    }
}
