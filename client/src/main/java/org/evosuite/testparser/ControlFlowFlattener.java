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

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;

/**
 * Flattens the control flow that LLM-written tests wrap around their statements. A try block is
 * replaced by its resources and body statements, and an if statement by its then-branch, because
 * preserving the raw source often leaks undeclared locals or captures non-effectively-final
 * variables that fail javac. Used by {@link StatementParser} in best-effort mode.
 */
final class ControlFlowFlattener {

    private final StatementParser parser;
    private final ParseResult result;

    ControlFlowFlattener(StatementParser parser, ParseResult result) {
        this.parser = parser;
        this.result = result;
    }

    void handleTryStatement(TryStmt tryStmt) {
        if (tryStmt == null) {
            return;
        }
        NodeList<Expression> resources = tryStmt.getResources();
        for (Expression resource : resources) {
            try {
                parser.handleExpressionStatement(resource);
            } catch (Throwable t) {
                int line = resource.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "Failed to parse try-with-resources declaration in LLM try-block; "
                                + "preserving as UninterpretedStatement: "
                                + t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : (": " + t.getMessage())),
                        line,
                        resource.toString()));
                try {
                    parser.addStatement(parser.createUninterpretedStatement(resource, resource.toString() + ";"));
                } catch (Throwable ignored) {
                    // Keep going to salvage subsequent resources/statements in the try block.
                }
            }
        }
        NodeList<com.github.javaparser.ast.stmt.Statement> stmts = tryStmt.getTryBlock().getStatements();
        for (int i = 0; i < stmts.size(); ) {
            com.github.javaparser.ast.stmt.Statement stmt = stmts.get(i);
            try {
                i += parser.parseStatement(stmt, stmts, i);
            } catch (Throwable t) {
                int line = stmt.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "Failed to parse statement inside LLM try-block; preserving as UninterpretedStatement: "
                                + t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : (": " + t.getMessage())),
                        line,
                        stmt.toString()));
                try {
                    parser.addStatement(parser.createUninterpretedStatementFromAst(stmt));
                } catch (Throwable ignored) {
                    // Keep going to salvage subsequent statements in the try block.
                }
                i++;
            }
        }
    }

    void handleIfStatement(IfStmt ifStmt) {
        if (ifStmt == null) {
            return;
        }
        int line = ifStmt.getBegin().map(p -> p.line).orElse(0);
        result.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING,
                "Flattened LLM if-statement to a single branch for compilability",
                line,
                ifStmt.toString()));
        parseFlattenedConditionalBranch(ifStmt.getThenStmt());
    }

    private void parseFlattenedConditionalBranch(com.github.javaparser.ast.stmt.Statement stmt) {
        if (stmt == null) {
            return;
        }
        if (stmt instanceof BlockStmt) {
            NodeList<com.github.javaparser.ast.stmt.Statement> statements =
                    ((BlockStmt) stmt).getStatements();
            for (int i = 0; i < statements.size(); ) {
                i += parser.parseStatement(statements.get(i), statements, i);
            }
            return;
        }
        parser.parseStatement(stmt, null, 0);
    }
}
