package org.evosuite.graphs.cfg;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CFGMethodAdapterTest {

    @Test
    public void testClear() throws Exception {
        ClassLoader cl = new ClassLoader() {};

        // Populate methods via reflection to be robust against visibility changes
        Field methodsField = CFGMethodAdapter.class.getDeclaredField("methods");
        methodsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ClassLoader, Map<String, Set<String>>> methods = (Map<ClassLoader, Map<String, Set<String>>>) methodsField.get(null);

        Map<String, Set<String>> classMap = new HashMap<>();
        Set<String> methodSet = new HashSet<>();
        methodSet.add("method1");
        classMap.put("Class1", methodSet);
        methods.put(cl, classMap);

        assertTrue(CFGMethodAdapter.getNumMethods(cl) > 0, "Should have methods before clear");

        CFGMethodAdapter.clear();

        assertEquals(0, CFGMethodAdapter.getNumMethods(cl), "Should have 0 methods after clear");
        assertTrue(methods.isEmpty(), "Map should be empty");
    }
}
