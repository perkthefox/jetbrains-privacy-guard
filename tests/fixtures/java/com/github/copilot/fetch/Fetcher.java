package com.github.copilot.fetch;

public class Fetcher {
    private final FetchRequest request;
    private final FetchBodySubscriber bodySubscriber = new FetchBodySubscriber();

    public Fetcher(FetchRequest r) {
        request = r;
    }

    public java.util.concurrent.CompletableFuture<FetchResult> sendAsync() {
        fixture.Counters.network++;
        return java.util.concurrent.CompletableFuture.completedFuture(
                new FetchResult(200, java.util.Map.of()));
    }
}
