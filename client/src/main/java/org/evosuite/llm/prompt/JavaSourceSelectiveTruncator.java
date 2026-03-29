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
package org.evosuite.llm.prompt;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Selective truncator for Java source (SOURCE_CODE and DECOMPILED_SOURCE modes).
 * Uses JavaParser to parse the class structure and stub methods that don't fit
 * within the character budget.
 *
 * <p>Priority order:
 * <ol>
 *   <li>Class header and fields (always kept)</li>
 *   <li>Constructors in full (stubbed only if budget is extremely tight)</li>
 *   <li>Public methods in full, smallest first</li>
 *   <li>Non-public methods as stubs</li>
 *   <li>Inner types as declaration-only stubs</li>
 * </ol>
 */
public class JavaSourceSelectiveTruncator implements SelectiveMethodTruncator {

    private static final Logger logger = LoggerFactory.getLogger(JavaSourceSelectiveTruncator.class);

    @Override
    public String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }

        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(text);
        } catch (Exception e) {
            logger.debug("JavaParser failed to parse source for selective truncation: {}", e.getMessage());
            return null;
        }

        TypeDeclaration<?> primaryType = findPrimaryType(cu);
        if (primaryType == null) {
            logger.debug("No primary type declaration found for selective truncation");
            return null;
        }

        // Partition members
        List<ConstructorDeclaration> constructors = new ArrayList<>();
        List<MethodDeclaration> publicMethods = new ArrayList<>();
        List<MethodDeclaration> nonPublicMethods = new ArrayList<>();
        List<TypeDeclaration<?>> innerTypes = new ArrayList<>();

        for (BodyDeclaration<?> member : primaryType.getMembers()) {
            if (member instanceof ConstructorDeclaration) {
                constructors.add((ConstructorDeclaration) member);
            } else if (member instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) member;
                if (md.hasModifier(Modifier.Keyword.PUBLIC)) {
                    publicMethods.add(md);
                } else {
                    nonPublicMethods.add(md);
                }
            } else if (member instanceof TypeDeclaration) {
                innerTypes.add((TypeDeclaration<?>) member);
            }
            // FieldDeclarations are always kept as-is
        }

        // Sort public methods smallest-first (by body text length)
        Collections.sort(publicMethods, new Comparator<MethodDeclaration>() {
            @Override
            public int compare(MethodDeclaration a, MethodDeclaration b) {
                return bodyLength(a) - bodyLength(b);
            }
        });

        // Step 1: Stub all non-public methods and inner types
        for (MethodDeclaration md : nonPublicMethods) {
            stubMethod(md);
        }
        for (TypeDeclaration<?> inner : innerTypes) {
            stubInnerType(inner);
        }

        // Step 2: Check if we fit now
        String result = cu.toString();
        if (result.length() <= maxChars) {
            return result;
        }

        // Step 3: Progressively stub public methods, largest first
        List<MethodDeclaration> fullPublicMethods = new ArrayList<>(publicMethods);
        // Reverse to stub largest first
        for (int i = fullPublicMethods.size() - 1; i >= 0; i--) {
            stubMethod(fullPublicMethods.get(i));
            result = cu.toString();
            if (result.length() <= maxChars) {
                return result;
            }
        }

        // Step 4: Stub constructors largest first
        Collections.sort(constructors, new Comparator<ConstructorDeclaration>() {
            @Override
            public int compare(ConstructorDeclaration a, ConstructorDeclaration b) {
                return bodyLength(a) - bodyLength(b);
            }
        });
        for (int i = constructors.size() - 1; i >= 0; i--) {
            stubConstructor(constructors.get(i));
            result = cu.toString();
            if (result.length() <= maxChars) {
                return result;
            }
        }

        // Still over budget after stubbing everything — return null to let hard truncation handle it
        logger.debug("Selective truncation could not fit within {} chars (result: {} chars)",
                maxChars, result.length());
        return null;
    }

    private TypeDeclaration<?> findPrimaryType(CompilationUnit cu) {
        List<TypeDeclaration<?>> types = cu.getTypes();
        if (types.isEmpty()) {
            return null;
        }
        // Prefer the public type, or the first type
        for (TypeDeclaration<?> type : types) {
            if (type.hasModifier(Modifier.Keyword.PUBLIC)) {
                return type;
            }
        }
        return types.get(0);
    }

    private void stubMethod(MethodDeclaration md) {
        BlockStmt stubBody = new BlockStmt();
        Type returnType = md.getType();
        if (!(returnType instanceof VoidType)) {
            // Add a dummy return statement
            stubBody.addStatement(new ReturnStmt("null"));
        }
        md.setBody(stubBody);
        // Add a line comment to indicate stubbing
        md.setLineComment(" body stubbed for brevity");
    }

    private void stubConstructor(ConstructorDeclaration cd) {
        BlockStmt stubBody = new BlockStmt();
        cd.setBody(stubBody);
        cd.setLineComment(" body stubbed for brevity");
    }

    private void stubInnerType(TypeDeclaration<?> type) {
        // Remove all members from inner types
        type.getMembers().clear();
        type.setLineComment(" members stubbed for brevity");
    }

    private int bodyLength(MethodDeclaration md) {
        return md.getBody().map(b -> b.toString().length()).orElse(0);
    }

    private int bodyLength(ConstructorDeclaration cd) {
        return cd.getBody().toString().length();
    }
}
