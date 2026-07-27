package com.vintic.backend.curation;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.curation.dto.CurationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/curations")
public class CurationController {

    private final CurationService curationService;

    public CurationController(CurationService curationService) {
        this.curationService = curationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CurationResponse>>> getCurations() {
        return ResponseEntity.ok(ApiResponse.success(curationService.getCurations()));
    }
}
