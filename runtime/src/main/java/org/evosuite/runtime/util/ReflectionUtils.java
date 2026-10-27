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
package org.evosuite.runtime.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

public class ReflectionUtils {

    protected static final Logger logger = LoggerFactory.getLogger(ReflectionUtils.class);

    /**
     * Reads the members of a class, treating a class whose dependencies cannot be loaded as
     * having none.
     */
    private static <T> T[] members(Class<?> clazz, Supplier<T[]> reader, T[] none, String what) {
        try {
            return reader.get();
        } catch (NoClassDefFoundError e) {
            logger.info("Error while trying to load " + what + " of class " + clazz.getName() + ": " + e);
            return none;
        }
    }

    /**
     * Gets all declared classes of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of declared classes
     */
    public static Class<?>[] getDeclaredClasses(Class<?> clazz) {
        return members(clazz, clazz::getDeclaredClasses, new Class<?>[0], "declared classes");
    }

    /**
     * Gets all public classes of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of public classes
     */
    public static Class<?>[] getClasses(Class<?> clazz) {
        return members(clazz, clazz::getClasses, new Class<?>[0], "classes");
    }

    /**
     * Gets all declared constructors of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of declared constructors
     */
    public static Constructor<?>[] getDeclaredConstructors(Class<?> clazz) {
        return members(clazz, clazz::getDeclaredConstructors, new Constructor<?>[0], "declared constructors");
    }

    /**
     * Gets all public constructors of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of public constructors
     */
    public static Constructor<?>[] getConstructors(Class<?> clazz) {
        return members(clazz, clazz::getConstructors, new Constructor<?>[0], "constructors");
    }

    /**
     * Gets all interfaces implemented by the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of interfaces
     */
    public static Class<?>[] getInterfaces(Class<?> clazz) {
        return members(clazz, clazz::getInterfaces, new Class<?>[0], "interfaces");
    }

    /**
     * Gets all declared methods of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of declared methods
     */
    public static Method[] getDeclaredMethods(Class<?> clazz) {
        return members(clazz, clazz::getDeclaredMethods, new Method[0], "declared methods");
    }

    /**
     * Gets all public methods of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of public methods
     */
    public static Method[] getMethods(Class<?> clazz) {
        return members(clazz, clazz::getMethods, new Method[0], "methods");
    }

    /**
     * Gets all declared fields of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of declared fields
     */
    public static Field[] getDeclaredFields(Class<?> clazz) {
        return members(clazz, clazz::getDeclaredFields, new Field[0], "declared fields");
    }

    /**
     * Gets a specific declared field of the given class, handling NoClassDefFoundError and NoSuchFieldException.
     *
     * @param clazz     the class to analyze
     * @param fieldName the name of the field
     * @return the declared field, or null if not found
     */
    public static Field getDeclaredField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoClassDefFoundError | NoSuchFieldException e) {
            logger.info("Error while trying to load declared field '" + fieldName + "' of class "
                    + clazz.getName() + ": " + e);
            return null;
        }
    }

    /**
     * Gets all public fields of the given class, handling NoClassDefFoundError.
     *
     * @param clazz the class to analyze
     * @return an array of public fields
     */
    public static Field[] getFields(Class<?> clazz) {
        return members(clazz, clazz::getFields, new Field[0], "fields");
    }

    public static Annotation[] getDeclaredAnnotations(Class<?> clazz) {
        return clazz.getDeclaredAnnotations();
    }

    public static Annotation[] getAnnotations(Class<?> clazz) {
        return clazz.getAnnotations();
    }

    public static Annotation[] getDeclaredAnnotations(Field field) {
        return field.getDeclaredAnnotations();
    }

    public static Annotation[] getDeclaredAnnotations(Method method) {
        return method.getDeclaredAnnotations();
    }
}
