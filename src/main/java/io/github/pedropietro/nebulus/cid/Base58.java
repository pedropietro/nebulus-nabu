package io.github.pedropietro.nebulus.cid;

import java.math.BigInteger;
import java.util.Arrays;

/** Bitcoin-alphabet base58, used by CIDv0 ({@code Qm...}) and multibase prefix {@code z}. */
public final class Base58 {

    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {
    }

    public static String encode(byte[] data) {
        int zeros = 0;
        while (zeros < data.length && data[zeros] == 0) {
            zeros++;
        }
        BigInteger n = new BigInteger(1, data);
        StringBuilder sb = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(BASE);
            sb.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (int i = 0; i < zeros; i++) {
            sb.append('1');
        }
        return sb.reverse().toString();
    }

    public static byte[] decode(String text) {
        BigInteger n = BigInteger.ZERO;
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '1') {
            zeros++;
        }
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid base58 character: " + text.charAt(i));
            }
            n = n.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] raw = n.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        } else if (n.signum() == 0) {
            raw = new byte[0];
        }
        byte[] out = new byte[zeros + raw.length];
        System.arraycopy(raw, 0, out, zeros, raw.length);
        return out;
    }
}
