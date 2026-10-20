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
package org.evosuite.testparser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inlines the local helper methods of an LLM-written test class at their call sites. EvoSuite
 * parses test bodies only, so a call such as {@code createFoo()} to a helper declared next to the
 * test would otherwise be lost. Helper bodies are instantiated with fresh local names and their
 * argument substituted, and the statements they emit are rolled back if the inlining fails. Used
 * by {@link StatementParser} in best-effort mode.
 */
final class HelperInliner {

    private static final Logger logger = LoggerFactory.getLogger(HelperInliner.class);

    private final StatementParser parser;
    private final DefaultTestCase testCase;
    private final VariableScope scope;
    private final Map<String, MethodDeclaration> inlineHelperMethods;

    HelperInliner(StatementParser parser,
                  DefaultTestCase testCase,
                  VariableScope scope,
                  Map<String, MethodDeclaration> inlineHelperMethods) {
        this.parser = parser;
        this.testCase = testCase;
        this.scope = scope;
        this.inlineHelperMethods = inlineHelperMethods;
    }

    static String inlineHelperKey(String methodName, int arity) {
        return methodName + "#" + arity;
    }

    static boolean isVoidContext(Type declaredType, String targetVarName) {
        if (targetVarName == null) {
            return true;
        }
        if (!(declaredType instanceof Class<?>)) {
            return false;
        }
        Class<?> raw = (Class<?>) declaredType;
        return raw == void.class || raw == Void.class || raw == Void.TYPE;
    }

    Expression instantiateInlineHelperReturn(MethodDeclaration helper,
                                                     List<Expression> callArgs,
                                                     Expression callSite) {
        if (!helper.getBody().isPresent()) {
            return null;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = helper.getBody().get().getStatements();
        if (statements.isEmpty()) {
            return null;
        }
        com.github.javaparser.ast.stmt.Statement terminal = statements.get(statements.size() - 1);
        if (!(terminal instanceof ReturnStmt)) {
            return null;
        }
        ReturnStmt returnStmt = (ReturnStmt) terminal;
        if (!returnStmt.getExpression().isPresent()) {
            return null;
        }
        return materializeInlineHelperBody(helper, statements, callArgs, callSite,
                returnStmt.getExpression().get());
    }

    Expression instantiateInlineHelperExpression(MethodDeclaration helper,
                                                         List<Expression> callArgs,
                                                         Expression callSite) {
        if (!helper.getBody().isPresent()) {
            return null;
        }
        List<com.github.javaparser.ast.stmt.Statement> statements = helper.getBody().get().getStatements();
        if (statements.isEmpty()) {
            return null;
        }
        com.github.javaparser.ast.stmt.Statement terminal = statements.get(statements.size() - 1);
        if (!(terminal instanceof ExpressionStmt)) {
            return null;
        }
        return materializeInlineHelperBody(helper, statements, callArgs, callSite,
                ((ExpressionStmt) terminal).getExpression());
    }

    /**
     * Materializes a (possibly multi-statement) helper body at the call site.
     * Preamble statements (everything before the terminal) are emitted into
     * the test case via {@link #parseStatement} after locals are renamed to
     * avoid collisions and the helper parameter (if any) is substituted with
     * the actual call argument. Returns the rewritten terminal expression for
     * the caller to plug in (typed-null fallback if anything fails, with
     * preamble emissions rolled back).
     */
    private Expression materializeInlineHelperBody(MethodDeclaration helper,
                                                   List<com.github.javaparser.ast.stmt.Statement> statements,
                                                   List<Expression> callArgs,
                                                   Expression callSite,
                                                   Expression terminalExpression) {
        Map<String, Expression> paramSub = buildHelperParamSubstitution(helper, callArgs);
        if (paramSub == null) {
            return null;
        }
        int preambleCount = statements.size() - 1;
        int checkpoint = testCase.size();
        try {
            Map<String, String> localRename = buildHelperLocalRenameMap(
                    statements, preambleCount, helper.getNameAsString());
            for (int i = 0; i < preambleCount; i++) {
                com.github.javaparser.ast.stmt.Statement preamble = statements.get(i);
                com.github.javaparser.ast.stmt.Statement rewritten =
                        rewriteHelperStatement(preamble, localRename, paramSub, callSite);
                parser.parseStatement(rewritten);
            }
            Expression terminal = StatementParser.copySyntheticRange(terminalExpression.clone(), callSite);
            Expression rewrittenTerminal = rewriteHelperExpression(terminal, localRename, paramSub);
            return StatementParser.copySyntheticRange(rewrittenTerminal, callSite);
        } catch (Throwable t) {
            logger.debug("Failed to materialize multi-statement inline helper {}: {}",
                    helper.getNameAsString(), t.getMessage());
            parser.rollbackTemporaryStatements(checkpoint);
            return null;
        }
    }

    /**
     * Returns a substitution map from helper parameter name to actual call
     * argument expression, or {@code null} if the arity does not match.
     */
    private Map<String, Expression> buildHelperParamSubstitution(MethodDeclaration helper,
                                                                 List<Expression> callArgs) {
        if (helper.getParameters().isEmpty()) {
            return Collections.emptyMap();
        }
        if (helper.getParameters().size() != 1 || callArgs.size() != 1) {
            return null;
        }
        Map<String, Expression> map = new LinkedHashMap<>();
        Parameter param = helper.getParameter(0);
        map.put(param.getNameAsString(), callArgs.get(0));
        return map;
    }

    /**
     * Builds a map from each local variable declared in the helper's preamble
     * to a fresh, host-scope-unique name. Subsequent name references and
     * declarators are renamed so that inlined preambles do not collide with
     * existing locals or with previously inlined helper invocations.
     */
    private Map<String, String> buildHelperLocalRenameMap(
            List<com.github.javaparser.ast.stmt.Statement> statements,
            int preambleCount,
            String helperName) {
        Map<String, String> rename = new LinkedHashMap<>();
        for (int i = 0; i < preambleCount; i++) {
            com.github.javaparser.ast.stmt.Statement s = statements.get(i);
            if (!(s instanceof ExpressionStmt)) {
                continue;
            }
            Expression e = ((ExpressionStmt) s).getExpression();
            if (!(e instanceof VariableDeclarationExpr)) {
                continue;
            }
            for (VariableDeclarator d : ((VariableDeclarationExpr) e).getVariables()) {
                String original = d.getNameAsString();
                if (!rename.containsKey(original)) {
                    rename.put(original,
                            parser.nextSyntheticName("__inlined_" + helperName + "_" + original + "_"));
                }
            }
        }
        return rename;
    }

    private com.github.javaparser.ast.stmt.Statement rewriteHelperStatement(
            com.github.javaparser.ast.stmt.Statement original,
            Map<String, String> localRename,
            Map<String, Expression> paramSub,
            Expression callSite) {
        com.github.javaparser.ast.stmt.Statement cloned =
                StatementParser.copySyntheticRange(original.clone(), callSite);
        applyHelperRewrite(cloned, localRename, paramSub);
        return cloned;
    }

    private Expression rewriteHelperExpression(Expression original,
                                                Map<String, String> localRename,
                                                Map<String, Expression> paramSub) {
        Visitable rewritten = applyHelperRewrite(original, localRename, paramSub);
        if (rewritten instanceof Expression) {
            return (Expression) rewritten;
        }
        return original;
    }

    private Visitable applyHelperRewrite(com.github.javaparser.ast.Node node,
                                          Map<String, String> localRename,
                                          Map<String, Expression> paramSub) {
        ModifierVisitor<Void> visitor = new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(NameExpr n, Void arg) {
                String name = n.getNameAsString();
                Expression substitution = paramSub.get(name);
                if (substitution != null) {
                    return StatementParser.copySyntheticRange(substitution.clone(), n);
                }
                String renamed = localRename.get(name);
                if (renamed != null) {
                    n.setName(renamed);
                }
                return super.visit(n, arg);
            }

            @Override
            public Visitable visit(VariableDeclarator d, Void arg) {
                String name = d.getNameAsString();
                String renamed = localRename.get(name);
                if (renamed != null) {
                    d.setName(renamed);
                }
                return super.visit(d, arg);
            }
        };
        return node.accept(visitor, null);
    }

