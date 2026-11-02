package io.github.pedropietro.nebulus.cid;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 base32 without padding (lowercase on output), as used by multibase prefix {@code b}. */
public final class Base32 {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer >>> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }

    public static byte[] decode(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(end * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'a' && c <= 'z') {
                v = c - 'a';
            } else if (c >= '2' && c <= '7') {
                v = c - '2' + 26;
            } else {
                throw new IllegalArgumentException("Invalid base32 character: " + c);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
