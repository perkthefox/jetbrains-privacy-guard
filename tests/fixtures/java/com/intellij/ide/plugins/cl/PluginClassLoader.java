package com.intellij.ide.plugins.cl;

public class PluginClassLoader extends java.net.URLClassLoader {
    public PluginClassLoader(java.net.URL[] urls) {
        super(urls, ClassLoader.getPlatformClassLoader());
    }

    public static class FakeId {
        public String getIdString() {
            return "com.github.copilot";
        }
    }

    public FakeId getPluginId() {
        return new FakeId();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try (var in = findResource(name.replace('.', '/') + ".class").openStream()) {
            byte[] data = in.readAllBytes();
            return defineClass(name, data, 0, data.length);
        } catch (java.io.IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
