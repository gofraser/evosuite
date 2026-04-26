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
package org.evosuite.utils;

import java.lang.reflect.Method;

/**
 * Reflective bridge to access master-only classloading hooks from the client module.
 */
public final class MasterClassLoaderBridge {

    private static final Object UNAVAILABLE = new Object();
    private static final String CLASS_NAME = "org.evosuite.rmi.MasterClassLoader";

    private static volatile Object holderClassOrUnavailable;
    private static volatile Object getIfInitializedMethodOrUnavailable;
    private static volatile Object isMasterProcessMethodOrUnavailable;

    private MasterClassLoaderBridge() {
    }

    public static ClassLoader getMasterClassLoaderIfInitialized() {
        Method m = getMethodGetIfInitialized();
        if (m == null) {
            return null;
        }
        try {
            Object loader = m.invoke(null);
            return loader instanceof ClassLoader ? (ClassLoader) loader : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean isMasterProcess() {
        Method m = getMethodIsMasterProcess();
        if (m == null) {
            return false;
        }
        try {
            Object flag = m.invoke(null);
            return flag instanceof Boolean && (Boolean) flag;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static Method getMethodGetIfInitialized() {
        Object cached = getIfInitializedMethodOrUnavailable;
        if (cached == UNAVAILABLE) {
            return null;
        }
        if (cached instanceof Method) {
            return (Method) cached;
        }
        Class<?> holder = getHolderClass();
        if (holder == null) {
            getIfInitializedMethodOrUnavailable = UNAVAILABLE;
            return null;
        }
        try {
            Method m = holder.getMethod("getIfInitialized");
            getIfInitializedMethodOrUnavailable = m;
            return m;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            getIfInitializedMethodOrUnavailable = UNAVAILABLE;
            return null;
        }
    }

    private static Method getMethodIsMasterProcess() {
        Object cached = isMasterProcessMethodOrUnavailable;
        if (cached == UNAVAILABLE) {
            return null;
        }
        if (cached instanceof Method) {
            return (Method) cached;
        }
        Class<?> holder = getHolderClass();
        if (holder == null) {
            isMasterProcessMethodOrUnavailable = UNAVAILABLE;
            return null;
        }
        try {
            Method m = holder.getMethod("isMasterProcess");
            isMasterProcessMethodOrUnavailable = m;
            return m;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            isMasterProcessMethodOrUnavailable = UNAVAILABLE;
            return null;
        }
    }

    private static Class<?> getHolderClass() {
        Object cached = holderClassOrUnavailable;
        if (cached == UNAVAILABLE) {
            return null;
        }
        if (cached instanceof Class<?>) {
            return (Class<?>) cached;
        }
        try {
            Class<?> holder = Class.forName(CLASS_NAME);
            holderClassOrUnavailable = holder;
            return holder;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            holderClassOrUnavailable = UNAVAILABLE;
            return null;
        }
    }
}

