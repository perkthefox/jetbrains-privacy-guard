package local.privacyguard;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.*;
import java.util.stream.Stream;

/** Local, offline instrumentation. No sockets, external services, or plugin execution. */
public final class PrivacyGuard {
    static final String VERSION = "1.1.0";
    static final String COP = "com/github/copilot/";
    static final String AIA = "com/intellij/ml/llm/";
    static final Set<String> BLOCKED = ConcurrentHashMap.newKeySet();
    static final Map<String, String> APPROVED_FILES = new ConcurrentHashMap<>();
    static final Map<String, Path> CANONICAL_PATHS = new ConcurrentHashMap<>();
    static final Map<ClassLoader, String> LOADER_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    static final Map<String, String> PLUGIN_IDS =
            Map.of(
                    "lermitage.intellij.extra.icons",
                    "extra-icons",
                    "lermitage.intellij.extratci",
                    "extra-tci",
                    "izhangzhihao.rainbow.brackets",
                    "rainbow",
                    "com.chrisrm.idea.MaterialThemeUI",
                    "material",
                    "com.github.copilot",
                    "copilot",
                    "org.jetbrains.junie",
                    "junie",
                    "com.intellij.ml.llm",
                    "ai-assistant");
    static final Map<String, Set<String>> REQUIRED =
            Map.of(
                    "extra-icons", Set.of("extra-detector", "extra-stats", "extra-config"),
                    "extra-tci", Set.of("extra-detector", "extra-stats", "extra-config"),
                    "rainbow", Set.of("rainbow-jvm", "rainbow-environment"),
                    "material", Set.of("material-analytics"),
                    "copilot",
                            Set.of(
                                    "copilot-telemetry",
                                    "copilot-ms",
                                    "copilot-native",
                                    "copilot-transport",
                                    "copilot-http-filter"),
                    "junie", Set.of("junie-analytics"),
                    "ai-assistant", Set.of("aia-sharing", "aia-analytics", "aia-export"));
    static Path logFile;
    static Path ideLib;
    static List<Path> roots = List.of();

