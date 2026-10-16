package fixture;

public class Detector {
    public static boolean scan() {
        Counters.inspection++;
        System.getProperty("java.class.path");
        return true;
    }

    private static boolean lambda$findCrackJar$0() {
        return true;
    }
}
