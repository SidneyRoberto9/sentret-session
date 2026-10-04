package io.github.sidneyroberto9.sentret.util;

import java.security.SecureRandom;

public final class NanoId {

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private NanoId() {}

    public static String generate(int size) {
        byte[] bytes = new byte[size];

        RANDOM.nextBytes(bytes);

        char[] out = new char[size];

        for (int i = 0; i < size; i++) {
            out[i] = ALPHABET[bytes[i] & 63];
        }

        return new String(out);
    }
}
