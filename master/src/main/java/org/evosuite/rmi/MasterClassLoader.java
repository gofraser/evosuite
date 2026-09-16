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
package org.evosuite.rmi;

import org.evosuite.classpath.ClassPathHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Master-side classloader used to resolve project classes during RMI
 * deserialization.
 */
public final class MasterClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(MasterClassLoader.class);

    private static volatile URLClassLoader instance;
    private static volatile boolean masterProcess;

    private MasterClassLoader() {
    }

    public static URLClassLoader get() {
        URLClassLoader current = instance;
        if (current != null) {
            return current;
        }
        synchronized (MasterClassLoader.class) {
            if (instance == null) {
                instance = create();
            }
            return instance;
        }
    }

    public static URLClassLoader getIfInitialized() {
        return instance;
    }

    public static void markMasterProcess() {
        masterProcess = true;
    }

    public static boolean isMasterProcess() {
        return masterProcess;
    }

    private static URLClassLoader create() {
        Set<URL> urls = new LinkedHashSet<>();

        String projectClasspath = ClassPathHandler.getInstance().getTargetProjectClasspath();
        if (projectClasspath == null || projectClasspath.trim().isEmpty()) {
            throw new IllegalStateException("Target project classpath is empty; cannot initialize MasterClassLoader");
        }
        addClasspathEntries(urls, projectClasspath);
        if (urls.isEmpty()) {
            throw new IllegalStateException("Target project classpath has no existing entries; cannot initialize MasterClassLoader");
        }

        URL[] cp = urls.toArray(new URL[0]);
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        logger.debug("Initializing MasterClassLoader with {} entries", cp.length);
        return new ProjectFirstClassLoader(cp, parent);
    }

    /**
     * Prefixes that must always come from the master's own classpath: the JDK, EvoSuite
     * itself and the libraries it shares with the client by identity (logging, JUnit,
     * ASM). Everything else is a project class as far as the master is concerned.
     */
    private static final String[] PARENT_FIRST_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.evosuite.", "shaded.", "org.slf4j.", "ch.qos.logback.",
            "org.junit.", "junit.", "org.hamcrest.", "org.objectweb.asm.",
            "org.w3c.", "org.xml."
    };

    /**
     * Resolves project classes from the project classpath before consulting the parent.
     *
     * <p>A plain parent-first {@link URLClassLoader} hands back EvoSuite's own bundled
     * copy of any library that the project also ships -- jackson-databind and gson are
     * part of the master jar -- so deserialising a {@code GenericConstructor} of
     * {@code com.fasterxml.jackson.databind.introspect.POJOPropertyBuilder} looked the
     * constructor up in the wrong version, threw "Unknown constructor", broke the RMI
     * connection and left the master with an empty suite (Size 0, coverage 0.0) although
     * the client had written hundreds of tests. Every JacksonDatabind and Gson target in
     * the Defects4J assertion experiment was affected.
     */
    private static final class ProjectFirstClassLoader extends URLClassLoader {

        ProjectFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isParentFirst(name)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException notInProject) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isParentFirst(String name) {
            for (String prefix : PARENT_FIRST_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void addClasspathEntries(Set<URL> urls, String classpath) {
        if (classpath == null || classpath.trim().isEmpty()) {
            return;
        }
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            File f = new File(entry);
            if (!f.exists()) {
                continue;
            }
            try {
                urls.add(f.toURI().toURL());
            } catch (MalformedURLException e) {
                logger.debug("Skipping malformed classpath entry {}", entry, e);
            }
        }
    }
}
