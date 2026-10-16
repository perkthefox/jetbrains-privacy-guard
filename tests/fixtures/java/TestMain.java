import com.github.copilot.lang.agent.commands.TelemetrySettings;

import fixture.*;

public class TestMain {
    public static void main(String[] a) throws Exception {
        if (java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().isEmpty())
            throw new AssertionError("application scope unexpectedly hidden");
        if (!Feature.arguments().isEmpty()) throw new AssertionError("plugin sees JVM arguments");
        if (Feature.sum(3, 4) != 7
                || !Feature.license("valid-fixture")
                || Feature.license("invalid"))
            throw new AssertionError("functional/license regression");
        if (Detector.scan()) throw new AssertionError("inspection executed");
        Stats.send();
        RemoteConfig.load();
        new com.github.copilot.telemetry.GitHubPanicTelemetryService()
                .track("test", java.util.Map.of());
        new com.github.copilot.mstelemetry.MsTelemetryClient().trackEvent("test");
        if (new TelemetrySettings(TelemetrySettings.TelemetryLevel.ALL).telemetryLevel()
                != TelemetrySettings.TelemetryLevel.OFF)
            throw new AssertionError("native telemetry not off");
        if (new com.github.copilot.settings.CopilotApplicationState().enableTelemetry)
            throw new AssertionError("default telemetry not off");
        if (Counters.inspection != 0 || Counters.network != 0)
            throw new AssertionError("side effects occurred");
        System.out.println(
                "PASS functional behavior, fixture license check, JVM redaction, telemetry sinks,"
                    + " native telemetry OFF");
        if (new com.github.copilot.lang.agent.commands.CopilotSettings(
                                com.github.copilot.lang.agent.commands.FetchStrategy.Native)
                        .fetchStrategy()
                != com.github.copilot.lang.agent.commands.FetchStrategy.Client)
            throw new AssertionError("native transport can bypass filter");
        for (String url :
                new String[] {
                    "https://telemetry.individual.githubcopilot.com/telemetry",
                    "https://centralus-2.in.applicationinsights.azure.com/v2/track",
                    "HTTPS://Telemetry.Business.Githubcopilot.com/telemetry"
                }) {
            if (new com.github.copilot.fetch.Fetcher(new com.github.copilot.fetch.FetchRequest(url))
                            .sendAsync()
                            .join()
                            .status()
                    != 403) throw new AssertionError("telemetry URL reached transport");
        }
        if (Counters.network != 0) throw new AssertionError("blocked request had side effects");
        if (Counters.streamsEnded != 3)
            throw new AssertionError("blocked response streams not completed");
        if (!"true".equals(System.getProperty("jetbrains.privacy.guard.optional-http-blocked")))
            throw new AssertionError("block signal not recorded");
        if (new com.github.copilot.fetch.Fetcher(
                                new com.github.copilot.fetch.FetchRequest(
                                        "https://api.githubcopilot.com/chat/completions"))
                        .sendAsync()
                        .join()
                        .status()
                != 200) throw new AssertionError("functional route blocked");
        if (Counters.network != 1) throw new AssertionError("functional route not executed");
        System.out.println(
                "PASS optional HTTP endpoints blocked, functional AI endpoint preserved, client"
                    + " transport enforced");
        try (var loader =
                new com.intellij.ide.plugins.cl.PluginClassLoader(
                        new java.net.URL[] {java.nio.file.Path.of(a[2]).toUri().toURL()})) {
            Class<?> type =
                    Class.forName(
                            "com.github.copilot.lang.agent.commands.TelemetrySettings",
                            true,
                            loader);
            Class<?> level =
                    Class.forName(
                            "com.github.copilot.lang.agent.commands.TelemetrySettings$TelemetryLevel",
                            true,
                            loader);
            Object original = level.getField("ALL").get(null);
            Object settings = type.getConstructor(level).newInstance(original);
            if (!type.getMethod("telemetryLevel").invoke(settings).toString().equals("OFF"))
                throw new AssertionError("null-CodeSource plugin loader escaped guard");
            System.out.println("PASS IntelliJ-style null-CodeSource plugin classloader");
        }
        java.nio.file.Files.move(
                java.nio.file.Path.of(a[0]),
                java.nio.file.Path.of(a[1]),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        try (var loader =
                new java.net.URLClassLoader(
                        new java.net.URL[] {java.nio.file.Path.of(a[1]).toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            try {
                Class.forName("fixture.UnknownPluginFeature", true, loader);
                throw new AssertionError("unknown update was allowed");
            } catch (ClassFormatError expected) {
                System.out.println("PASS unrecognized hot update blocked before execution");
            }
        }
    }
}
