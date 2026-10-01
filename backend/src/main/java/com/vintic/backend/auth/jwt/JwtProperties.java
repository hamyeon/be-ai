package com.vintic.backend.auth.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// application.yml의 jwt.* 값을 바인딩한다. secret은 여기 기본값을 두지 않는다 - JWT_SECRET
// 환경변수가 없으면 기동 자체가 실패해야 한다(운영 secret이 조용히 빈 값/기본값으로 대체되는
// 것을 막기 위함, VisionStageProperties/AnalysisStreamProperties와 동일한 바인딩 관례를 따른다).
//
// #75-4B: dev/prod에서만 이 빈을 만든다 - local/test는 JWT_SECRET을 설정하지 않으므로, 이
// profile 제한이 없으면 그 두 profile의 context 기동 자체가 placeholder 해석 실패로 막힌다.
@Component
@Profile({"dev", "prod"})
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private long accessTtlSeconds = 1800;
    private long refreshTtlSeconds = 1209600;
}
