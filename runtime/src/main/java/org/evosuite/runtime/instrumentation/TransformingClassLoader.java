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
package org.evosuite.runtime.instrumentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;

/**
 * A class loader that defines the classes it can instrument itself, from transformed bytecode, and
 * hands every other class to a delegate loader.
 *
 * <p>Subclasses say which classes they transform, where the bytes come from and how they are
 * transformed.
 */
public abstract class TransformingClassLoader extends ClassLoader {
    private static final Logger logger = LoggerFactory.getLogger(TransformingClassLoader.class);

    private final ClassLoader delegate;

    /**
     * @param parent   the parent class loader
     * @param delegate the class loader that loads the classes this loader does not transform
     */
    protected TransformingClassLoader(ClassLoader parent, ClassLoader delegate) {
        super(parent);
        this.delegate = delegate;
    }

    /**
     * Whether this loader transforms and defines the class itself.
     *
     * @param name the binary name of the class
     * @return false if the class is loaded by the delegate
     */
    protected abstract boolean canInstrument(String name);

    /**
     * Opens the original bytecode of a class.
     *
     * @param name the binary name of the class
     * @return a stream over the class file, or null if the class cannot be found
     */
    protected abstract InputStream openClass(String name) throws IOException;

    /**
     * Transforms the original bytecode of a class.
     *
     * @param name  the binary name of the class
     * @param bytes a stream over the class file
     * @return the bytecode to define
     */
    protected abstract byte[] transform(String name, InputStream bytes) throws IOException;

    /**
     * Where the original bytecode of a class comes from; the class is defined with a protection
     * domain for it.
     *
     * @param name the binary name of the class
     * @return the location of the class file, or null to define the class without a protection domain
     */
    protected URL getClassSource(String name) {
        return null;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (!canInstrument(name)) {
            Class<?> result = findLoadedClass(name);
            if (result != null) {
                return result;
            }
            return delegate.loadClass(name);
        }

        // Look up, transform and define under one lock: two threads loading the class for the first
        // time must get the same Class instead of defining it twice.
        synchronized (getClassLoadingLock(name)) {
            Class<?> result = findLoadedClass(name);
            if (result != null) {
                return result;
            }
            logger.info("Seeing class for first time: " + name);
            return instrumentClass(name);
        }
    }

    private Class<?> instrumentClass(String name) throws ClassNotFoundException {
        logger.info("Instrumenting class '" + name + "'.");
        try (InputStream is = openClass(name)) {
            if (is == null) {
                throw new ClassNotFoundException("Class '" + name.replace('.', '/') + ".class"
                        + "' should be in target project, but could not be found!");
            }
            byte[] byteBuffer = transform(name, is);
            createPackageDefinition(name);
            ProtectionDomain protectionDomain = createProtectionDomain(getClassSource(name));
            Class<?> result = protectionDomain == null
                    ? defineClass(name, byteBuffer, 0, byteBuffer.length)
                    : defineClass(name, byteBuffer, 0, byteBuffer.length, protectionDomain);
            return result;
        } catch (VirtualMachineError e) {
            // A StackOverflowError while instrumenting (a StackOverflowError handler in the SUT
            // resolving a class, as Jackson's BeanSerializerBase does) must stay a
            // StackOverflowError: wrapped in ClassNotFoundException it becomes a
            // NoClassDefFoundError that HotSpot records permanently for that constant-pool
            // entry, whereas a VirtualMachineError is not recorded and the caller's next
            // attempt can succeed.
            throw e;
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            logger.error("Error while loading class: " + t.getMessage(), t);
            throw new ClassNotFoundException(t.getMessage(), t);
        }
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
     * @param className the name of the class for which to create the package definition
     */
    private void createPackageDefinition(String className) {
        int i = className.lastIndexOf('.');
        if (i != -1) {
            String pkgname = className.substring(0, i);
            // Check if package already loaded.
            if (getPackage(pkgname) == null) {
                try {
                    definePackage(pkgname, null, null, null, null, null, null, null);
                } catch (IllegalArgumentException e) {
                    // Ignore if already defined by another thread
                }
            }
        }
    }
}
