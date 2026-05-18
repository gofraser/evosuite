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
package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.classpath.ResourceList;
import org.evosuite.runtime.instrumentation.RuntimeInstrumentation;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.utils.MasterClassLoaderBridge;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;


/**
 * <em>Note:</em> Do not inadvertently use multiple instances of this class in
 * the application! This may lead to hard to detect and debug errors. Yet this
 * class cannot be an singleton as it might be necessary to do so...
 *
 * @author roessler
 * @author Gordon Fraser
 */
public class InstrumentingClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(InstrumentingClassLoader.class);
    private static final Pattern BINARY_CLASS_NAME_PATTERN =
            Pattern.compile("^[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*$");

    private final BytecodeInstrumentation instrumentation;
    private final Map<String, Class<?>> classes = new ConcurrentHashMap<>();

    /**
     * <p>
     * Constructor for InstrumentingClassLoader.
     * </p>
     */
    public InstrumentingClassLoader() {
        this(new BytecodeInstrumentation(), getDefaultParentClassLoader());
        setClassAssertionStatus(Properties.TARGET_CLASS, true);
        logger.debug("STANDARD classloader running now");
    }

    public InstrumentingClassLoader(ClassLoader parent) {
        this(new BytecodeInstrumentation(), parent);
        setClassAssertionStatus(Properties.TARGET_CLASS, true);
        logger.debug("STANDARD classloader running now");
    }

    /**
     * <p>
     * Constructor for InstrumentingClassLoader.
     * </p>
     *
     * @param instrumentation a {@link org.evosuite.instrumentation.BytecodeInstrumentation}
     *                        object.
     */
    public InstrumentingClassLoader(BytecodeInstrumentation instrumentation) {
        this(instrumentation, getDefaultParentClassLoader());
    }

    public InstrumentingClassLoader(BytecodeInstrumentation instrumentation, ClassLoader parent) {
        super(parent);
        this.instrumentation = instrumentation;
    }

    /**
     * Returns a view of the instrumented classes.
     *
     * @return a {@link java.util.List} object.
     */
    public List<String> getViewOfInstrumentedClasses() {
        return new ArrayList<>(classes.keySet());
    }


    /**
     * Load a class from a file.
     *
     * @param fullyQualifiedTargetClass the name of the class.
     * @param fileName the name of the file.
     * @return the class object.
     * @throws ClassNotFoundException if the class cannot be found.
     */
    public Class<?> loadClassFromFile(String fullyQualifiedTargetClass, String fileName) throws ClassNotFoundException {

        String className = fullyQualifiedTargetClass.replace('.', '/');

        try (InputStream is = new FileInputStream(new File(fileName))) {

            byte[] byteBuffer = getTransformedBytes(className, is);
            Class<?> result = defineInstrumentedClass(fullyQualifiedTargetClass, byteBuffer);

            logger.info("Loaded class " + fullyQualifiedTargetClass + " directly from " + fileName);
            return result;
        } catch (Throwable t) {
            logger.error("Error while loading class " + fullyQualifiedTargetClass + " : " + t.getMessage(), t);
            throw new ClassNotFoundException(t.getMessage(), t);
        }
    }

    /**
     * Loads the class with the specified binary name.
     * <p>
     * This method checks if the class should be instrumented. If so, it delegates to {@link #instrumentClass(String)}.
     * Otherwise, it delegates to the parent classloader.
     * </p>
     *
     * @param name The binary name of the class
     * @return The resulting Class object
     * @throws ClassNotFoundException If the class was not found
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if ("<evosuite>".equals(name)) {
                throw new ClassNotFoundException();
            }

            // In the master process we only resolve classes for RMI deserialization.
            // Never instrument there, as that can create duplicate class identities.
            if (MasterClassLoaderBridge.isMasterProcess()) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                ClassLoader masterLoader = MasterClassLoaderBridge.getMasterClassLoaderIfInitialized();
                if (masterLoader != null) {
                    return masterLoader.loadClass(name);
                }
                // During CLIENT_ON_THREAD execution the master marker may be set
                // before the dedicated master classloader is initialized. Fall
                // back to normal non-instrumenting resolution for core/JDK and
                // already-visible classes.
                return loadWithoutInstrumentation(name);
            }

            if (!shouldAttemptInstrumentation(name)) {
                Class<?> result = findLoadedClass(name);
                if (result != null) {
                    return result;
                }
                return loadWithoutInstrumentation(name);
            }

            Class<?> result = classes.get(name);
            if (result != null) {
                return result;
            } else {
                logger.info("Seeing class for first time: " + name);
                return instrumentClass(name);
            }
        }
    }

    private boolean shouldAttemptInstrumentation(String name) {
        if (!RuntimeInstrumentation.checkIfCanInstrument(name)) {
            return false;
        }

        // For unqualified simple names (e.g., "Object"), only instrument if they
        // really exist on the SUT classpath (default-package class).
        if (!name.contains(".")) {
            try {
                ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
                return ResourceList.getInstance(sutLoader).hasClass(name);
            } catch (Throwable ignored) {
                // Preserve previous behavior if classpath metadata is temporarily unavailable.
                return true;
            }
        }

        return true;
    }

    private Class<?> loadWithoutInstrumentation(String name) throws ClassNotFoundException {
        // Delegate to parent first.
        try {
            // Parent was passed as InstrumentingClassLoader.class.getClassLoader()
            ClassLoader parent = getParent();
            if (parent != null) {
                return parent.loadClass(name);
            }
            return ClassLoader.getSystemClassLoader().loadClass(name);
        } catch (ClassNotFoundException e) {
            // During (de-)serialization and assertion generation, project
            // dependencies may only be visible from the current thread context
            // class loader.
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null && context != this && context != getParent()) {
                return context.loadClass(name);
            }
            throw e;
        }
    }

    private static ClassLoader getDefaultParentClassLoader() {
        ClassLoader masterLoader = MasterClassLoaderBridge.getMasterClassLoaderIfInitialized();
        return masterLoader != null ? masterLoader : InstrumentingClassLoader.class.getClassLoader();
    }

    @Override
    public URL getResource(String name) {
        for (String candidate : resourceNameCandidates(name)) {
            URL resource = getResourceFromTargetClasspath(candidate);
            if (resource != null) {
                return resource;
            }
            resource = super.getResource(candidate);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> resources = new LinkedHashSet<>();

        for (String candidate : resourceNameCandidates(name)) {
            resources.addAll(getResourcesFromTargetClasspath(candidate));
            addEnumeration(resources, super.getResources(candidate));
        }

        return Collections.enumeration(resources);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        URL resource = getResource(name);
        if (resource == null) {
            return null;
        }
        try {
            return resource.openStream();
        } catch (IOException e) {
            return null;
        }
    }

    //This is needed, as it is overridden in subclasses
    protected byte[] getTransformedBytes(String className, InputStream is) throws IOException {
        return instrumentation.transformBytes(this, className, new ClassReader(is));
    }

    /**
     * Instrument the class with the given name.
     * <p>
     * This method loads the class bytes from the SUT classloader, instruments them
     * using {@link BytecodeInstrumentation}, and defines the class in this classloader.
     * </p>
     *
     * @param fullyQualifiedTargetClass the name of the class to instrument
     * @return the instrumented class
     * @throws ClassNotFoundException if the class cannot be found in the SUT classloader
     */
    private Class<?> instrumentClass(String fullyQualifiedTargetClass) throws ClassNotFoundException {
        String className = fullyQualifiedTargetClass.replace('.', '/');

        try (InputStream is = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getClassAsStream(fullyQualifiedTargetClass)) {
            if (is == null) {
                throw new ClassNotFoundException("Class '" + className + ".class"
                        + "' should be in target project, but could not be found!");
            }

            byte[] byteBuffer = getTransformedBytes(className, is);
            Class<?> result = defineInstrumentedClass(fullyQualifiedTargetClass, byteBuffer);

            logger.info("Loaded class: " + fullyQualifiedTargetClass);
            return result;
        } catch (Throwable t) {
            // We catch Throwable here because instrumentation or class definition might fail with
            // LinkageError or other unexpected errors, which we want to wrap as ClassNotFoundException
            // to conform to the ClassLoader contract and logging.
            if (isMissingClassError(t)) {
                if (shouldWarnForMissingClass(fullyQualifiedTargetClass)) {
                    AtMostOnceLogger.warn(logger, "Error while loading class (one-time): " + t.getMessage());
                } else {
                    AtMostOnceLogger.debug(logger, "Error while loading class (one-time): " + t.getMessage());
                }
                logger.debug("Full stack trace while loading class {}", fullyQualifiedTargetClass, t);
            } else {
                logger.error("Error while loading class: " + t.getMessage(), t);
            }
            throw new ClassNotFoundException(t.getMessage(), t);
        }
    }

    private static boolean shouldWarnForMissingClass(String className) {
        String normalized = normalizeClassName(className);
        if (normalized == null || !BINARY_CLASS_NAME_PATTERN.matcher(normalized).matches()) {
            return false;
        }

        String targetClass = Properties.TARGET_CLASS;
        if (targetClass != null && !targetClass.isEmpty()) {
            if (normalized.equals(targetClass) || normalized.startsWith(targetClass + "$")) {
                return true;
            }

            int lastDot = targetClass.lastIndexOf('.');
            if (lastDot > 0) {
                String targetPackage = targetClass.substring(0, lastDot);
                if (normalized.startsWith(targetPackage + ".")) {
                    return true;
                }
            }
        }

        try {
            ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            return ResourceList.getInstance(sutLoader).hasClass(normalized);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalizeClassName(String className) {
        if (className == null) {
            return null;
        }
        String normalized = className.replace('/', '.');
        return normalized.endsWith(".class") ? normalized.substring(0, normalized.length() - 6) : normalized;
    }

    private static boolean isMissingClassError(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ClassNotFoundException || current instanceof NoClassDefFoundError) {
                return true;
            }
            // LinkageError includes loader constraint violations that occur when a class
            // dependency was already loaded by a different classloader. This is a normal
            // classloading issue in multi-classloader setups, not a fatal error.
            if (current instanceof LinkageError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && (message.startsWith("Class not found: ")
                    || message.startsWith("Class not found "))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private URL getResourceFromTargetClasspath(String name) {
        List<URL> resources = getResourcesFromTargetClasspath(name);
        return resources.isEmpty() ? null : resources.get(0);
    }

    private List<URL> getResourcesFromTargetClasspath(String name) {
        String targetClasspath;
        try {
            targetClasspath = ClassPathHandler.getInstance().getTargetProjectClasspath();
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }

        if (targetClasspath == null || targetClasspath.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<URL> resources = new LinkedHashSet<>();
        for (String entry : targetClasspath.split(File.pathSeparator)) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            File cpEntry = new File(entry);
            if (!cpEntry.exists()) {
                continue;
            }

            if (cpEntry.isDirectory()) {
                File resourceFile = new File(cpEntry, name);
                if (resourceFile.isFile()) {
                    try {
                        resources.add(resourceFile.toURI().toURL());
                    } catch (Exception ignored) {
                        // Best-effort fallback.
                    }
                }
                continue;
            }

            String lower = cpEntry.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar") || lower.endsWith(".war")) {
                try (JarFile jarFile = new JarFile(cpEntry)) {
                    JarEntry jarEntry = jarFile.getJarEntry(name);
                    if (jarEntry != null) {
                        resources.add(new URL("jar:" + cpEntry.toURI().toURL() + "!/" + name));
                    }
                } catch (Exception ignored) {
                    // Best-effort fallback.
                }
            }
        }
        return new ArrayList<>(resources);
    }

    private static void addEnumeration(Set<URL> sink, Enumeration<URL> enumeration) {
        while (enumeration != null && enumeration.hasMoreElements()) {
            sink.add(enumeration.nextElement());
        }
    }

    private static List<String> resourceNameCandidates(String name) {
        if (name == null || name.isEmpty()) {
            return Collections.emptyList();
        }
        if (name.charAt(0) == '/') {
            String normalized = name.substring(1);
            if (normalized.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.singletonList(normalized);
        }
        return Collections.singletonList(name);
    }

    private Class<?> defineInstrumentedClass(String fullyQualifiedTargetClass, byte[] byteBuffer) {
        // Check if the class was already defined (e.g., during recursive class loading
        // where the first definition succeeded but an exception occurred later)
        Class<?> alreadyLoaded = findLoadedClass(fullyQualifiedTargetClass);
        if (alreadyLoaded != null) {
            classes.put(fullyQualifiedTargetClass, alreadyLoaded);
            return alreadyLoaded;
        }

        createPackageDefinition(fullyQualifiedTargetClass);
        String classResourceName = fullyQualifiedTargetClass.replace('.', '/') + ".class";
        URL sourceUrl = getResourceFromTargetClasspath(classResourceName);
        ProtectionDomain protectionDomain = createProtectionDomain(sourceUrl);

        Class<?> result = protectionDomain == null
                ? defineClass(fullyQualifiedTargetClass, byteBuffer, 0, byteBuffer.length)
                : defineClass(fullyQualifiedTargetClass, byteBuffer, 0, byteBuffer.length, protectionDomain);
        classes.put(fullyQualifiedTargetClass, result);
        return result;
    }

    private ProtectionDomain createProtectionDomain(URL sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        try {
            CodeSource codeSource = new CodeSource(sourceUrl, (Certificate[]) null);
            return new ProtectionDomain(codeSource, null, this, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Before a new class is defined, we need to create a package definition for it.
     *
     * @param className a {@link java.lang.String} object.
     */
    private void createPackageDefinition(String className) {
        int i = className.lastIndexOf('.');
        if (i != -1) {
            String pkgname = className.substring(0, i);
            // Check if package already loaded.
            if (getPackage(pkgname) == null) {
                try {
                    definePackage(pkgname, null, null, null, null, null, null, null);
                    logger.info("Defined package (3): " + getPackage(pkgname) + ", " + getPackage(pkgname).hashCode());
                } catch (IllegalArgumentException e) {
                    // Ignore if already defined by another thread
                }
            }
        }
    }

    public BytecodeInstrumentation getInstrumentation() {
        return instrumentation;
    }

    public Set<String> getLoadedClasses() {
        return new HashSet<>(this.classes.keySet());
    }

}
