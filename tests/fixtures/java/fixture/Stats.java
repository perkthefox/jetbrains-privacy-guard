package fixture;

public class Stats {
    public static void send() {
        com.intellij.util.io.HttpRequests.post("https://unused.invalid", "json");
    }

    private static void lambda$sendIDEStats$0() {}
}
