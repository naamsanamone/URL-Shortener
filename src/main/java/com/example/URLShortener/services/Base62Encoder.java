package com.example.URLShortener.services;

public class Base62Encoder {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62Encoder() {
        // utility
    }

    public static String encode(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        int current = value;
        while (current > 0) {
            int remainder = current % BASE;
            sb.append(ALPHABET.charAt(remainder));
            current /= BASE;
        }
        return sb.reverse().toString();
    }
}
