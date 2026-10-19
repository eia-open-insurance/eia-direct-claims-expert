package com.eia.directclaims.expert;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal RFC 9562 UUIDv7 generator, used for aggregate identifiers as
 * required by SPEC §11.4.4.
 */
final class UuidV7 {
    private UuidV7() {
    }

    static UUID next() {
        long timestamp = System.currentTimeMillis();
        long randomA = ThreadLocalRandom.current().nextLong(1L << 12);
        long msb = (timestamp << 16) | 0x7000L | randomA;
        long lsb = ThreadLocalRandom.current().nextLong();
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
