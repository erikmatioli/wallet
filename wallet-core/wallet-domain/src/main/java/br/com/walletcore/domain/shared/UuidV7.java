package br.com.walletcore.domain.shared;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUID (RFC 9562, version 7). Keeps B-tree inserts append-mostly, which matters
 * for the append-only ledger tables.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        long rand12 = ((rnd[0] & 0x0FL) << 8) | (rnd[1] & 0xFFL);
        long msb = (timestamp << 16) | 0x7000L | rand12;
        long r = ByteBuffer.wrap(rnd, 2, 8).getLong();
        long lsb = (r & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
