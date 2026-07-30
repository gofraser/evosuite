/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.graphs.interprocedural;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.setup.DependencyAnalysis;

/** Identifies implementation classes whose internal state is outside the target artifact. */
public final class ObjectConstructionBoundary {

    private ObjectConstructionBoundary() {
    }

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

    public static boolean isPlatformClass(String className) {
        return className != null && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun."));
    }

    /** Returns null when code-source metadata is unavailable and prefix fallback is required. */
    private static Boolean belongsToTargetArtifact(String className) {
        if (className == null || Properties.TARGET_CLASS == null
                || Properties.TARGET_CLASS.isEmpty()) {
            return null;
        }
        try {
            ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            String candidate = classpathOrigin(loader, className);
            String target = classpathOrigin(loader, Properties.TARGET_CLASS);
            return candidate == null || target == null ? null : candidate.equals(target);
        } catch (Throwable ignored) {
            return null;
        }
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
