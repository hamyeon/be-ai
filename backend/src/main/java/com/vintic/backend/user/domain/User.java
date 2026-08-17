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
