/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Parses JUnit test source code into AST structures.
 * Extracts import declarations and @Test method bodies for further processing
 * by {@link StatementParser}.
 */
public class TestMethodParser {

    private static final Logger logger = LoggerFactory.getLogger(TestMethodParser.class);

    /**
     * Parse a complete test class source string into a CompilationUnit AST.
     */
    public CompilationUnit parseSource(String sourceCode) {
        return StaticJavaParser.parse(sourceCode);
    }

    /**
     * Extract all import declarations from a CompilationUnit as strings
     * (including "import " prefix and ";" suffix, and "static" if applicable).
     */
    public List<String> extractImports(CompilationUnit cu) {
        List<String> imports = new ArrayList<>();

        // Add the package declaration as a wildcard import so that
        // same-package types can be resolved (mirrors Java semantics).
        cu.getPackageDeclaration().ifPresent(pkg ->
                imports.add("import " + pkg.getNameAsString() + ".*;"));

        for (ImportDeclaration imp : cu.getImports()) {
            StringBuilder sb = new StringBuilder("import ");
            if (imp.isStatic()) {
                sb.append("static ");
            }
            sb.append(imp.getNameAsString());
            if (imp.isAsterisk()) {
                sb.append(".*");
            }
            sb.append(";");
            imports.add(sb.toString());
        }
        return imports;
    }

    /**
     * Find all @Test methods in the compilation unit.
     */
    public List<MethodDeclaration> findTestMethods(CompilationUnit cu) {
        List<MethodDeclaration> testMethods = new ArrayList<>();
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : clazz.getMethods()) {
                if (isTestMethod(method)) {
                    testMethods.add(method);
                }
            }
        }
        return testMethods;
    }

    /**
     * Find a specific @Test method by name.
     */
    public Optional<MethodDeclaration> findTestMethod(CompilationUnit cu, String methodName) {
        return findTestMethods(cu).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .findFirst();
    }

    /**
     * Extract the body statements from a method declaration.
     */
    public List<com.github.javaparser.ast.stmt.Statement> extractBody(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        if (body.isPresent()) {
            return body.get().getStatements();
        }
        return new ArrayList<>();
    }

    /**
     * Check if a method is a test method (has @Test annotation from JUnit 4 or 5).
     */
    private boolean isTestMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.equals("Test")
                    || name.equals("org.junit.Test")
                    || name.equals("org.junit.jupiter.api.Test");
        });
    }

    /**
     * Extract the expected exception class name from a JUnit 4 {@code @Test(expected = X.class)}
     * annotation, if present.
     *
     * @return the fully qualified or simple class name, or null if not specified
     */
    public String extractExpectedException(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .filter(a -> {
                    String name = a.getNameAsString();
                    return name.equals("Test") || name.equals("org.junit.Test");
                })
                .filter(a -> a instanceof NormalAnnotationExpr)
                .map(a -> (NormalAnnotationExpr) a)
                .flatMap(a -> a.getPairs().stream())
                .filter(p -> p.getNameAsString().equals("expected"))
                .map(p -> {
                    Expression value = p.getValue();
                    if (value instanceof ClassExpr) {
                        return ((ClassExpr) value).getTypeAsString();
                    }
                    // FieldAccessExpr for qualified names like SomeException.class
                    return value.toString().replace(".class", "");
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Wrap a method body string in a minimal class so it can be parsed by JavaParser.
     * Used when only a method body (statements) is provided without class structure.
     */
    public String wrapMethodBody(String methodBody, List<String> imports) {
        return wrapMethodBody(methodBody, imports, null);
    }

    /**
     * Wrap a method body string in a minimal class so it can be parsed by JavaParser.
     * Used when only a method body (statements) is provided without class structure.
     *
     * @param methodBody  the method body statements
     * @param imports     import declarations
     * @param packageName package declaration for same-package resolution, or null to omit
     */
    public String wrapMethodBody(String methodBody, List<String> imports, String packageName) {
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        if (imports != null) {
            for (String imp : imports) {
                if (!imp.startsWith("import ")) {
                    sb.append("import ");
                }
                sb.append(imp);
                if (!imp.endsWith(";")) {
                    sb.append(";");
                }
                sb.append("\n");
            }
        }
        sb.append("public class __ParseWrapper__ {\n");
        sb.append("    @org.junit.Test\n");
        sb.append("    public void __testMethod__() {\n");
        sb.append(methodBody);
        sb.append("\n    }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
