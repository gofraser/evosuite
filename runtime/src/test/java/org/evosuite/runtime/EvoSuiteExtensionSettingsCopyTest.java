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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

/**
 * The settings the extension hands to the system class loader must be all of them, or the
 * instrumented code of the class under test runs with the defaults for the missing ones.
 */
public class EvoSuiteExtensionSettingsCopyTest {

    @Test
    public void testEverySettingIsCopiedToAnotherClassLoader() throws Exception {
        URL location = RuntimeSettings.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader other = new URLClassLoader(new URL[]{location}, null)) {
            Class<?> otherSettings = Class.forName(RuntimeSettings.class.getName(), true, other);
            Assertions.assertNotSame(RuntimeSettings.class, otherSettings);

            Map<Field, Object> saved = new HashMap<>();
            try {
                for (Field field : settingFields()) {
                    saved.put(field, field.get(null));
                    field.set(null, differentFrom(field.get(null)));
                }

                EvoSuiteExtension.copySettings(otherSettings);

                for (Field field : settingFields()) {
                    Object expected = field.get(null);
                    Object actual = otherSettings.getField(field.getName()).get(null);
                    Assertions.assertEquals(String.valueOf(expected), String.valueOf(actual),
                            "RuntimeSettings." + field.getName() + " was not copied");
                }
            } finally {
                for (Map.Entry<Field, Object> entry : saved.entrySet()) {
                    entry.getKey().set(null, entry.getValue());
                }
            }
        }
    }

    private static Field[] settingFields() {
        return java.util.Arrays.stream(RuntimeSettings.class.getFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()))
                .toArray(Field[]::new);
    }

    private static Object differentFrom(Object value) {
        if (value instanceof Boolean) {
            return !((Boolean) value);
        }
        if (value instanceof Integer) {
            return (Integer) value + 7;
        }
        if (value instanceof Long) {
            return (Long) value + 7L;
        }
        if (value instanceof String) {
            return value + "x";
        }
        if (value instanceof Enum) {
            Object[] constants = value.getClass().getEnumConstants();
            return constants[(((Enum<?>) value).ordinal() + 1) % constants.length];
        }
        throw new IllegalStateException("Unexpected type of setting: " + value);
    }
}
