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

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String nickname;

    private String profileImageUrl;

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

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
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
