package com.vintic.backend.auction.domain;

public enum CannotBidReason {
    AUCTION_NOT_STARTED,
    AUCTION_CLOSED,
    SELLER_CANNOT_BID,
    PENALTY_RESTRICTED,
    ALREADY_HIGHEST_BIDDER
}
