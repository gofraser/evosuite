package org.evosuite.coverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class BytecodeFixtureClassLoader extends ClassLoader {

    private final String javaVersion;

    public BytecodeFixtureClassLoader(String javaVersion) {
        // e.g., "8", "11", "17", "24"
        this.javaVersion = javaVersion;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // We only intercept loading of our test fixtures
        if (name.startsWith("bytecode_tests.")) {
            String resourceName = "/bytecode_tests/bin/java" + javaVersion + "/" + name.replace('.', '/') + ".class";
            try (InputStream is = getClass().getResourceAsStream(resourceName)) {
                if (is == null) {
                    throw new ClassNotFoundException("Could not find resource: " + resourceName);
                }
                byte[] classBytes = readAllBytes(is);
                return defineClass(name, classBytes, 0, classBytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("Error reading resource: " + resourceName, e);
            }
        }
        return super.findClass(name);
    }
    
    public byte[] getClassBytes(String name) throws IOException {
        String resourceName = "/bytecode_tests/bin/java" + javaVersion + "/" + name.replace('.', '/') + ".class";
        try (InputStream is = getClass().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IOException("Could not find resource: " + resourceName);
            }
            return readAllBytes(is);
        }
    }
    
    public Class<?> defineClassFromBytes(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
}
