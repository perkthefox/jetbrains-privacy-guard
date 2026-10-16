package com.github.copilot.fetch;

public class FetchRequest {
    private final String url;

    public FetchRequest(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
