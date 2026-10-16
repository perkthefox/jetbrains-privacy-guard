package com.intellij.util.io;

public class HttpRequests {
    public static void post(String url, String mime) {
        fixture.Counters.network++;
    }

    public static java.util.Properties request(String url) {
        fixture.Counters.network++;
        return new java.util.Properties();
    }
}
