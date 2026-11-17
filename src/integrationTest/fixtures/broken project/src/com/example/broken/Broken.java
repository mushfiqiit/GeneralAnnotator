package com.example.broken;

/** Fixture with one NullAway finding and one unrelated compilation error. */
public class Broken {

    static String name() {
        return null;
    }

    static int notAString() {
        int x = "not an int";
        return x;
    }
}
