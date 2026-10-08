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
package org.evosuite.classpath;

import org.evosuite.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * When running EvoSuite there are at least three different classpaths
 * to handle: (1) the one of the target project, (2) the one of EvoSuite
 * itself, and (3) the one of the starter of EvoSuite.
 * Note that (2) and (3) can indeed be different: eg if EvoSuite is
 * not run from command line, and rather run from Maven/Eclipse that
 * use their own classloaders.
 *
 * @author arcuri
 */
public class ClassPathHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClassPathHandler.class);

    private static final ClassPathHandler singleton = new ClassPathHandler();


    /**
     * The classpath of the project to test, including all
     * its dependencies.
     */
    private volatile String targetClassPath;

    /**
     * When we start the client, we need to know what is the classpath of EvoSuite.
     * Problem is, if EvoSuite was called from Eclipse/Maven, when the classpath will
     * not be part of the system classpath. And might not be feasible to query the
     * current classloader to get such info, as we cannot make any assumption on its implementation.
     * So, in those cases, we need to manually specify it
     */
    private volatile String evosuiteClassPath;


    private ClassPathHandler() {
    }

    public static ClassPathHandler getInstance() {
        return singleton;
    }

    public static void resetSingleton() {
        getInstance().targetClassPath = null;
        getInstance().evosuiteClassPath = null;
    }

    /**
     * Gets the EvoSuite classpath.
     *
     * @return the EvoSuite classpath
     */
    public String getEvoSuiteClassPath() {
        if (evosuiteClassPath == null) {
            evosuiteClassPath = System.getProperty("java.class.path");
        }

        return evosuiteClassPath;
    }

    /**
     * Replace current CP of EvoSuite with the given <code>elements</code>.
     *
     * @param elements array of classpath entries
     * @throws IllegalArgumentException if values in <code>elements</code> are not valid classpath entries
     */
    public synchronized void setEvoSuiteClassPath(String[] elements) throws IllegalArgumentException {
        String cp = getClassPath(elements);
        evosuiteClassPath = cp;
    }


    /**
     * Replace current CP for target project with the given <code>elements</code>.
     *
     * @param elements array of classpath entries
     * @throws IllegalArgumentException if values in <code>elements</code> are not valid classpath entries
     */
    public synchronized void changeTargetClassPath(String[] elements) throws IllegalArgumentException {
        String cp = getClassPath(elements);
        Properties.CP = cp;
        targetClassPath = cp;
    }

    private String getClassPath(String[] elements) {
        if (elements == null || elements.length == 0) {
            throw new IllegalArgumentException("No classpath elements");
        }

        StringBuilder cp = new StringBuilder();
        boolean first = true;
        for (String entry : elements) {
            checkIfValidClasspathEntry(entry);
            if (first) {
                first = false;
            } else {
                cp.append(File.pathSeparator);
            }
            cp.append(entry);
        }
        return cp.toString();
    }

    /**
     * Return the classpath of the target project.
     * This should include also all the third-party jars
     * it depends on.
     *
     * <p>If no classpath has been set so far, the one from the property file
     * will be used, if it exists.
     *
     * @return the target project classpath
     */
    public String getTargetProjectClasspath() {

        if (targetClassPath == null) {
            String line = null;
            if (Properties.CP_FILE_PATH != null) {
                File file = new File(Properties.CP_FILE_PATH);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(file), StandardCharsets.UTF_8))) {
                    line = reader.readLine();
                } catch (Exception e) {
                    logger.error("Error while processing {} : {}", file.getAbsolutePath(), e.getMessage());
                }
            }

            targetClassPath = orderLikeJvm(line != null ? line : Properties.CP);
        }

        return targetClassPath;
    }

    /**
     * Reorders a classpath the way a JVM or javac reads it: a jar's manifest {@code Class-Path}
     * entries are searched right after that jar, not where the entry sits in the plain list. When
     * two jars hold the same class (openhre ships xerces.jar and xercesImpl.jar, and xalan.jar's
     * manifest names xercesImpl.jar), the plain order and the JVM order pick different copies, so
     * EvoSuite would generate against one class while the compiled and executed test sees the
     * other. Only entries already on the classpath are moved; none is added or dropped, so the set
     * of visible classes is unchanged. The string is returned as is when nothing moves.
     */
    static String orderLikeJvm(String classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return classpath;
        }
        String[] entries = classpath.split(File.pathSeparator);
        Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            if (!entry.isEmpty()) {
                byKey.putIfAbsent(keyOf(entry), entry);
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String key : byKey.keySet()) {
            visitInJvmOrder(key, byKey, ordered);
        }
        List<String> result = new ArrayList<>();
        for (String key : ordered) {
            result.add(byKey.get(key));
        }
        List<String> original = new ArrayList<>(byKey.values());
        if (result.equals(original)) {
            return classpath;
        }
        logger.info("Ordered the project classpath by jar manifest Class-Path entries as the JVM does");
        return String.join(File.pathSeparator, result);
    }

    private static void visitInJvmOrder(String key, Map<String, String> onClasspath,
                                        Set<String> ordered) {
        if (!ordered.add(key)) {
            return;
        }
        File file = new File(key);
        if (!file.isFile() || !file.getName().endsWith(".jar")) {
            return;
        }
        try (JarFile jar = new JarFile(file)) {
            if (jar.getManifest() == null) {
                return;
            }
            String manifestPath = jar.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.CLASS_PATH);
            if (manifestPath == null) {
                return;
            }
            for (String reference : manifestPath.trim().split("\\s+")) {
                if (reference.isEmpty()) {
                    continue;
                }
                String referenced = keyOf(resolveManifestEntry(file, reference));
                if (onClasspath.containsKey(referenced)) {
                    visitInJvmOrder(referenced, onClasspath, ordered);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Cannot read the manifest of {}", key, e);
        }
    }

    private static String resolveManifestEntry(File jar, String reference) {
        try {
            java.net.URI uri = new java.net.URI(reference);
            if (uri.isAbsolute()) {
                return new File(uri).getPath();
            }
            return new File(jar.getAbsoluteFile().getParentFile(), uri.getPath()).getPath();
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return new File(jar.getAbsoluteFile().getParentFile(), reference).getPath();
        }
    }

    private static String keyOf(String entry) {
        File file = new File(entry).getAbsoluteFile();
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.toPath().normalize().toString();
        }
    }

    /**
     * Writes the given classpath to a temporary file.
     *
     * @param classpath the classpath string to write
     * @return the absolute path of the temporary file
     */
    public static String writeClasspathToFile(String classpath) {

        try {
            Path tempFile = Files.createTempFile("EvoSuite_classpathFile", ".txt");
            File file = tempFile.toFile();
            file.deleteOnExit();

            try (BufferedWriter out = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                out.write(classpath);
                out.newLine();
            }

            return file.getAbsolutePath();

        } catch (Exception e) {
            throw new IllegalStateException("Failed to create tmp file for classpath specification: " + e.getMessage());
        }
    }


    /**
     * Add classpath entry to the classpath of the target project.
     *
     * @param element the classpath element to add
     * @throws IllegalArgumentException if the element is invalid
     */
    public synchronized void addElementToTargetProjectClassPath(String element) throws IllegalArgumentException {
        checkIfValidClasspathEntry(element);

        getTargetProjectClasspath(); //need to be sure it is initialized
        if (targetClassPath == null || targetClassPath.isEmpty()) {
            targetClassPath = element;
        } else {

            if (targetClassPath.contains(element)) {
                return; //already there, nothing to add
            }

            targetClassPath += File.pathSeparator + element;
            Properties.CP = targetClassPath;
        }
    }

    private void checkIfValidClasspathEntry(String element) throws IllegalArgumentException {
        if (element == null || element.isEmpty()) {
            throw new IllegalArgumentException("Empty input element");
        }

        File file = new File(element);
        if (!file.exists()) {
            throw new IllegalArgumentException("Classpath element does not exist on disk at: " + element);
        }
        if (!element.endsWith(".jar") && !element.endsWith(".war") && !file.isDirectory()) {
            throw new IllegalArgumentException(
                    "A classpath element should either be a jar or a war or a folder: " + element);
        }
    }

    /**
     * Get the project classpath as an array of elements.
     *
     * @return a non-null array
     */
    public String[] getClassPathElementsForTargetProject() {
        String cp = getTargetProjectClasspath();
        if (cp == null) {
            return new String[0];
        }
        return cp.split(File.pathSeparator);
    }

    /**
     * Changes the target classpath to match the EvoSuite classpath.
     *
     * <p>This is meant only for running the EvoSuite test cases, whose CUTs will be in the
     * classpath of EvoSuite itself.</p>
     */
    public void changeTargetCPtoTheSameAsEvoSuite() {

        File outDir = new File("target" + File.separator + "classes");
        if (outDir.exists()) {
            changeTargetClassPath(new String[]{outDir.getAbsolutePath()});

            File testDir = new File("target" + File.separator + "test-classes");
            if (testDir.exists()) {
                addElementToTargetProjectClassPath(testDir.getAbsolutePath());
            }
        } else {
            //TODO: just in case... not sure it would work properly
            changeTargetClassPath(getEvoSuiteClassPath().split(File.pathSeparator));
        }
    }
}
