package com.vintic.backend.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // #75-4C: Kakao 사용자 중 일부는 이메일 동의를 하지 않았거나 계정 자체에 이메일이 없을 수
    // 있다 - NOT NULL을 유지하면 그런 사용자는 가입 자체가 막힌다. UNIQUE는 유지한다(MySQL은
    // NULL 여러 개를 UNIQUE 위반으로 보지 않으므로 이메일이 없는 사용자끼리는 서로 충돌하지
    // 않는다). identity로 쓰지 않는다 - kakaoUserId가 identity다.
    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private String nickname;

    private String profileImageUrl;

    // #75-4C: Kakao 로그인 identity. email/nickname/profileImageUrl은 profile data일 뿐이라
    // find-or-create 기준으로 쓰지 않는다 - 이 컬럼 하나만 UNIQUE 최종 방어선이다. 기존 mock/
    // 시연용 User(LocalUserSeeder 등 비-Kakao 경로)는 null을 허용한다 - 그런 User끼리는 전부
    // null이라 UNIQUE 제약과 충돌하지 않는다.
    @Column(name = "kakao_user_id", unique = true)
    private Long kakaoUserId;

    @Column(nullable = false)
    private int noshowCount;

    private LocalDateTime bidRestrictedUntil;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
    }

    public static User register(String email, String nickname, String profileImageUrl) {
        User user = new User();
        user.email = email;
        user.nickname = nickname;
        user.profileImageUrl = profileImageUrl;
        user.noshowCount = 0;
        user.createdAt = LocalDateTime.now();
        return user;
    }

    // #75-4C: Kakao 최초 로그인 시 신규 User를 만드는 유일한 진입점. register()를 그대로
    // 재사용하고 kakaoUserId만 추가로 채운다 - register()의 시그니처는 바꾸지 않는다(기존
    // 호출부/테스트 fixture 전부 영향 없음).
    public static User registerFromKakao(Long kakaoUserId, String email, String nickname, String profileImageUrl) {
        if (kakaoUserId == null) {
            throw new IllegalArgumentException("kakaoUserId는 필수입니다.");
        }
        User user = register(email, nickname, profileImageUrl);
        user.kakaoUserId = kakaoUserId;
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Long getKakaoUserId() {
        return kakaoUserId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public int getNoshowCount() {
        return noshowCount;
    }

    public LocalDateTime getBidRestrictedUntil() {
        return bidRestrictedUntil;
    }

    public boolean isBidRestricted(LocalDateTime now) {
        return bidRestrictedUntil != null && bidRestrictedUntil.isAfter(now);
    }

    // #57-2 사용자 확정 정책: noShowCount/bidRestrictedUntil은 PAYMENT_EXPIRED penalty에서만
    // 갱신한다 - FORFEITED는 penalties 이력에는 남지만(Penalty.forfeited()) 이 메서드를 호출하지
    // 않는다(계약/기존 정책에 FORFEITED가 입찰 제한을 유발한다는 확정 내용이 없어 임의로 확대하지
    // 않았다). restrictedUntil은 호출자(OrderExpirationService)가 BidRestrictionPolicy로
    // 미리 계산해 넘긴다 - 회차별 escalating 없이 고정 기간을 그대로 설정한다(현재 값을 덮어쓰는
    // 것으로 충분하다 - 매 호출의 now가 항상 이전보다 뒤이므로 결과적으로 항상 뒤로 연장된다).
    public void recordPaymentExpiredPenalty(LocalDateTime restrictedUntil) {
        this.noshowCount++;
        this.bidRestrictedUntil = restrictedUntil;
    }

    // 영속화 전(id == null) 상태에서는 서로 다른 인스턴스를 같은 사용자로 오판하면 안 되므로,
    // 참조 동일성을 우선 확인하고 그 다음에만 id를 비교한다.
    // id는 필드가 아니라 getId()로 읽는다 — Hibernate 지연 로딩 프록시(예: Auction.currentWinner)는
    // 식별자 필드 자체는 채워져 있지 않고 getId() 호출에서만 초기화 없이 정상적으로 id를 반환한다.
    public boolean isSameUser(User other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        Long thisId = getId();
        Long otherId = other.getId();
        return thisId != null && otherId != null && thisId.equals(otherId);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
