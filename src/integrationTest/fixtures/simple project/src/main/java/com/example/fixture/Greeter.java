package com.example.fixture;

/** Fixture with exactly two known NullAway findings (lines 12 and 16). */
public class Greeter {

    @Nullable
    static String find(String key) {
        return key.isEmpty() ? null : key;
    }

    static int lengthOf(String key) {
        return find(key).length();
    }

    static String name() {
        return null;
    }

    static int safe(String key) {
        String s = find(key);
        return s == null ? 0 : s.length();
    }
}
