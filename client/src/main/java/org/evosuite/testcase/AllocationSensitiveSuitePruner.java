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
package org.evosuite.testcase;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for runtime registrations of allocation-sensitive classes (via
 * {@link StatementFactory#addAllocationSensitiveListener}) and prunes the
 * current {@link Archive}'s stored solutions so they no longer carry tests
 * that exercise the offending class. Without this, after a class is first
 * marked dangerous mid-search the existing best individuals still contain
 * thousands of statements using it; those tests then dominate minimization
 * runtime and may re-trigger OOMs.
 *
 * <p>Pruning strategy: for each archived solution, find the smallest position
 * that constructs the class, calls a method on it, or accesses a field on
 * it, and {@code chop} the test there. Tests that don't touch the class are
 * left alone.</p>
 */
public final class AllocationSensitiveSuitePruner {

    private static final Logger logger = LoggerFactory.getLogger(AllocationSensitiveSuitePruner.class);

    private static final AtomicBoolean registered = new AtomicBoolean(false);

    private static final Consumer<String> LISTENER = AllocationSensitiveSuitePruner::onRegistered;

    private AllocationSensitiveSuitePruner() {
    }

    /**
     * Idempotent. Wires the pruner into {@link StatementFactory}; safe to call
     * once at the start of each search.
     */
    public static void install() {
        if (registered.compareAndSet(false, true)) {
            StatementFactory.addAllocationSensitiveListener(LISTENER);
        }
    }

    /** For tests: unhook the listener and allow re-install. */
    public static void uninstallForTesting() {
        StatementFactory.removeAllocationSensitiveListener(LISTENER);
        registered.set(false);
    }

    private static void onRegistered(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        try {
            pruneArchive(className);
        } catch (Throwable t) {
            logger.warn("Allocation-sensitive pruning failed for {} (non-fatal): {}",
                    className, t.toString());
        }
    }

    private static void pruneArchive(String className) {
        Archive archive = Archive.getArchiveInstance();
        if (archive == null) {
            return;
        }
        Set<TestChromosome> solutions;
        try {
            solutions = archive.getSolutions();
        } catch (Throwable t) {
            // Archive may not be in a queryable state yet
            return;
        }
        if (solutions == null || solutions.isEmpty()) {
            return;
        }
        int chopped = 0;
        int skipped = 0;
        for (TestChromosome tc : solutions) {
            if (tc == null) {
                continue;
            }
            TestCase test = tc.getTestCase();
            if (test == null || test.size() == 0) {
                continue;
            }
            int firstUse = firstStatementUsing(test, className);
            if (firstUse < 0) {
                skipped++;
                continue;
            }
            test.chop(firstUse);
            tc.setChanged(true);
            tc.clearCachedResults();
            chopped++;
        }
        logger.warn("Pruned {} archived test(s) at first use of allocation-sensitive class {} "
                + "(left {} untouched).", chopped, className, skipped);
    }

    /** Package-private for unit testing. */
    static int firstStatementUsing(TestCase test, String className) {
        int size = test.size();
        for (int i = 0; i < size; i++) {
            Statement s = test.getStatement(i);
            if (s == null) {
                continue;
            }
            Class<?> declaring = declaringClassOf(s);
            if (declaring == null) {
                continue;
            }
            for (Class<?> c = declaring; c != null; c = c.getSuperclass()) {
                if (className.equals(c.getName())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Class<?> declaringClassOf(Statement s) {
        if (s instanceof ConstructorStatement) {
            ConstructorStatement cs = (ConstructorStatement) s;
            return cs.getConstructor().getConstructor().getDeclaringClass();
        }
        if (s instanceof MethodStatement) {
            MethodStatement ms = (MethodStatement) s;
            return ms.getMethod().getMethod().getDeclaringClass();
        }
        if (s instanceof FieldStatement) {
            FieldStatement fs = (FieldStatement) s;
            return fs.getField().getField().getDeclaringClass();
        }
        return null;
    }
}
