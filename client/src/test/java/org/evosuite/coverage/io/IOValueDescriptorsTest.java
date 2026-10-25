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
package org.evosuite.coverage.io;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pins the descriptors, and their order, that a declared type offers as goals.
 */
public class IOValueDescriptorsTest {

    private static List<String> statics(Class<?> clazz, boolean collections) {
        return IOValueDescriptors.staticDescriptors(Type.getType(clazz), clazz, collections);
    }

    @Test
    public void primitives() {
        Assertions.assertEquals(Arrays.asList("True", "False"), statics(boolean.class, false));
        Assertions.assertEquals(Arrays.asList("AlphabeticChar", "Digit", "OtherChar"), statics(char.class, true));
        Assertions.assertEquals(Arrays.asList("Negative", "Zero", "Positive"), statics(long.class, false));
        Assertions.assertTrue(statics(void.class, false).isEmpty());
    }

    @Test
    public void wrappersAndStrings() {
        Assertions.assertEquals(Arrays.asList("Null", "Negative", "Zero", "Positive"), statics(Integer.class, true));
        Assertions.assertEquals(Arrays.asList("Null", "True", "False"), statics(Boolean.class, false));
        Assertions.assertEquals(Arrays.asList("Null", "EmptyString", "NonEmptyString"), statics(String.class, true));
        Assertions.assertEquals(Arrays.asList("Null", "EmptyArray", "NonEmptyArray"), statics(int[].class, false));
    }

    @Test
    public void collectionsOnlyWhenRequested() {
        Assertions.assertEquals(Arrays.asList("Null", "EmptyList", "NonEmptyList"), statics(ArrayList.class, true));
        Assertions.assertEquals(Arrays.asList("Null", "EmptySet", "NonEmptySet"), statics(Set.class, true));
        Assertions.assertEquals(Arrays.asList("Null", "EmptyMap", "NonEmptyMap"), statics(Map.class, true));
        List<String> asObject = statics(List.class, false);
        Assertions.assertEquals(Arrays.asList("Null", "NonNull"), asObject.subList(0, 2));
        Assertions.assertTrue(asObject.contains("NonNull:java.util.List:isEmpty()Z:True"), asObject.toString());
        Assertions.assertTrue(asObject.contains("NonNull:java.util.List:size()I:Zero"), asObject.toString());
    }
}
