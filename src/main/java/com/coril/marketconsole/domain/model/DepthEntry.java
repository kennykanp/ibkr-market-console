package com.coril.marketconsole.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record DepthEntry(
        int position,
        BigDecimal price,
        long size,
        String marketMaker,
        Instant updatedAt
) {
}
