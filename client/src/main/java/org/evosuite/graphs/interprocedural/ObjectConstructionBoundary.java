/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.graphs.interprocedural;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.setup.DependencyAnalysis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies implementation classes whose internal state is outside the target artifact.
 */
public final class ObjectConstructionBoundary {

    private ObjectConstructionBoundary() {
    }

    /**
     * Checks whether a class is implemented outside the artifact of the target class.
     *
     * @param className the fully qualified class name
     * @return true for platform classes and classes that do not belong to the target artifact
     */
    public static boolean isExternalImplementationClass(String className) {
        if (isPlatformClass(className)) {
            return true;
        }
        Boolean sameArtifact = belongsToTargetArtifact(className);
        if (sameArtifact != null) {
            return !sameArtifact;
        }
        return className != null && !DependencyAnalysis.isTargetClassName(className)
                && !DependencyAnalysis.isTargetProject(className);
    }

    /**
     * Checks whether a class belongs to the Java platform.
     *
     * @param className the fully qualified class name, may be null
     * @return true if the name starts with a platform package prefix
     */
    public static boolean isPlatformClass(String className) {
        return className != null && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun."));
    }

    /**
     * Returns null when code-source metadata is unavailable and prefix fallback is required.
     */
    private static Boolean belongsToTargetArtifact(String className) {
        if (className == null || Properties.TARGET_CLASS == null
                || Properties.TARGET_CLASS.isEmpty()) {
            return null;
        }
        try {
            ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            String candidate = cachedClasspathOrigin(loader, className);
            String target = cachedClasspathOrigin(loader, Properties.TARGET_CLASS);
            return candidate == null || target == null ? null : candidate.equals(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Origins resolved through {@link #originLoader}; a new SUT loader starts a fresh cache.
     */
    private static final Map<String, String> ORIGINS = new ConcurrentHashMap<>();
    private static final String NO_ORIGIN = "";
    private static volatile ClassLoader originLoader;

    private static String cachedClasspathOrigin(ClassLoader loader, String className) {
        if (loader != originLoader) {
            synchronized (ORIGINS) {
                if (loader != originLoader) {
                    ORIGINS.clear();
                    originLoader = loader;
                }
            }
        }
        String origin = ORIGINS.computeIfAbsent(className, name -> {
            String resolved = classpathOrigin(loader, name);
            return resolved == null ? NO_ORIGIN : resolved;
        });
        return origin == NO_ORIGIN ? null : origin;
    }

    private static String classpathOrigin(ClassLoader loader, String className) {
        String resourceName = className.replace('.', '/') + ".class";
        java.net.URL resource = loader.getResource(resourceName);
        if (resource == null) {
            return null;
        }
        String location = resource.toExternalForm();
        int jarSeparator = location.indexOf("!/");
        if (jarSeparator >= 0) {
            return location.substring(0, jarSeparator);
        }
        return location.endsWith(resourceName)
                ? location.substring(0, location.length() - resourceName.length()) : null;
    }
}
