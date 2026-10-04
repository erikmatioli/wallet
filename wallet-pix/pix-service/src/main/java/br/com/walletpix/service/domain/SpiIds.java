package br.com.walletpix.service.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Identifier formats defined by the SPI.
 * <ul>
 *   <li>EndToEndId (32): {@code E} + ISPB of the payer's PSP (8) + {@code yyyyMMddHHmm} in UTC (12)
 *       + 11 alphanumerics. Identifies one Pix end to end, across every message about it.</li>
 *   <li>Message id (32): {@code M} + ISPB of the sender (8) + 23 alphanumerics. Identifies one
 *       message; it is what duplicate detection keys on.</li>
 * </ul>
 */
public final class SpiIds {

    private static final Pattern END_TO_END = Pattern.compile("E\\d{8}\\d{12}[A-Za-z0-9]{11}");
    private static final Pattern ISPB = Pattern.compile("\\d{8}");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SpiIds() {
    }

    public static String newEndToEndId(String payerIspb, Instant now) {
        return "E" + requireIspb(payerIspb) + MINUTE.format(now) + random(11);
    }

    public static String newMessageId(String senderIspb) {
        return "M" + requireIspb(senderIspb) + random(23);
    }

    public static boolean isEndToEndId(String value) {
        return value != null && END_TO_END.matcher(value).matches();
    }

    private static String requireIspb(String ispb) {
        if (ispb == null || !ISPB.matcher(ispb).matches()) {
            throw new IllegalArgumentException("ISPB must have 8 digits: " + ispb);
        }
        return ispb;
    }

    private static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)];
        }
        return new String(out);
    }
}