    com.github.javaparser.ast.stmt.Statement inlineHelperCallsInUnsupportedStatement(
            com.github.javaparser.ast.stmt.Statement astStmt) {
        com.github.javaparser.ast.stmt.Statement cloned = StatementParser.copySyntheticRange(astStmt.clone(), astStmt);
        ModifierVisitor<Void> visitor = new ModifierVisitor<Void>() {
            @Override
            public Visitable visit(ExpressionStmt n, Void arg) {
                Expression expr = n.getExpression();
                if (expr instanceof MethodCallExpr) {
                    MethodCallExpr call = (MethodCallExpr) expr;
                    if (!call.getScope().isPresent()) {
                        MethodDeclaration helper = inlineHelperMethods.get(
                                inlineHelperKey(call.getNameAsString(), call.getArguments().size()));
                        if (helper != null) {
                            Expression replacement =
                                    instantiateInlineHelperExpression(helper, call.getArguments(), call);
                            if (replacement == null) {
                                replacement = instantiateInlineHelperReturn(helper, call.getArguments(), call);
                            }
                            if (replacement != null) {
                                return StatementParser.copySyntheticRange(new ExpressionStmt(replacement), n);
                            }
                        }
                    }
                }
                return (ExpressionStmt) super.visit(n, arg);
            }
        };
        Visitable rewritten = cloned.accept(visitor, null);
        if (rewritten instanceof com.github.javaparser.ast.stmt.Statement) {
            return (com.github.javaparser.ast.stmt.Statement) rewritten;
        }
        return cloned;
    }
}
