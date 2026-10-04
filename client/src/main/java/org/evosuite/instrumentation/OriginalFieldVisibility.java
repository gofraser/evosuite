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

import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Original source visibility of fields promoted by {@link AccessibleClassAdapter}. */
public final class OriginalFieldVisibility {
    private static final int ACCESS_MASK = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;
    // Values contain no Class or ClassLoader references, so finished SUT loaders remain collectable.
    private static final Map<ClassLoader, Map<String, Integer>> VISIBILITY = new WeakHashMap<>();

    private OriginalFieldVisibility() {
    }

    static synchronized void record(ClassLoader loader, String className, String name,
                                    String descriptor, int modifiers) {
        VISIBILITY.computeIfAbsent(loader, ignored -> new HashMap<>())
                .putIfAbsent(key(className, name, descriptor), modifiers & ACCESS_MASK);
    }

    public static synchronized int getModifiers(Field field) {
        Map<String, Integer> fields = VISIBILITY.get(field.getDeclaringClass().getClassLoader());
        Integer original = fields == null ? null : fields.get(key(field.getDeclaringClass().getName(),
                field.getName(), Type.getDescriptor(field.getType())));
        return original == null ? field.getModifiers()
                : (field.getModifiers() & ~ACCESS_MASK) | original;
    }

    private static String key(String className, String name, String descriptor) {
        return className.replace('/', '.') + '#' + name + ':' + descriptor;
    }
}
