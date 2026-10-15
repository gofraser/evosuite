/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.continuous.persistency;

import org.evosuite.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fingerprints of the classes under test, recorded when tests were last generated for them, so that a
 * later run can tell which classes changed since.
 *
 * <p>A fingerprint covers what the tests of a class exercise: the bytecode of its whole compilation unit (the
 * class and its nested, local and anonymous classes, eg {@code Foo$1}), of its superclasses, and of the
 * interfaces it implements, which can have default methods (bar the JDK's classes); plus the EvoSuite build
 * that generated the tests, so that a new EvoSuite regenerates everything. Bytecode rather than source is used
 * so that the fingerprints do not depend on where the project is checked out and need no help from the build
 * tool. What a class merely calls is not covered: a change there is not detected.</p>
 *
 * <p>A fingerprint is only recorded once a class's tests have been generated, so a class that ran out of budget
 * or failed is still seen as changed in the next run.</p>
 */
public class ClassFingerprints {

    private static final Logger logger = LoggerFactory.getLogger(ClassFingerprints.class);

    static final String FILE_NAME = "class_fingerprints.properties";

    private final File file;

    private final String evoSuiteVersion;

    private final Map<String, String> recorded = new TreeMap<>();

    /**
     * Load the fingerprints recorded in the given folder.
     *
     * @param ctgFolder       the folder CTG keeps its data in
     * @param evoSuiteVersion identifier of the running EvoSuite build
     */
    public ClassFingerprints(File ctgFolder, String evoSuiteVersion) {
        this.file = new File(ctgFolder, FILE_NAME);
        this.evoSuiteVersion = evoSuiteVersion;
        if (file.exists()) {
            java.util.Properties stored = new java.util.Properties();
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                stored.load(in);
                for (String name : stored.stringPropertyNames()) {
                    recorded.put(name, stored.getProperty(name));
                }
            } catch (IOException e) {
                logger.warn("Cannot read " + file + ", every class counts as changed: " + e.getMessage());
            }
        }
    }

    /**
     * The fingerprints of the default CTG folder.
     *
     * @param evoSuiteVersion identifier of the running EvoSuite build
     * @return the fingerprints
     */
    public static ClassFingerprints load(String evoSuiteVersion) {
        return new ClassFingerprints(new File(Properties.CTG_DIR), evoSuiteVersion);
    }

    /**
     * Compute the current fingerprint of a class.
     *
     * @param theClass the class, loaded by the class loader of the project under test
     * @return the fingerprint, or {@code null} if its bytecode cannot be read
     */
    public String fingerprint(Class<?> theClass) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        digest.update(evoSuiteVersion.getBytes(StandardCharsets.UTF_8));

        if (readBytecode(theClass) == null) {
            return null;
        }
        for (Map.Entry<String, byte[]> unit : compilationUnit(theClass).entrySet()) {
            digest.update(unit.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update(unit.getValue());
        }

        Set<Class<?>> inherited = new LinkedHashSet<>();
        for (Class<?> c = theClass.getSuperclass(); isProjectOrLibrary(c); c = c.getSuperclass()) {
            inherited.add(c);
        }
        Deque<Class<?>> interfaces = new ArrayDeque<>();
        interfaces.add(theClass);
        interfaces.addAll(inherited);
        while (!interfaces.isEmpty()) {
            for (Class<?> i : interfaces.poll().getInterfaces()) {
                if (isProjectOrLibrary(i) && inherited.add(i)) {
                    interfaces.add(i);
                }
            }
        }
        for (Class<?> c : inherited) {
            byte[] bytes = readBytecode(c);
            if (bytes != null) {
                digest.update(c.getName().getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static boolean isProjectOrLibrary(Class<?> c) {
        return c != null && c.getClassLoader() != null;
    }

    /**
     * The bytecode of the top-level class a class belongs to and of all classes compiled from the same source
     * file ({@code Outer$*.class}), ordered by name. The nested classes can only be found when the class is
     * loaded from a folder, which is what CTG targets; from a jar only the class itself is read.
     */
    private static Map<String, byte[]> compilationUnit(Class<?> theClass) {
        Map<String, byte[]> unit = new TreeMap<>();
        Class<?> top = theClass;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        byte[] topBytes = readBytecode(top);
        if (topBytes != null) {
            unit.put(top.getName(), topBytes);
        }

        URL location = top.getClassLoader().getResource(top.getName().replace('.', '/') + ".class");
        if (location == null || !"file".equals(location.getProtocol())) {
            unit.put(theClass.getName(), readBytecode(theClass));
            return unit;
        }
        try {
            File folder = new File(location.toURI()).getParentFile();
            String simpleName = top.getName().substring(top.getName().lastIndexOf('.') + 1);
            File[] nested = folder.listFiles((dir, name) -> name.startsWith(simpleName + "$")
                    && name.endsWith(".class"));
            if (nested != null) {
                String packagePrefix = top.getName().substring(0, top.getName().length() - simpleName.length());
                for (File file : nested) {
                    String name = file.getName();
                    unit.put(packagePrefix + name.substring(0, name.length() - ".class".length()),
                            Files.readAllBytes(file.toPath()));
                }
            }
        } catch (URISyntaxException | IOException e) {
            logger.debug("Cannot read the nested classes of " + top.getName() + ": " + e.getMessage());
            unit.put(theClass.getName(), readBytecode(theClass));
        }
        return unit;
    }

    private static byte[] readBytecode(Class<?> c) {
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            logger.debug("Cannot read bytecode of " + c.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether a class differs from when its tests were last generated.
     *
     * @param className   the class
     * @param fingerprint its current fingerprint, possibly {@code null}
     * @return true if it changed, is new, or its fingerprint is unknown
     */
    public boolean hasChanged(String className, String fingerprint) {
        return fingerprint == null || !fingerprint.equals(recorded.get(className));
    }

    /**
     * Record the fingerprint a class had when its tests were generated.
     *
     * @param className   the class
     * @param fingerprint its fingerprint; {@code null} forgets the class
     */
    public void record(String className, String fingerprint) {
        if (fingerprint == null) {
            recorded.remove(className);
        } else {
            recorded.put(className, fingerprint);
        }
    }

    /**
     * Forget the classes that are no longer part of the project.
     *
     * @param currentClasses the classes of the project
     */
    public void retainOnly(Collection<String> currentClasses) {
        recorded.keySet().retainAll(currentClasses);
    }

    /**
     * Write the fingerprints back, replacing the file atomically where the file system allows it.
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        File tmp = new File(parent, FILE_NAME + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : recorded.entrySet()) {
                out.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
