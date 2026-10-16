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
package org.evosuite.runtime;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link System#hashCode(Object)} and {@link System#toString(Object)} cache, per class,
 * whether the class overrides {@code Object.hashCode()}/{@code toString()} -- populated
 * with SUT classes loaded through the disposable class loaders a JUnit check creates for
 * every check it runs. If the cache keeps those {@code Class} objects strongly reachable,
 * releasing the loader cannot free them, nor the rest of the class graph it defined.
 */
public class ObjectMethodCacheClassLoaderLeakTest {

    private static final String CLASS_NAME = "com.examples.ObjectMethodCaller$Plain";
    private static final String RESOURCE = "com/examples/ObjectMethodCaller$Plain.class";

    @Test
    public void classLoadedThroughDisposableLoaderIsNotRetainedAfterFullReset() throws Exception {
        WeakReference<ClassLoader> loaderRef = useAndForgetLoader();

        System.fullReset();

        for (int i = 0; i < 20 && loaderRef.get() != null; i++) {
            java.lang.System.gc();
            Thread.sleep(50);
        }

        assertNull(loaderRef.get(), "the disposable class loader is still reachable after fullReset()");
    }

    /**
     * Loads {@code Plain} through a fresh, throwaway class loader, runs it through both
     * caches, and returns only a weak handle -- everything else (the loader, the class, the
     * instance) goes out of scope with this method, so nothing here keeps them alive.
     */
    private WeakReference<ClassLoader> useAndForgetLoader() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            bytes = in.readAllBytes();
        }

        ClassLoader loader = new DisposableLoader(bytes);
        Class<?> clazz = loader.loadClass(CLASS_NAME);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        // Populate both caches with a Class<?> whose only root is the loader below.
        System.hashCode(instance);
        System.toString(instance);

        return new WeakReference<>(loader);
    }

    /** Defines exactly one class from in-memory bytes, with no parent but the bootstrap loader. */
    private static final class DisposableLoader extends ClassLoader {
        private final byte[] bytes;

        DisposableLoader(byte[] bytes) {
            super(null);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(CLASS_NAME)) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
