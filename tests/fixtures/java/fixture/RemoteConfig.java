package fixture;

public class RemoteConfig {
    public static java.util.Properties load() {
        return com.intellij.util.io.HttpRequests.request("https://unused.invalid");
    }

    private static boolean lambda$initCrackClassPathDefinitions$0() {
        return true;
    }
}
