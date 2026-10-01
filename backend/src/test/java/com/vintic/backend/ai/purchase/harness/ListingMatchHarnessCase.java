package com.vintic.backend.ai.purchase.harness;

import com.vintic.backend.ai.purchase.match.AuctionListing;
import com.vintic.backend.ai.purchase.match.MatchGoal;

// Matcher 하네스 케이스 하나. goal × listing → expected.matched.
record ListingMatchHarnessCase(
        String id,
        Goal goal,
        Listing listing,
        Expected expected,
        String note
) {

    record Goal(String modelKey, String modelQuery, String brand, String freeTextConditions) {
        MatchGoal toMatchGoal() {
            return new MatchGoal(modelKey, modelQuery, brand, freeTextConditions);
        }
    }

    record Listing(String brand, String model, String colorway, String title, String description) {
        AuctionListing toAuctionListing(long auctionId) {
            return new AuctionListing(auctionId, brand, model, colorway, title, description);
        }
    }

    record Expected(boolean matched) {
    }
}
