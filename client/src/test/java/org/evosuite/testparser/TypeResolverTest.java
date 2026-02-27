/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testparser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TypeResolverTest {

    private TypeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of(
                        "import java.util.List;",
                        "import java.util.Map;",
                        "import java.util.ArrayList;",
                        "import java.util.HashMap;",
                        "import java.util.Stack;"
                )
        );
    }

    @Test
    void resolvePrimitiveTypes() throws ClassNotFoundException {
        assertEquals(int.class, resolver.resolveClass("int"));
        assertEquals(long.class, resolver.resolveClass("long"));
        assertEquals(boolean.class, resolver.resolveClass("boolean"));
        assertEquals(double.class, resolver.resolveClass("double"));
        assertEquals(float.class, resolver.resolveClass("float"));
        assertEquals(char.class, resolver.resolveClass("char"));
        assertEquals(byte.class, resolver.resolveClass("byte"));
        assertEquals(short.class, resolver.resolveClass("short"));
        assertEquals(void.class, resolver.resolveClass("void"));
    }

    @Test
    void resolveJavaLangTypes() throws ClassNotFoundException {
        assertEquals(String.class, resolver.resolveClass("String"));
        assertEquals(Integer.class, resolver.resolveClass("Integer"));
        assertEquals(Object.class, resolver.resolveClass("Object"));
    }

    @Test
    void resolveExplicitImports() throws ClassNotFoundException {
        assertEquals(List.class, resolver.resolveClass("List"));
        assertEquals(Map.class, resolver.resolveClass("Map"));
        assertEquals(ArrayList.class, resolver.resolveClass("ArrayList"));
        assertEquals(Stack.class, resolver.resolveClass("Stack"));
    }

    @Test
    void resolveFullyQualifiedName() throws ClassNotFoundException {
        assertEquals(LinkedList.class, resolver.resolveClass("java.util.LinkedList"));
    }

    @Test
    void resolveWildcardImport() throws ClassNotFoundException {
        TypeResolver wildcardResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of("import java.util.*;")
        );
        assertEquals(TreeSet.class, wildcardResolver.resolveClass("TreeSet"));
    }

    @Test
    void resolveArrayType() throws ClassNotFoundException {
        assertEquals(int[].class, resolver.resolveClass("int[]"));
        assertEquals(String[].class, resolver.resolveClass("String[]"));
        assertEquals(int[][].class, resolver.resolveClass("int[][]"));
    }

    @Test
    void resolveInnerClass() throws ClassNotFoundException {
        TypeResolver innerResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of("import java.util.Map;")
        );
        assertEquals(Map.Entry.class, innerResolver.resolveClass("Map.Entry"));
    }

    @Test
    void unresolvedTypeThrows() {
        assertThrows(ClassNotFoundException.class, () -> resolver.resolveClass("NonExistentType"));
    }

    // --- JavaParser Type node resolution ---

    @Test
    void resolveJavaParserPrimitiveType() throws ClassNotFoundException {
        Type jpType = PrimitiveType.intType();
        assertEquals(int.class, resolver.resolveType(jpType));
    }

    @Test
    void resolveJavaParserClassType() throws ClassNotFoundException {
        Type jpType = StaticJavaParser.parseClassOrInterfaceType("String");
        assertEquals(String.class, resolver.resolveType(jpType));
    }

    @Test
    void resolveJavaParserParameterizedType() throws ClassNotFoundException {
        // List<String>
        Type jpType = StaticJavaParser.parseType("List<String>");
        java.lang.reflect.Type resolved = resolver.resolveType(jpType);

        assertInstanceOf(ParameterizedType.class, resolved);
        ParameterizedType pt = (ParameterizedType) resolved;
        assertEquals(List.class, pt.getRawType());
        assertEquals(1, pt.getActualTypeArguments().length);
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    void resolveNestedGenericType() throws ClassNotFoundException {
        // Map<String, List<Integer>>
        Type jpType = StaticJavaParser.parseType("Map<String, List<Integer>>");
        java.lang.reflect.Type resolved = resolver.resolveType(jpType);

        assertInstanceOf(ParameterizedType.class, resolved);
        ParameterizedType pt = (ParameterizedType) resolved;
        assertEquals(Map.class, pt.getRawType());
        assertEquals(2, pt.getActualTypeArguments().length);
        assertEquals(String.class, pt.getActualTypeArguments()[0]);

        assertInstanceOf(ParameterizedType.class, pt.getActualTypeArguments()[1]);
        ParameterizedType inner = (ParameterizedType) pt.getActualTypeArguments()[1];
        assertEquals(List.class, inner.getRawType());
        assertEquals(Integer.class, inner.getActualTypeArguments()[0]);
    }

    @Test
    void resolveWildcardExtends() throws ClassNotFoundException {
        // List<? extends Number>
        Type jpType = StaticJavaParser.parseType("List<? extends Number>");
        java.lang.reflect.Type resolved = resolver.resolveType(jpType);

        assertInstanceOf(ParameterizedType.class, resolved);
        ParameterizedType pt = (ParameterizedType) resolved;
        assertEquals(List.class, pt.getRawType());

        java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
        assertInstanceOf(WildcardType.class, arg);
        WildcardType wt = (WildcardType) arg;
        assertEquals(Number.class, wt.getUpperBounds()[0]);
        assertEquals(0, wt.getLowerBounds().length);
    }

    @Test
    void resolveWildcardSuper() throws ClassNotFoundException {
        // List<? super Integer>
        Type jpType = StaticJavaParser.parseType("List<? super Integer>");
        java.lang.reflect.Type resolved = resolver.resolveType(jpType);

        assertInstanceOf(ParameterizedType.class, resolved);
        ParameterizedType pt = (ParameterizedType) resolved;

        java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
        assertInstanceOf(WildcardType.class, arg);
        WildcardType wt = (WildcardType) arg;
        assertEquals(Object.class, wt.getUpperBounds()[0]);
        assertEquals(Integer.class, wt.getLowerBounds()[0]);
    }

    @Test
    void resolveUnboundedWildcard() throws ClassNotFoundException {
        // List<?>
        Type jpType = StaticJavaParser.parseType("List<?>");
        java.lang.reflect.Type resolved = resolver.resolveType(jpType);

        assertInstanceOf(ParameterizedType.class, resolved);
        ParameterizedType pt = (ParameterizedType) resolved;

        java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
        assertInstanceOf(WildcardType.class, arg);
        WildcardType wt = (WildcardType) arg;
        assertEquals(Object.class, wt.getUpperBounds()[0]);
        assertEquals(0, wt.getLowerBounds().length);
    }

    @Test
    void resolveJavaParserArrayType() throws ClassNotFoundException {
        Type jpType = StaticJavaParser.parseType("String[]");
        assertEquals(String[].class, resolver.resolveType(jpType));
    }

    @Test
    void resolveJavaParserVoidType() throws ClassNotFoundException {
        Type jpType = new com.github.javaparser.ast.type.VoidType();
        assertEquals(void.class, resolver.resolveType(jpType));
    }

    @Test
    void resolveGenericClass() throws ClassNotFoundException {
        Type jpType = StaticJavaParser.parseType("List<String>");
        org.evosuite.utils.generic.GenericClass<?> gc = resolver.resolveGenericClass(jpType);
        assertNotNull(gc);
        assertEquals(List.class, gc.getRawClass());
    }

    // --- Diamond inference ---

    @Test
    void inferDiamondFromLHS() throws ClassNotFoundException {
        // LHS: Map<String, Integer>, RHS: new HashMap<>()
        java.lang.reflect.Type lhs = resolver.resolveType(StaticJavaParser.parseType("Map<String, Integer>"));
        java.lang.reflect.Type inferred = resolver.inferDiamondType(HashMap.class, lhs);

        assertInstanceOf(ParameterizedType.class, inferred);
        ParameterizedType pt = (ParameterizedType) inferred;
        assertEquals(HashMap.class, pt.getRawType());
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
        assertEquals(Integer.class, pt.getActualTypeArguments()[1]);
    }

    // --- Static imports ---

    @Test
    void resolveStaticImportExplicit() {
        TypeResolver staticResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of("import static org.junit.jupiter.api.Assertions.assertEquals;")
        );
        assertEquals("org.junit.jupiter.api.Assertions", staticResolver.resolveStaticImportClass("assertEquals"));
    }

    @Test
    void resolveStaticImportWildcard() {
        TypeResolver staticResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of("import static org.junit.jupiter.api.Assertions.*;")
        );
        assertEquals("org.junit.jupiter.api.Assertions", staticResolver.resolveStaticImportClass("assertEquals"));
    }

    @Test
    void resolveStaticImportNotFound() {
        assertNull(resolver.resolveStaticImportClass("nonExistentMethod"));
    }

    // --- TestCluster fallback ---

    @AfterEach
    void tearDown() {
        TestCluster.reset();
    }

    @Test
    void resolveViaTestClusterFallback() throws ClassNotFoundException {
        // LinkedList is not in the resolver's imports and not in java.lang,
        // so normal resolution would fail. Add it to TestCluster's analyzed classes.
        TestCluster.getInstance().getAnalyzedClasses().add(LinkedList.class);

        TypeResolver noImportsResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of()
        );
        assertEquals(LinkedList.class, noImportsResolver.resolveClass("LinkedList"));
    }

    @Test
    void resolveFailsWithoutImportsOrTestCluster() {
        // With an empty TestCluster and no imports, resolution should fail
        TypeResolver noImportsResolver = new TypeResolver(
                getClass().getClassLoader(),
                List.of()
        );
        assertThrows(ClassNotFoundException.class,
                () -> noImportsResolver.resolveClass("LinkedList"));
    }
}
