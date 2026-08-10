package com.vintic.backend.autobid.repository;

import com.vintic.backend.autobid.domain.AutoBidSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoBidSettingRepository extends JpaRepository<AutoBidSetting, Long> {
}
