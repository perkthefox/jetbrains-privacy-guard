package com.github.copilot.fetch;

public record FetchResult(int status, java.util.Map<String, String> headers) {}
