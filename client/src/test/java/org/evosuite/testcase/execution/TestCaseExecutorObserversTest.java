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
package org.evosuite.testcase.execution;

import org.evosuite.testcase.statements.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestCaseExecutorObserversTest {

    private static class FirstObserver extends ExecutionObserver {
        @Override
        public void afterStatement(Statement statement, Scope scope, Throwable exception) {
        }
    }

    private static class SecondObserver extends ExecutionObserver {
        @Override
        public void afterStatement(Statement statement, Scope scope, Throwable exception) {
        }
    }

    private Set<ExecutionObserver> saved;

    @BeforeEach
    public void saveObservers() {
        TestCaseExecutor executor = TestCaseExecutor.getInstance();
        saved = executor.getExecutionObservers();
        executor.setExecutionObservers(new LinkedHashSet<>());
    }

    @AfterEach
    public void restoreObservers() {
        TestCaseExecutor.getInstance().setExecutionObservers(saved);
    }

    @Test
    public void addsOnlyWhenNoObserverOfTheClassIsRegistered() {
        TestCaseExecutor executor = TestCaseExecutor.getInstance();
        AtomicInteger created = new AtomicInteger();
        FirstObserver first = new FirstObserver();

        executor.addObserverIfAbsent(FirstObserver.class, () -> {
            created.incrementAndGet();
            return first;
        });
        executor.addObserverIfAbsent(FirstObserver.class, () -> {
            created.incrementAndGet();
            return new FirstObserver();
        });

        assertEquals(1, created.get());
        assertEquals(1, executor.getExecutionObservers().size());
        assertSame(first, executor.getExecutionObservers().iterator().next());
    }

    @Test
    public void keepsRegistrationOrder() {
        TestCaseExecutor executor = TestCaseExecutor.getInstance();
        executor.addObserverIfAbsent(SecondObserver.class, SecondObserver::new);
        executor.addObserverIfAbsent(FirstObserver.class, FirstObserver::new);
        executor.addObserverIfAbsent(SecondObserver.class, SecondObserver::new);

        List<Class<?>> classes = new ArrayList<>();
        for (ExecutionObserver observer : executor.getExecutionObservers()) {
            classes.add(observer.getClass());
        }
        assertEquals(List.of(SecondObserver.class, FirstObserver.class), classes);
    }
}
