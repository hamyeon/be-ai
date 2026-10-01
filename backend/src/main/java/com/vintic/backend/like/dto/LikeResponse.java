package com.vintic.backend.like.dto;

public record LikeResponse(
        boolean liked,
        int likeCount
) {
}
