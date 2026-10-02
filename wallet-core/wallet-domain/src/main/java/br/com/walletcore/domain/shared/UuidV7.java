package br.com.walletcore.domain.shared;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUID (RFC 9562, version 7). Keeps B-tree inserts append-mostly, which matters
 * for the append-only ledger tables - and, since {@link Account#id()} is one, is what lets the
 * accounts directory paginate by "id < cursor" instead of needing a separate sequence column
 * (see AccountRepository.findAccountDirectory).
 *
 * <p><b>Monotonic within the millisecond.</b> {@code System.currentTimeMillis()} only has
 * millisecond resolution, so two calls in quick succession (same request handling burst, a fast
 * loop, or simply an unlucky pair of concurrent requests) can land on the same timestamp. A naive
 * implementation would then pick fresh random bits each time, making the two UUIDs compare in an
 * essentially random order despite being generated microseconds apart - which silently breaks
 * anything that orders by id "as if" it were a creation-time sequence. Per RFC 9562's guidance,
 * when the clock does not advance (or moves backwards), this reuses the last timestamp and
 * increments the random payload as a 74-bit counter instead of drawing new randomness, so output
 * from this method is always strictly increasing, regardless of how fast it is called.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RAND_A_MASK = 0x0FFFL;         // 12 bits, lives in the low bits of msb
    private static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL; // 62 bits, lives in the low bits of lsb

    private static long lastTimestamp = -1L;
    private static long lastRandA = 0L;
    private static long lastRandB = 0L;

    private UuidV7() {
    }

    public static synchronized UUID next() {
        long now = System.currentTimeMillis();
        if (now > lastTimestamp) {
            lastTimestamp = now;
            byte[] rnd = new byte[10];
            RANDOM.nextBytes(rnd);
            lastRandA = ((rnd[0] & 0x0FL) << 8) | (rnd[1] & 0xFFL);
            lastRandB = ByteBuffer.wrap(rnd, 2, 8).getLong() & RAND_B_MASK;
        } else {
            // Clock didn't advance (or went backwards): keep the timestamp and bump the random
            // payload so this UUID still sorts strictly after the previous one.
            lastRandB = (lastRandB + 1) & RAND_B_MASK;
            if (lastRandB == 0L) { // 62-bit counter wrapped - astronomically unlikely; carry up
                lastRandA = (lastRandA + 1) & RAND_A_MASK;
            }
        }
        long msb = (lastTimestamp << 16) | 0x7000L | lastRandA;
        long lsb = lastRandB | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
