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

import com.github.javaparser.ast.type.*;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.WildcardTypeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Resolves type names from parsed source code to java.lang.reflect types.
 * Uses import statements and the provided ClassLoader.
 */
public class TypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(TypeResolver.class);

    private final ClassLoader classLoader;

    /** simple name → fully qualified name (from explicit imports). */
    private final Map<String, String> importMap = new LinkedHashMap<>();

    /** wildcard import prefixes, e.g. "java.util" from "import java.util.*". */
    private final List<String> wildcardImports = new ArrayList<>();

    /** static import: simple method/field name → fully qualified class name. */
    private final Map<String, String> staticImportMap = new LinkedHashMap<>();

    /** static wildcard imports: fully qualified class names from "import static foo.Bar.*". */
    private final List<String> staticWildcardImports = new ArrayList<>();

    private static final Map<String, Class<?>> PRIMITIVE_TYPES = new HashMap<>();

    static {
        PRIMITIVE_TYPES.put("int", int.class);
        PRIMITIVE_TYPES.put("long", long.class);
        PRIMITIVE_TYPES.put("short", short.class);
        PRIMITIVE_TYPES.put("byte", byte.class);
        PRIMITIVE_TYPES.put("char", char.class);
        PRIMITIVE_TYPES.put("float", float.class);
        PRIMITIVE_TYPES.put("double", double.class);
        PRIMITIVE_TYPES.put("boolean", boolean.class);
        PRIMITIVE_TYPES.put("void", void.class);
    }

    public TypeResolver(ClassLoader classLoader, List<String> imports) {
        this.classLoader = classLoader;
        processImports(imports);
    }

    private void processImports(List<String> imports) {
        if (imports == null) {
            return;
        }
        for (String imp : imports) {
            String trimmed = imp.trim();
            // Strip leading "import " and trailing ";"
            if (trimmed.startsWith("import ")) {
                trimmed = trimmed.substring("import ".length());
            }
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            trimmed = trimmed.trim();

            boolean isStatic = false;
            if (trimmed.startsWith("static ")) {
                isStatic = true;
                trimmed = trimmed.substring("static ".length()).trim();
            }

            if (isStatic) {
                if (trimmed.endsWith(".*")) {
                    staticWildcardImports.add(trimmed.substring(0, trimmed.length() - 2));
                } else {
                    int lastDot = trimmed.lastIndexOf('.');
                    if (lastDot > 0) {
                        String className = trimmed.substring(0, lastDot);
                        String memberName = trimmed.substring(lastDot + 1);
                        staticImportMap.put(memberName, className);
                    }
                }
            } else {
                if (trimmed.endsWith(".*")) {
                    wildcardImports.add(trimmed.substring(0, trimmed.length() - 2));
                } else {
                    int lastDot = trimmed.lastIndexOf('.');
                    String simpleName = lastDot > 0 ? trimmed.substring(lastDot + 1) : trimmed;
                    importMap.put(simpleName, trimmed);
                }
            }
        }
    }

    /**
     * Resolve a simple or qualified class name to a Class.
     */
    public Class<?> resolveClass(String typeName) throws ClassNotFoundException {
        // Primitives
        Class<?> primitive = PRIMITIVE_TYPES.get(typeName);
        if (primitive != null) {
            return primitive;
        }

        // Array syntax: "String[]" → resolve component, then get array class
        if (typeName.endsWith("[]")) {
            String component = typeName.substring(0, typeName.length() - 2);
            Class<?> componentClass = resolveClass(component);
            return Array.newInstance(componentClass, 0).getClass();
        }

        // Contains a dot: could be fully qualified, or OuterSimpleName.Inner
        if (typeName.contains(".")) {
            // First try as fully qualified
            try {
                return loadClass(typeName);
            } catch (ClassNotFoundException ignored) {
                // Ignore and try inner class pattern
            }

            // Try resolving the first segment as an imported class (inner class pattern)
            int firstDot = typeName.indexOf('.');
            String outerName = typeName.substring(0, firstDot);
            String rest = typeName.substring(firstDot + 1);
            String outerFqn = importMap.get(outerName);
            if (outerFqn != null) {
                return loadClass(outerFqn + "$" + rest.replace('.', '$'));
            }

            throw new ClassNotFoundException("Cannot resolve type: " + typeName);
        }

        // Check explicit imports
        String fqn = importMap.get(typeName);
        if (fqn != null) {
            return loadClass(fqn);
        }

        // Check java.lang.*
        try {
            return loadClass("java.lang." + typeName);
        } catch (ClassNotFoundException ignored) {
            // Ignore and try wildcard imports
        }

        // Check wildcard imports
        for (String prefix : wildcardImports) {
            try {
                return loadClass(prefix + "." + typeName);
            } catch (ClassNotFoundException ignored) {
                // Ignore and try next prefix
            }
        }

        // Check TestCluster (available during EvoSuite test generation)
        try {
            return org.evosuite.setup.TestCluster.getInstance().getClass(typeName);
        } catch (Exception ignored) {
            // TestCluster may not be initialized outside of EvoSuite context
        }

        throw new ClassNotFoundException("Cannot resolve type: " + typeName);
    }

    private Class<?> loadClass(String fqn) throws ClassNotFoundException {
        try {
            return Class.forName(fqn, false, classLoader);
        } catch (ClassNotFoundException e) {
            // Try inner class: last '.' → '$'
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                String innerAttempt = fqn.substring(0, lastDot) + "$" + fqn.substring(lastDot + 1);
                try {
                    return Class.forName(innerAttempt, false, classLoader);
                } catch (ClassNotFoundException ignored) {
                    // Ignore and throw original exception
                }
            }
            throw e;
        }
    }

    /**
     * Resolve a JavaParser Type node to a java.lang.reflect.Type.
     * Handles primitives, class/interface types, parameterized types, wildcards, arrays, and void.
     *
     * @param jpType the JavaParser type node.
     * @return the resolved java.lang.reflect.Type.
     * @throws ClassNotFoundException if the type cannot be resolved.
     */
    public java.lang.reflect.Type resolveType(Type jpType) throws ClassNotFoundException {
        if (jpType instanceof PrimitiveType) {
            return resolvePrimitiveType((PrimitiveType) jpType);
        }
        if (jpType instanceof VoidType) {
            return void.class;
        }
        if (jpType instanceof ArrayType) {
            return resolveArrayType((ArrayType) jpType);
        }
        if (jpType instanceof ClassOrInterfaceType) {
            return resolveClassOrInterfaceType((ClassOrInterfaceType) jpType);
        }
        if (jpType instanceof WildcardType) {
            return resolveWildcardType((WildcardType) jpType);
        }
        if (jpType instanceof VarType) {
            // Can't resolve 'var' without context — caller must provide the type from RHS
            throw new ClassNotFoundException("Cannot resolve 'var' type without context");
        }
        throw new ClassNotFoundException("Unsupported JavaParser type: " + jpType.getClass().getName());
    }

    /**
     * Resolve to GenericClass for EvoSuite's type system.
     */
    public GenericClass<?> resolveGenericClass(Type jpType) throws ClassNotFoundException {
        java.lang.reflect.Type resolved = resolveType(jpType);
        return GenericClassFactory.get(resolved);
    }

    private Class<?> resolvePrimitiveType(PrimitiveType pt) {
        switch (pt.getType()) {
            case BOOLEAN: return boolean.class;
            case BYTE:    return byte.class;
            case CHAR:    return char.class;
            case DOUBLE:  return double.class;
            case FLOAT:   return float.class;
            case INT:     return int.class;
            case LONG:    return long.class;
            case SHORT:   return short.class;
            default:      throw new IllegalArgumentException("Unknown primitive: " + pt.getType());
        }
    }

    private java.lang.reflect.Type resolveArrayType(ArrayType at) throws ClassNotFoundException {
        java.lang.reflect.Type componentType = resolveType(at.getComponentType());
        // For raw classes, use Array.newInstance to get the array class
        if (componentType instanceof Class<?>) {
            return Array.newInstance((Class<?>) componentType, 0).getClass();
        }
        // For parameterized component types, create a GenericArrayType
        return new java.lang.reflect.GenericArrayType() {
            @Override
            public java.lang.reflect.Type getGenericComponentType() {
                return componentType;
            }

            @Override
            public String toString() {
                return componentType.getTypeName() + "[]";
            }

            @Override
            public boolean equals(Object obj) {
                if (obj instanceof java.lang.reflect.GenericArrayType) {
                    return componentType.equals(((java.lang.reflect.GenericArrayType) obj).getGenericComponentType());
                }
                return false;
            }

            @Override
            public int hashCode() {
                return componentType.hashCode();
            }
        };
    }

    private java.lang.reflect.Type resolveClassOrInterfaceType(ClassOrInterfaceType cit) throws ClassNotFoundException {
        // Build the full name including scope (e.g. "Map.Entry" → "Map" scope + "Entry" name)
        String name = buildClassName(cit);
        Class<?> rawClass = resolveClass(name);

        // If there are type arguments, build a ParameterizedType
        if (cit.getTypeArguments().isPresent()) {
            List<Type> jpTypeArgs = cit.getTypeArguments().get();

            // Diamond operator: empty type arguments <> — return raw class,
            // caller should infer from LHS declared type
            if (jpTypeArgs.isEmpty()) {
                return rawClass;
            }

            java.lang.reflect.Type[] resolvedArgs = new java.lang.reflect.Type[jpTypeArgs.size()];
            for (int i = 0; i < jpTypeArgs.size(); i++) {
                resolvedArgs[i] = resolveType(jpTypeArgs.get(i));
            }
            return new ParameterizedTypeImpl(rawClass, resolvedArgs, null);
        }

        return rawClass;
    }

    /**
     * Build the class name string from a ClassOrInterfaceType, handling scope
     * (e.g. Map.Entry becomes "Map.Entry" which resolveClass handles via inner class logic).
     */
    private String buildClassName(ClassOrInterfaceType cit) {
        if (cit.getScope().isPresent()) {
            return buildClassName(cit.getScope().get()) + "." + cit.getNameAsString();
        }
        return cit.getNameAsString();
    }

    private java.lang.reflect.Type resolveWildcardType(WildcardType wt) throws ClassNotFoundException {
        if (wt.getExtendedType().isPresent()) {
            java.lang.reflect.Type bound = resolveType(wt.getExtendedType().get());
            return new WildcardTypeImpl(new java.lang.reflect.Type[]{bound}, new java.lang.reflect.Type[0]);
        }
        if (wt.getSuperType().isPresent()) {
            java.lang.reflect.Type bound = resolveType(wt.getSuperType().get());
            return new WildcardTypeImpl(new java.lang.reflect.Type[]{Object.class},
                    new java.lang.reflect.Type[]{bound});
        }
        // Unbounded: ?
        return new WildcardTypeImpl(new java.lang.reflect.Type[]{Object.class}, new java.lang.reflect.Type[0]);
    }

    /**
     * Attempt to infer generic type arguments for a diamond constructor (e.g., new HashMap<>())
     * from the left-hand side declared type.
     *
     * @param rawClass the raw class of the constructor (e.g., HashMap)
     * @param lhsType  the declared type from the left-hand side
     * @return a ParameterizedType if inference succeeds, or the raw class if not
     */
    public java.lang.reflect.Type inferDiamondType(Class<?> rawClass, java.lang.reflect.Type lhsType) {
        if (lhsType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramLhs = (java.lang.reflect.ParameterizedType) lhsType;
            // If the LHS raw type is the same or a supertype, use its type arguments
            Class<?> lhsRaw = (Class<?>) paramLhs.getRawType();
            if (lhsRaw.isAssignableFrom(rawClass)) {
                // Use the LHS type arguments for the constructor's type
                return new ParameterizedTypeImpl(rawClass, paramLhs.getActualTypeArguments(), null);
            }
        }
        return rawClass;
    }

    /**
     * Resolve the class for a static import: given a simple method/field name,
     * find the declaring class.
     *
     * @param memberName simple name (e.g. "assertEquals")
     * @return the fully qualified class name, or null if not found
     */
    public String resolveStaticImportClass(String memberName) {
        // Check explicit static imports
        String className = staticImportMap.get(memberName);
        if (className != null) {
            return className;
        }

        // Check wildcard static imports
        for (String fqClass : staticWildcardImports) {
            try {
                Class<?> clazz = loadClass(fqClass);
                // Check if the class has a method or field with this name
                boolean hasMethod = Arrays.stream(clazz.getMethods())
                        .anyMatch(m -> m.getName().equals(memberName));
                if (hasMethod) {
                    return fqClass;
                }

                boolean hasField = Arrays.stream(clazz.getFields())
                        .anyMatch(f -> f.getName().equals(memberName));
                if (hasField) {
                    return fqClass;
                }
            } catch (ClassNotFoundException e) {
                logger.debug("Could not load class for static wildcard import: {}", fqClass);
            }
        }

        return null;
    }

    /**
     * Get the ClassLoader used by this resolver.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }
}
