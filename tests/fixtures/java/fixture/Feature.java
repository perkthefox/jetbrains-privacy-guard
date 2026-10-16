package fixture;

public class Feature {
    public static int sum(int x, int y) {
        return x + y;
    }

    public static boolean license(String key) {
        return "valid-fixture".equals(key);
    }

    public static java.util.List<String> arguments() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
}
