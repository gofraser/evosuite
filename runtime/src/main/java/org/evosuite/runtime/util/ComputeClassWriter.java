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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A ClassWriter that computes the common super class of two classes without
 * actually loading them with a ClassLoader.
 *
 * @author Eric Bruneton
 */
public class ComputeClassWriter extends ClassWriter {

    /**
     * Frame computation asks for the common superclass of two types over and over, and each
     * answer walks both ancestor chains, reading and parsing every class on the way through
     * {@link ClassLoader#getResourceAsStream(String)}. On a SUT with a few hundred classpath
     * entries a single lookup costs hundreds of file-system probes, which is how instrumenting
     * one class could take more than the ten seconds a static initializer is given before it is
     * abandoned. Both the parsed facts and the answers are cached per class loader, keyed
     * weakly so a discarded test-generation context takes its cache with it.
     */
    private static final Map<ClassLoader, Map<String, TypeFacts>> TYPE_FACTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<ClassLoader, Map<String, String>> COMMON_SUPERCLASS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Bounds the per-loader answer cache; the fact cache is bounded by the classpath itself. */
    private static final int MAX_CACHED_ANSWERS = 20_000;

    /** What frame computation needs to know about a type, or {@link #MISSING} if it has no bytecode. */
    private static final class TypeFacts {
        private final String superName;
        private final String[] interfaces;
        private final boolean isInterface;

        private TypeFacts(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }
    }

    private static final TypeFacts MISSING = new TypeFacts(null, new String[0], false);

    public ComputeClassWriter(final int flags) {
        super(flags);
    }

    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        Map<String, String> answers = answerCache();
        String key = type1 + ' ' + type2;
        String cached = answers.get(key);
        if (cached != null) {
            return cached;
        }
        String answer = computeCommonSuperClass(type1, type2);
        if (answers.size() < MAX_CACHED_ANSWERS) {
            answers.put(key, answer);
        }
        return answer;
    }

    private String computeCommonSuperClass(final String type1, final String type2) {
        try {
            TypeFacts info1 = typeFacts(type1);
            TypeFacts info2 = typeFacts(type2);
            // If either type can't be resolved on the classpath (common when a SUT
            // references classes from dependencies that aren't available), fall back
            // to Object. Throwing here would abort the entire instrumentation and
            // propagate up through RMI (breaking test-case deserialization on the
            // master), whereas Object produces a valid — if conservative — frame.
            if (info1 == MISSING || info2 == MISSING) {
                return "java/lang/Object";
            }

            if (info1.isInterface) {
                if (typeImplements(type2, info2, type1)) {
                    return type1;
                }
                if (info2.isInterface) {
                    if (typeImplements(type1, info1, type2)) {
                        return type2;
                    }
                }
                return "java/lang/Object";
            }
            if (info2.isInterface) {
                if (typeImplements(type1, info1, type2)) {
                    return type2;
                } else {
                    return "java/lang/Object";
                }
            }
            StringBuilder b1 = typeAncestors(type1, info1);
            StringBuilder b2 = typeAncestors(type2, info2);
            String result = "java/lang/Object";
            int end1 = b1.length();
            int end2 = b2.length();
            while (true) {
                int start1 = b1.lastIndexOf(";", end1 - 1);
                int start2 = b2.lastIndexOf(";", end2 - 1);
                if (start1 != -1 && start2 != -1
                        && end1 - start1 == end2 - start2) {
                    String p1 = b1.substring(start1 + 1, end1);
                    String p2 = b2.substring(start2 + 1, end2);
                    if (p1.equals(p2)) {
                        result = p1;
                        end1 = start1;
                        end2 = start2;
                    } else {
                        return result;
                    }
                } else {
                    return result;
                }
            }
        } catch (IOException | NullPointerException e) {
            // Missing transitive ancestor during typeAncestors/typeImplements —
            // fall back conservatively rather than aborting instrumentation.
            return "java/lang/Object";
        }
    }

    private Map<String, String> answerCache() {
        return COMMON_SUPERCLASS.computeIfAbsent(contextLoader(),
                cl -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static ClassLoader contextLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ComputeClassWriter.class.getClassLoader() : loader;
    }

    /** The cached facts about a type, or {@link #MISSING} when its bytecode cannot be read. */
    private TypeFacts typeFacts(final String type) {
        Map<String, TypeFacts> facts = TYPE_FACTS.computeIfAbsent(contextLoader(),
                cl -> Collections.synchronizedMap(new LinkedHashMap<>()));
        TypeFacts known = facts.get(type);
        if (known != null) {
            return known;
        }
        TypeFacts computed;
        try {
            ClassReader reader = typeInfo(type);
            computed = new TypeFacts(reader.getSuperName(), reader.getInterfaces(),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
        } catch (IOException | NullPointerException e) {
            computed = MISSING;
        }
        facts.put(type, computed);
        return computed;
    }

    /**
     * Returns the internal names of the ancestor classes of the given type.
     *
     * @param type the internal name of a class or interface.
     * @param info the ClassReader corresponding to 'type'.
     * @return a StringBuilder containing the ancestor classes of 'type',
     *     separated by ';'. The returned string has the following format:
     *     ";type1;type2 ... ;typeN", where type1 is 'type', and typeN is a
     *     direct subclass of Object. If 'type' is Object, the returned
     *     string is empty.
     * @throws IOException if the bytecode of 'type' or of some of its ancestor class
     *     cannot be loaded.
     */
    private StringBuilder typeAncestors(String type, TypeFacts info)
            throws IOException {
        StringBuilder b = new StringBuilder();
        while (!"java/lang/Object".equals(type)) {
            b.append(';').append(type);
            type = info.superName;
            if (type == null) {
                break;
            }
            info = typeFacts(type);
            if (info == MISSING) {
                break;
            }
        }
        return b;
    }

    /**
     * Returns true if the given type implements the given interface.
     *
     * @param type the internal name of a class or interface.
     * @param info the ClassReader corresponding to 'type'.
     * @param itf  the internal name of a interface.
     * @return true if 'type' implements directly or indirectly 'itf'
     * @throws IOException if the bytecode of 'type' or of some of its ancestor class
     *                     cannot be loaded.
     */
    private boolean typeImplements(String type, TypeFacts info, String itf)
            throws IOException {
        while (!"java/lang/Object".equals(type)) {
            String[] itfs = info.interfaces;
            for (final String s : itfs) {
                if (s.equals(itf)) {
                    return true;
                }
            }
            for (final String s : itfs) {
                TypeFacts itfInfo = typeFacts(s);
                if (itfInfo != MISSING && typeImplements(s, itfInfo, itf)) {
                    return true;
                }
            }
            type = info.superName;
            if (type == null) {
                return false;
            }
            info = typeFacts(type);
            if (info == MISSING) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns a ClassReader corresponding to the given class or interface.
     *
     * @param type the internal name of a class or interface.
     * @return the ClassReader corresponding to 'type'.
     * @throws IOException          if the bytecode of 'type' cannot be loaded.
     * @throws NullPointerException if the bytecode of 'type' cannot be found.
     */
    private ClassReader typeInfo(final String type) throws IOException, NullPointerException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(type + ".class")) {
            if (is == null) {
                throw new NullPointerException("Class not found " + type);
            }
            return new ClassReader(is);
        }
    }
}