    static String source(ProtectionDomain pd) {
        try {
            return Paths.get(pd.getCodeSource().getLocation().toURI()).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    static String owner(String source) {
        String s = source.replace('\\', '/');
        if (s.contains("/Extra Icons/")) return "extra-icons";
        if (s.contains("/Extra ToolWindow Colorful Icons/")) return "extra-tci";
        if (s.contains("/intellij-rainbow-brackets/")) return "rainbow";
        if (s.contains("/Material Theme UI/")) return "material";
        if (s.contains("/github-copilot-intellij/")) return "copilot";
        if (s.contains("/ej/")) return "junie";
        if (s.contains("/ml-llm/")) return "ai-assistant";
        return "";
    }

    static boolean pluginLoader(ClassLoader loader) {
        return loader != null
                && loader.getClass().getName().startsWith("com.intellij.ide.plugins.cl.");
    }

    static Path canonicalPath(Path path) {
        return CANONICAL_PATHS.computeIfAbsent(
                path.toString(),
                key -> {
                    try {
                        return path.toRealPath();
                    } catch (IOException ignored) {
                        return path.toAbsolutePath().normalize();
                    }
                });
    }

    static boolean pluginSource(String source) {
        if (source.isEmpty()) return false;
        try {
            Path path = canonicalPath(Path.of(source));
            return roots.stream().anyMatch(path::startsWith);
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    static String pluginId(ClassLoader loader) {
        if (!pluginLoader(loader)) return "";
        synchronized (LOADER_IDS) {
            String cached = LOADER_IDS.get(loader);
            if (cached != null) return cached;
            String id = "";
            try {
                Object value = loader.getClass().getMethod("getPluginId").invoke(loader);
                if (value != null)
                    id = (String) value.getClass().getMethod("getIdString").invoke(value);
            } catch (ReflectiveOperationException ignored) {
            }
            LOADER_IDS.put(loader, id);
            log("LOADER", loader.getClass().getName() + " plugin=" + id);
            return id;
        }
    }

    static String resourceSource(ClassLoader loader, String name, String fallback) {
        try {
            URL resource;
            if (pluginLoader(loader))
                resource =
                        (URL)
                                loader.getClass()
                                        .getMethod("findResource", String.class)
                                        .invoke(loader, name + ".class");
            else resource = loader == null ? null : loader.getResource(name + ".class");
            if (resource == null) return fallback;
            String value = resource.toExternalForm();
            if (value.startsWith("jar:")) value = value.substring(4, value.indexOf("!/"));
            URI uri = URI.create(value);
            if (uri.getScheme().equals("file")) return Path.of(uri).toString();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static boolean contains(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            if (data[i] != needle[0]) continue;
            for (int j = 1; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static synchronized void log(String event, String message) {
        String line = Instant.now() + "\t" + event + "\t" + message.replace('\n', ' ') + "\n";
        if (logFile == null) return;
        try {
            if (Files.exists(logFile) && Files.size(logFile) > 8 * 1024 * 1024)
                Files.move(logFile, Path.of(logFile + ".1"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println(
                    "[privacy-guard] Cannot write local log: " + ex.getClass().getSimpleName());
        }
    }

    public static void premain(String arg, Instrumentation instrumentation) {
        try {
            Properties config = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(arg))) {
                config.load(r);
            }
            logFile = Path.of(config.getProperty("log"));
            Files.createDirectories(logFile.getParent());
            String libraryPath = config.getProperty("ide.lib", "");
            if (!libraryPath.isBlank()) ideLib = Path.of(libraryPath);
            if (config.getProperty("plugin.roots", "").isBlank())
                throw new IllegalArgumentException(
                        "plugin.roots must contain at least one directory");
            roots =
                    Arrays.stream(config.getProperty("plugin.roots").split("\\|"))
                            .map(s -> canonicalPath(Path.of(s)))
                            .toList();
            // These are opt-outs, not substitutes for the bytecode rules below.
            System.setProperty("extra-tools-pack.data-sharing", "false");
            System.setProperty("llm.jcp.analytics.enabled", "false");
            System.setProperty("llm.chat.agent.event.telemetry.enabled", "false");
            System.setProperty("llm.opentelemetry.trace.enable", "false");
            System.setProperty("otel.sdk.disabled", "true");
            System.setProperty("jetbrains.privacy.guard.optional-http-blocked", "false");
            log("START", "version=" + VERSION + " pid=" + ProcessHandle.current().pid());
            preflight(roots, false, null);
            instrumentation.addTransformer(new GuardTransformer(), false);
            log("READY", "blocked=" + BLOCKED);
        } catch (Throwable error) {
            // Never silently launch with a broken guard installation.
            System.err.println("[privacy-guard] Initialization failed: " + error);
            throw new IllegalStateException("Privacy guard initialization failed", error);
        }
    }

    static final class GuardTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                Module module,
                ClassLoader loader,
                String name,
                Class<?> redefining,
                ProtectionDomain pd,
                byte[] bytes) {
            if (name == null || name.startsWith("local/privacyguard/")) return null;
            String src = source(pd), own = owner(src);
            if (own.isEmpty()) own = PLUGIN_IDS.getOrDefault(pluginId(loader), "");
            boolean fromPlugin = pluginLoader(loader) || pluginSource(src);
            if (!own.isEmpty() || fromPlugin && contains(bytes, "getInputArguments")) {
                src = resourceSource(loader, name, src);
                if (own.isEmpty()) own = owner(src);
            }
            if (src.isEmpty() && fromPlugin) src = "/plugins/unresolved/";
            if (!own.isEmpty() && !ensureApproved(src, own)) {
                log("BLOCKED_CLASS", own + " " + name + " unrecognized update");
                return new byte[] {0, 0, 0, 0};
            }
            if (BLOCKED.contains(own)) {
                log("BLOCKED_CLASS", own + " " + name);
                return new byte[] {0, 0, 0, 0};
            }
            if (own.isEmpty() && !fromPlugin) return null;
            if (!candidate(name, own, src, bytes)) return null;
            try {
                Result result = patch(bytes, own, src, loader);
                if (result == null) return null;
                log("PATCH", own + " " + name + " " + result.changes);
                return result.bytes;
            } catch (Throwable error) {
                log("BLOCKED_CLASS", own + " " + name + " reason=" + error);
                // Transformer exceptions are ignored by the JVM; invalid bytecode deliberately
                // prevents this identified sensitive class from executing on a failed patch.
                return new byte[] {0, 0, 0, 0};
            }
        }
    }

    static String stamp(Path path) throws IOException {
        var a = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        return a.size() + ":" + a.lastModifiedTime().toMillis();
    }

    static synchronized boolean ensureApproved(String source, String own) {
        try {
            Path jar = Path.of(source);
            if (!source.endsWith(".jar")) return !BLOCKED.contains(own);
            String actual = stamp(jar);
            if (actual.equals(APPROVED_FILES.get(source))) return !BLOCKED.contains(own);
            Path archive = canonicalPath(jar);
            Path root =
                    roots.stream()
                            .filter(archive::startsWith)
                            .max(Comparator.comparingInt(Path::getNameCount))
                            .orElseThrow(
                                    () ->
                                            new IOException(
                                                    "Archive is outside configured plugin roots"));
            Path relative = root.relativize(archive);
            if (relative.getNameCount() < 2)
                throw new IOException("Cannot identify plugin directory");
            Path plugin = root.resolve(relative.getName(0));
            BLOCKED.remove(own);
            Map<String, Set<String>> detected = preflight(List.of(plugin), false, null);
            if (!detected.getOrDefault(own, Set.of()).containsAll(REQUIRED.get(own))) {
                BLOCKED.add(own);
                APPROVED_FILES.put(source, actual);
                log("BLOCKED_PLUGIN", own + " update owner/schema was not recognized");
            }
            log("UPDATE_CHECK", own + " " + source + " blocked=" + BLOCKED.contains(own));
            return !BLOCKED.contains(own);
        } catch (Throwable error) {
            BLOCKED.add(own);
            log("BLOCKED_PLUGIN", own + " update validation: " + error);
            return false;
        }
    }

    static boolean candidate(String name, String own, String src, byte[] b) {
        // Runtime callers have already checked plugin scope; offline callers
        // supply only plugin archives. Do not depend on a folder named plugins.
        if (!src.isEmpty() && contains(b, "getInputArguments")) return true;
        return switch (own) {
            case "extra-icons", "extra-tci" ->
                    contains(b, "lambda$findCrackJar")
                            || contains(b, "lambda$sendIDEStats")
                            || contains(b, "lambda$initCrackClassPathDefinitions");
            case "rainbow" ->
                    contains(b, "getInputArguments")
                            || contains(b, "getResource")
                            || contains(b, "IdeaPluginDescriptor");
            case "material" ->
                    name.equals("com/mallowigi/idea/MTAnalytics")
                            || name.equals(
                                    "com/mallowigi/idea/config/application/newconfig/MTMetadataState");
            case "copilot" ->
                    name.startsWith(COP + "telemetry/")
                            || name.startsWith(COP + "mstelemetry/")
                            || name.equals(COP + "lang/agent/commands/TelemetrySettings")
                            || name.equals(COP + "lang/agent/commands/CopilotSettings")
                            || name.equals(COP + "fetch/Fetcher")
                            || name.equals(COP + "settings/CopilotApplicationState");
            case "junie" ->
                    name.equals(AIA + "matterhorn/core/analytics/ingrazzio/AnalyticsClientImpl");
            case "ai-assistant" ->
                    name.equals(AIA + "core/settings/LLMDataSharingManager")
                            || name.startsWith(AIA + "core/statistics/fus/jcp/JcpAnalytics")
                            || name.startsWith(
                                    AIA + "core/statistics/telemetry/AgentEventTelemetryCollector")
                            || name.equals(AIA + "telemetry/AIAssistantTelemetryExporterKt");
            default -> false;
        };
    }

    record Facts(List<InvokeInstruction> calls, boolean staticArrayWrite) {}

    record Rule(String category, String kind) {}

    record Result(byte[] bytes, List<String> changes, Set<String> categories) {}

    static Facts facts(MethodModel m) {
        List<InvokeInstruction> calls = new ArrayList<>();
        boolean arrayWrite = false;
        if (m.code().isPresent())
            for (CodeElement e : m.code().get()) {
                if (e instanceof InvokeInstruction i) calls.add(i);
                if (e instanceof FieldInstruction f
                        && f.opcode() == Opcode.PUTSTATIC
                        && f.typeSymbol().descriptorString().startsWith("[")) arrayWrite = true;
            }
        return new Facts(calls, arrayWrite);
    }

    static boolean calls(Facts f, String owner, String method) {
        return f.calls.stream()
                .anyMatch(
                        i ->
                                i.owner().asInternalName().equals(owner)
                                        && i.name().equalsString(method));
    }

    static boolean jvmArgs(InvokeInstruction i) {
        return i.owner().asInternalName().equals("java/lang/management/RuntimeMXBean")
                && i.name().equalsString("getInputArguments")
                && i.typeSymbol().descriptorString().equals("()Ljava/util/List;");
    }

    static Rule replacement(
            String own,
            String clazz,
            MethodModel m,
            Facts f,
            boolean detector,
            boolean stats,
            boolean remote) {
        String n = m.methodName().stringValue(), d = m.methodTypeSymbol().descriptorString();
        String ret = m.methodTypeSymbol().returnType().descriptorString();
        if (n.startsWith("<") || m.code().isEmpty()) return null;
        if (detector) {
            boolean helper = (m.flags().flagsMask() & 2) != 0 && Set.of("a", "b", "c").contains(n);
            if (!helper && !f.staticArrayWrite && !ret.startsWith("Ljava/lang/invoke/"))
                return new Rule("extra-detector", "default");
        }
        if (stats
                && (calls(f, "com/intellij/util/io/HttpRequests", "post")
                        || calls(f, "com/intellij/util/io/HttpRequests$Request", "write")))
            return new Rule("extra-stats", "default");
        if (remote
                && calls(f, "com/intellij/util/io/HttpRequests", "request")
                && ret.equals("Ljava/util/Properties;")) return new Rule("extra-config", "default");
        if (own.equals("rainbow")) {
            if (d.equals("(Lkotlin/Unit;)Z") && f.calls.stream().anyMatch(PrivacyGuard::jvmArgs))
                return new Rule("rainbow-jvm", "default");
            if (d.equals("(Lkotlin/Unit;)Z")
                    && f.calls.stream()
                            .anyMatch(
                                    i ->
                                            i.owner().asInternalName().equals("java/lang/Class")
                                                            && Set.of(
                                                                            "getResource",
                                                                            "getResourceAsStream")
                                                                    .contains(
                                                                            i.name().stringValue())
                                                    || i.owner()
                                                                    .asInternalName()
                                                                    .equals(
                                                                            "com/intellij/ide/plugins/PluginManagerCore")
                                                            && Set.of(
                                                                            "getPlugins",
                                                                            "getLoadedPlugins",
                                                                            "isPluginInstalled")
                                                                    .contains(
                                                                            i.name()
                                                                                    .stringValue())))
                return new Rule("rainbow-environment", "default");
            if (d.equals("(Lcom/intellij/ide/plugins/IdeaPluginDescriptor;)Z")
                    && calls(f, "com/intellij/ide/plugins/IdeaPluginDescriptor", "getPluginId"))
                return new Rule("rainbow-environment", "default");
        }
        if (clazz.equals("com/mallowigi/idea/MTAnalytics")
                && ret.equals("V")
                && (n.startsWith("track") || n.contains("initAnalytics") || n.equals("identify")))
            return new Rule("material-analytics", "default");
        if (clazz.equals("com/mallowigi/idea/config/application/newconfig/MTMetadataState")
                && n.equals("getAllowDataCollection"))
            return new Rule("material-analytics", "default");
        if (clazz.startsWith(COP + "telemetry/")) {
            if (n.equals("isTelemetryEnabled") && ret.equals("Z"))
                return new Rule("copilot-telemetry", "default");
            if (ret.equals("V")
                    && (n.equals("track")
                            || n.equals("post")
                            || n.startsWith("report")
                            || n.startsWith("lambda$report")))
                return new Rule("copilot-telemetry", "default");
        }
        if (clazz.equals(COP + "mstelemetry/MsTelemetryClient")
                && (n.equals("trackEvent") || n.equals("buildCommonProperties")))
            return new Rule("copilot-ms", "default");
        if (clazz.equals(COP + "mstelemetry/MsTelemetryClient$ClientHolder")
                && n.equals("createTelemetryLogger")) return new Rule("copilot-ms", "copilot-noop");
        if (clazz.equals(COP + "mstelemetry/MsTelemetryService")
                && ret.equals("Z")
                && Set.of("isTelemetryEnabled", "shouldSendTelemetry").contains(n))
            return new Rule("copilot-ms", "default");
        if (clazz.equals(AIA + "matterhorn/core/analytics/ingrazzio/AnalyticsClientImpl")
                && n.equals("logEvent")) return new Rule("junie-analytics", "default");
        if (clazz.equals(AIA + "core/settings/LLMDataSharingManager")
                && Set.of(
                                "isDataSharingEnabled",
                                "isLLMCDataSharingEnabled",
                                "getDataSharingPermission",
                                "shouldAskForTraceConsent",
                                "shouldShowConsentDialog")
                        .contains(n)) return new Rule("aia-sharing", "default");
        if (clazz.equals(AIA + "core/statistics/fus/jcp/JcpAnalyticsClientImpl")
                && Set.of("submitEvent", "analyticsEnabled_delegate$lambda$0").contains(n))
            return new Rule("aia-analytics", "default");
        if (clazz.equals(AIA + "core/statistics/fus/jcp/JcpAnalyticsSender") && n.equals("send"))
            return new Rule("aia-analytics", "boolean-true-object");
        if (clazz.startsWith(AIA + "core/statistics/telemetry/AgentEventTelemetryCollector")) {
            if (n.equals("isTelemetryEnabled")) return new Rule("aia-analytics", "default");
            if (Set.of("logAgentClientEvent", "logAgentTaskFinished", "report").contains(n)
                    && d.contains("Lkotlin/coroutines/Continuation;"))
                return new Rule("aia-analytics", "unit");
        }
        if (clazz.equals(AIA + "telemetry/AIAssistantTelemetryExporterKt")
                && Set.of(
                                "isOpenTelemetryEnabled",
                                "access$isOpenTelemetryEnabled",
                                "isExportToLangfuseEnabled")
                        .contains(n)) return new Rule("aia-export", "default");
        return null;
    }

    static ClassDesc cd(String internal) {
        return ClassDesc.ofDescriptor("L" + internal + ";");
    }

    static void stub(CodeBuilder b, MethodModel method, String clazz, String kind) {
        ClassDesc ret = method.methodTypeSymbol().returnType();
        String r = ret.descriptorString();
        if (kind.equals("copilot-noop")) {
            b.aload(0)
                    .invokevirtual(cd(clazz), "createNoopLogger", MethodTypeDesc.of(ret))
                    .areturn();
            return;
        }
        if (kind.equals("unit")) {
            b.getstatic(cd("kotlin/Unit"), "INSTANCE", cd("kotlin/Unit")).areturn();
            return;
        }
        if (kind.equals("boolean-true-object")) {
            b.getstatic(cd("java/lang/Boolean"), "TRUE", cd("java/lang/Boolean")).areturn();
            return;
        }
        switch (r) {
            case "V" -> b.return_();
            case "Z", "B", "C", "S", "I" -> b.iconst_0().ireturn();
            case "J" -> b.lconst_0().lreturn();
            case "F" -> b.fconst_0().freturn();
            case "D" -> b.dconst_0().dreturn();
            case "Ljava/lang/String;" -> b.ldc("").areturn();
            case "Ljava/lang/Boolean;" ->
                    b.getstatic(cd("java/lang/Boolean"), "FALSE", cd("java/lang/Boolean"))
                            .areturn();
            case "Ljava/util/List;", "Ljava/util/Collection;" ->
                    b.invokestatic(
                                    cd("java/util/Collections"),
                                    "emptyList",
                                    MethodTypeDesc.of(cd("java/util/List")))
                            .areturn();
            case "Ljava/util/Set;" ->
                    b.invokestatic(cd("java/util/Collections"), "emptySet", MethodTypeDesc.of(ret))
                            .areturn();
            case "Ljava/util/Map;" ->
                    b.invokestatic(cd("java/util/Collections"), "emptyMap", MethodTypeDesc.of(ret))
                            .areturn();
            case "Ljava/util/Optional;" ->
                    b.invokestatic(cd("java/util/Optional"), "empty", MethodTypeDesc.of(ret))
                            .areturn();
            case "Ljava/util/Properties;" ->
                    b.new_(ret)
                            .dup()
                            .invokespecial(ret, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                            .areturn();
            case "Ljava/lang/Object;" -> b.aconst_null().areturn();
            default ->
                    throw new IllegalArgumentException(
                            "Unrecognized replacement return type: " + r);
        }
    }

    static Result patch(byte[] bytes, String own, String source, ClassLoader loader) {
        ClassFile cf =
                ClassFile.of(
                        ClassFile.ClassHierarchyResolverOption.of(
                                ClassHierarchyResolver.ofResourceParsing(
                                        loader == null
                                                ? ClassLoader.getSystemClassLoader()
                                                : loader)));
        ClassModel model = cf.parse(bytes);
        String clazz = model.thisClass().asInternalName();
        if (clazz.equals(COP + "lang/agent/commands/CopilotSettings")) {
            String enumName = COP + "lang/agent/commands/FetchStrategy";
            ClassLoader resourceLoader =
                    loader == null ? ClassLoader.getSystemClassLoader() : loader;
            try (InputStream in = resourceLoader.getResourceAsStream(enumName + ".class")) {
                if (in == null
                        || cf.parse(in.readAllBytes()).fields().stream()
                                .noneMatch(
                                        f ->
                                                f.fieldName().equalsString("Client")
                                                        && f.fieldType()
                                                                .equalsString(
                                                                        "L" + enumName + ";")))
                    throw new IllegalStateException("Unrecognized Copilot Client transport enum");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (clazz.equals(COP + "fetch/Fetcher")
                && model.fields().stream()
                        .noneMatch(
                                f ->
                                        f.fieldName().equalsString("request")
                                                && f.fieldType()
                                                        .equalsString(
                                                                "L" + COP + "fetch/FetchRequest;")))
            throw new IllegalStateException("Unrecognized Copilot fetch request field");
        if (clazz.equals(COP + "fetch/Fetcher")) {
            if (model.fields().stream()
                    .noneMatch(
                            f ->
                                    f.fieldName().equalsString("bodySubscriber")
                                            && f.fieldType()
                                                    .equalsString(
                                                            "L"
                                                                    + COP
                                                                    + "fetch/FetchBodySubscriber;")))
                throw new IllegalStateException("Unrecognized Copilot body subscriber field");
            requireMethod(cf, loader, COP + "fetch/FetchBodySubscriber", "onComplete", "()V");
            requireMethod(cf, loader, COP + "fetch/FetchResult", "<init>", "(ILjava/util/Map;)V");
            requireMethod(cf, loader, COP + "fetch/FetchRequest", "getUrl", "()Ljava/lang/String;");
        }
        if (clazz.equals(COP + "lang/agent/commands/TelemetrySettings")) {
            String levelName = COP + "lang/agent/commands/TelemetrySettings$TelemetryLevel";
            ClassLoader resourceLoader =
                    loader == null ? ClassLoader.getSystemClassLoader() : loader;
            try (InputStream in = resourceLoader.getResourceAsStream(levelName + ".class")) {
                if (in == null
                        || cf.parse(in.readAllBytes()).fields().stream()
                                .noneMatch(
                                        f ->
                                                f.fieldName().equalsString("OFF")
                                                        && f.fieldType()
                                                                .equalsString(
                                                                        "L" + levelName + ";")))
                    throw new IllegalStateException("Unrecognized native telemetry OFF enum");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (clazz.equals(COP + "mstelemetry/MsTelemetryClient$ClientHolder")
                && model.methods().stream()
                        .noneMatch(
                                m ->
                                        m.methodName().equalsString("createNoopLogger")
                                                && m.methodTypeSymbol().parameterCount() == 0))
            throw new IllegalStateException("Unrecognized Microsoft telemetry no-op factory");
        boolean detector = (own.startsWith("extra-") && contains(bytes, "lambda$findCrackJar"));
        boolean stats = own.startsWith("extra-") && contains(bytes, "lambda$sendIDEStats");
        boolean remote =
                own.startsWith("extra-") && contains(bytes, "lambda$initCrackClassPathDefinitions");
        Map<MethodModel, Rule> replacements = new IdentityHashMap<>();
        Set<MethodModel> edits = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> changes = new ArrayList<>();
        Set<String> categories = new TreeSet<>();
        for (MethodModel m : model.methods()) {
            Facts f = facts(m);
            Rule rule = replacement(own, clazz, m, f, detector, stats, remote);
            if (rule != null) {
                replacements.put(m, rule);
                categories.add(rule.category);
                changes.add(rule.category + ":" + m.methodName() + m.methodType());
            } else if (m.code().isPresent()) {
                boolean edit = f.calls.stream().anyMatch(PrivacyGuard::jvmArgs);
                if (clazz.equals(COP + "lang/agent/commands/TelemetrySettings")
                        || clazz.equals(COP + "settings/CopilotApplicationState")
                        || clazz.equals(COP + "lang/agent/commands/CopilotSettings"))
                    for (CodeElement e : m.code().get())
                        if (e instanceof FieldInstruction field
                                && field.opcode() == Opcode.PUTFIELD
                                && Set.of("telemetryLevel", "enableTelemetry", "fetchStrategy")
                                        .contains(field.name().stringValue())) {
                            edit = true;
                            if (field.name().equalsString("fetchStrategy"))
                                categories.add("copilot-transport");
                        }
                if (clazz.equals(COP + "fetch/Fetcher")
                        && m.methodName().equalsString("sendAsync")) {
                    if (!m.methodType().equalsString("()Ljava/util/concurrent/CompletableFuture;"))
                        throw new IllegalStateException(
                                "Unrecognized Copilot asynchronous fetch signature");
                    edit = true;
                    categories.add("copilot-http-filter");
                }
                if (edit) {
                    edits.add(m);
                    categories.add(
                            clazz.startsWith(COP) ? "copilot-native" : "plugin-jvm-arguments");
                    changes.add("instructions:" + m.methodName() + m.methodType());
                }
            }
        }
        if (replacements.isEmpty() && edits.isEmpty()) return null;
        byte[] out =
                cf.transformClass(
                        model,
                        (cb, ce) -> {
                            if (ce instanceof MethodModel m && replacements.containsKey(m)) {
                                Rule rule = replacements.get(m);
                                cb.transformMethod(
                                        m,
                                        (mb, me) -> {
                                            if (me instanceof CodeModel)
                                                mb.withCode(b -> stub(b, m, clazz, rule.kind));
                                            else mb.with(me);
                                        });
                            } else if (ce instanceof MethodModel m && edits.contains(m)) {
                                if (clazz.equals(COP + "fetch/Fetcher")
                                        && m.methodName().equalsString("sendAsync")) {
                                    cb.transformMethod(
                                            m,
                                            (mb, me) -> {
                                                if (me instanceof CodeModel code)
                                                    mb.withCode(
                                                            b -> {
                                                                httpFilter(b);
                                                                for (CodeElement e : code)
                                                                    b.with(e);
                                                            });
                                                else mb.with(me);
                                            });
                                    return;
                                }
                                cb.transformMethod(
                                        m,
                                        MethodTransform.transformingCode(
                                                (b, e) -> {
                                                    if (e instanceof InvokeInstruction i
                                                            && jvmArgs(i)) {
                                                        b.pop()
                                                                .invokestatic(
                                                                        cd("java/util/Collections"),
                                                                        "emptyList",
                                                                        MethodTypeDesc.of(
                                                                                cd(
                                                                                        "java/util/List")));
                                                    } else if (e instanceof FieldInstruction f
                                                            && f.opcode() == Opcode.PUTFIELD
                                                            && clazz.equals(
                                                                    COP
                                                                            + "lang/agent/commands/TelemetrySettings")
                                                            && f.name()
                                                                    .equalsString(
                                                                            "telemetryLevel")) {
                                                        ClassDesc level =
                                                                cd(
                                                                        COP
                                                                                + "lang/agent/commands/TelemetrySettings$TelemetryLevel");
                                                        if (!f.typeSymbol().equals(level))
                                                            throw new IllegalStateException(
                                                                    "Native telemetry field type"
                                                                        + " changed");
                                                        b.pop()
                                                                .getstatic(level, "OFF", level)
                                                                .with(e);
                                                    } else if (e instanceof FieldInstruction f
                                                            && f.opcode() == Opcode.PUTFIELD
                                                            && clazz.equals(
                                                                    COP
                                                                            + "settings/CopilotApplicationState")
                                                            && f.name()
                                                                    .equalsString(
                                                                            "enableTelemetry")) {
                                                        b.pop().iconst_0().with(e);
                                                    } else if (e instanceof FieldInstruction f
                                                            && f.opcode() == Opcode.PUTFIELD
                                                            && (clazz.equals(
                                                                            COP
                                                                                    + "settings/CopilotApplicationState")
                                                                    || clazz.equals(
                                                                            COP
                                                                                    + "lang/agent/commands/CopilotSettings"))
                                                            && f.name()
                                                                    .equalsString(
                                                                            "fetchStrategy")) {
                                                        ClassDesc strategy =
                                                                cd(
                                                                        COP
                                                                                + "lang/agent/commands/FetchStrategy");
                                                        if (!f.typeSymbol().equals(strategy))
                                                            throw new IllegalStateException(
                                                                    "Copilot transport field type"
                                                                        + " changed");
                                                        b.pop()
                                                                .getstatic(
                                                                        strategy, "Client",
                                                                        strategy)
                                                                .with(e);
                                                    } else b.with(e);
                                                }));
                            } else cb.with(ce);
                        });
        return new Result(out, changes, categories);
    }

    static void httpFilter(CodeBuilder b) {
        ClassDesc str = ConstantDescs.CD_String, bool = ConstantDescs.CD_boolean;
        Label blocked = b.newLabel(), next = b.newLabel(), allowed = b.newLabel();
        b.aload(0)
                .getfield(cd(COP + "fetch/Fetcher"), "request", cd(COP + "fetch/FetchRequest"))
                .invokevirtual(cd(COP + "fetch/FetchRequest"), "getUrl", MethodTypeDesc.of(str))
                .invokestatic(
                        cd("java/net/URI"), "create", MethodTypeDesc.of(cd("java/net/URI"), str))
                .invokevirtual(cd("java/net/URI"), "getHost", MethodTypeDesc.of(str))
                .ldc("")
                .invokestatic(
                        cd("java/util/Objects"),
                        "toString",
                        MethodTypeDesc.of(str, ConstantDescs.CD_Object, str))
                .getstatic(cd("java/util/Locale"), "ROOT", cd("java/util/Locale"))
                .invokevirtual(str, "toLowerCase", MethodTypeDesc.of(str, cd("java/util/Locale")));
        b.dup()
                .ldc("telemetry.")
                .invokevirtual(str, "startsWith", MethodTypeDesc.of(bool, str))
                .ifeq(next);
        b.dup()
                .ldc(".githubcopilot.com")
                .invokevirtual(str, "endsWith", MethodTypeDesc.of(bool, str))
                .ifne(blocked);
        b.labelBinding(next);
        for (String host :
                List.of(
                        "copilot-telemetry.githubusercontent.com",
                        "copilot-telemetry.github.com",
                        "api.mixpanel.com",
                        "api-eu.mixpanel.com",
                        "dc.services.visualstudio.com"))
            b.dup()
                    .ldc(host)
                    .invokevirtual(str, "equals", MethodTypeDesc.of(bool, ConstantDescs.CD_Object))
                    .ifne(blocked);
        for (String suffix :
                List.of(
                        ".applicationinsights.azure.com",
                        ".applicationinsights.azure.cn",
                        ".ingest.sentry.io"))
            b.dup()
                    .ldc(suffix)
                    .invokevirtual(str, "endsWith", MethodTypeDesc.of(bool, str))
                    .ifne(blocked);
        b.pop().goto_(allowed);
        b.labelBinding(blocked)
                .pop()
                .ldc("jetbrains.privacy.guard.optional-http-blocked")
                .ldc("true")
                .invokestatic(
                        cd("java/lang/System"), "setProperty", MethodTypeDesc.of(str, str, str))
                .pop()
                .aload(0)
                .getfield(
                        cd(COP + "fetch/Fetcher"),
                        "bodySubscriber",
                        cd(COP + "fetch/FetchBodySubscriber"))
                .invokevirtual(
                        cd(COP + "fetch/FetchBodySubscriber"),
                        "onComplete",
                        MethodTypeDesc.of(ConstantDescs.CD_void))
                .new_(cd(COP + "fetch/FetchResult"))
                .dup()
                .ldc(403)
                .invokestatic(
                        cd("java/util/Collections"),
                        "emptyMap",
                        MethodTypeDesc.of(cd("java/util/Map")))
                .invokespecial(
                        cd(COP + "fetch/FetchResult"),
                        "<init>",
                        MethodTypeDesc.of(
                                ConstantDescs.CD_void, ConstantDescs.CD_int, cd("java/util/Map")))
                .invokestatic(
                        cd("java/util/concurrent/CompletableFuture"),
                        "completedFuture",
                        MethodTypeDesc.of(
                                cd("java/util/concurrent/CompletableFuture"),
                                ConstantDescs.CD_Object))
                .areturn();
        b.labelBinding(allowed);
    }

    static void requireMethod(
            ClassFile cf, ClassLoader loader, String name, String method, String descriptor) {
        ClassLoader l = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        try (InputStream in = l.getResourceAsStream(name + ".class")) {
            if (in == null
                    || cf.parse(in.readAllBytes()).methods().stream()
                            .noneMatch(
                                    m ->
                                            m.methodName().equalsString(method)
                                                    && m.methodType().equalsString(descriptor)))
                throw new IllegalStateException(
                        "Unrecognized HTTP bridge contract " + name + "." + method);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> jars(List<Path> roots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : roots)
            if (Files.isDirectory(root))
                try (Stream<Path> walk = Files.walk(root)) {
                    result.addAll(
                            walk.filter(
                                            p ->
                                                    p.toString().endsWith(".jar")
                                                            && Files.isRegularFile(p))
                                    .sorted()
                                    .toList());
                }
        return result;
    }

    static Map<String, Set<String>> preflight(List<Path> paths, boolean verify, Path output)
            throws Exception {
        Map<String, Set<String>> found = new TreeMap<>();
        Set<String> installed = new TreeSet<>();
        List<Path> archives = jars(paths);
        List<URL> urls = new ArrayList<>();
        for (Path jar : archives) urls.add(jar.toUri().toURL());
        if (ideLib != null && Files.isDirectory(ideLib))
            try (Stream<Path> s = Files.list(ideLib)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".jar")).toList())
                    urls.add(p.toUri().toURL());
            }
        long count = 0, changed = 0;
        List<String> report = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(
                        urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (Path jar : archives) {
                String own = owner(jar.toString());
                if (!own.isEmpty()) installed.add(own);
                if (!verify && own.isEmpty()) continue;
                try (JarFile z = new JarFile(jar.toFile(), false)) {
                    for (JarEntry entry : Collections.list(z.entries())) {
                        if (!entry.getName().endsWith(".class")) continue;
                        count++;
                        String name = entry.getName().substring(0, entry.getName().length() - 6);
                        // Only Extra/Rainbow have obfuscated class names; other families have
                        // stable explicit entry points. Avoid decompressing AI dependencies at
                        // boot.
                        if (!verify
                                && !own.startsWith("extra-")
                                && !own.equals("rainbow")
                                && !candidate(name, own, "", new byte[0])) continue;
                        byte[] original;
                        try (InputStream in = z.getInputStream(entry)) {
                            original = in.readAllBytes();
                        }
                        if (!candidate(name, own, jar.toString(), original)) continue;
                        Result result = patch(original, own, jar.toString(), loader);
                        if (result == null) continue;
                        if (verify) {
                            ClassFile verifier =
                                    ClassFile.of(
                                            ClassFile.ClassHierarchyResolverOption.of(
                                                    ClassHierarchyResolver.ofResourceParsing(
                                                            loader)));
                            var errors = verifier.verify(result.bytes);
                            if (!errors.isEmpty())
                                throw new IllegalStateException(jar + "!" + name + ": " + errors);
                        }
                        changed++;
                        found.computeIfAbsent(own, k -> new TreeSet<>()).addAll(result.categories);
                        report.add(jar + "\t" + name + "\t" + result.changes);
                    }
                } catch (Exception failure) {
                    if (verify || own.isEmpty()) throw failure;
                    BLOCKED.add(own);
                    log("BLOCKED_PLUGIN", own + " preflight: " + failure);
                }
                if (!own.isEmpty()) APPROVED_FILES.put(jar.toString(), stamp(jar));
            }
        }
        for (String own : installed) {
            Set<String> missing = new TreeSet<>(REQUIRED.get(own));
            missing.removeAll(found.getOrDefault(own, Set.of()));
            if (!missing.isEmpty()) {
                BLOCKED.add(own);
                log("BLOCKED_PLUGIN", own + " missing recognized rules=" + missing);
            }
        }
        log(
                "PREFLIGHT",
                "classes="
                        + count
                        + " transformed="
                        + changed
                        + " categories="
                        + found
                        + " blocked="
                        + BLOCKED);
        if (output != null) {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.write(output, report);
        }
        if (verify) {
            System.out.println(
                    "Verified transformed classes="
                            + changed
                            + "; scanned class entries="
                            + count
                            + "; blocked="
                            + BLOCKED);
            System.out.println("Categories=" + found);
            if (!BLOCKED.isEmpty())
                throw new IllegalStateException("Unrecognized plugin structure: " + BLOCKED);
        }
        return found;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !args[0].equals("--verify"))
            throw new IllegalArgumentException(
                    "--verify REPORT [--ide-lib DIRECTORY] PLUGIN_ROOT...");
        List<Path> paths = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--ide-lib")) {
                if (++i == args.length)
                    throw new IllegalArgumentException("Missing --ide-lib directory");
                ideLib = Path.of(args[i]);
            } else paths.add(canonicalPath(Path.of(args[i])));
        }
        if (paths.isEmpty())
            throw new IllegalArgumentException("At least one plugin root is required");
        roots = List.copyOf(paths);
        preflight(paths, true, Path.of(args[1]));
    }
}
