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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.setup.callgraph.CallGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * Regenerating a stale cached inheritance tree used to leave the whole run without a call graph:
 * the regeneration dropped the cached call graphs but, unlike the equivalent recovery inside
 * {@link DependencyAnalysis#analyzeClass}, never rebuilt the one for the target class.
 */
public class DependencyAnalysisRegenerationTest {

    private static final String TARGET_CLASS = java.util.ArrayList.class.getName();

    private final String defaultTargetClass = Properties.TARGET_CLASS;
    private final String defaultInheritanceFile = Properties.INHERITANCE_FILE;
    private final Criterion[] defaultCriteria = Properties.CRITERION;
    private final boolean defaultInstrumentContext = Properties.INSTRUMENT_CONTEXT;
    private final boolean defaultInstrumentParent = Properties.INSTRUMENT_PARENT;

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.INHERITANCE_FILE = defaultInheritanceFile;
        Properties.CRITERION = defaultCriteria;
        Properties.INSTRUMENT_CONTEXT = defaultInstrumentContext;
        Properties.INSTRUMENT_PARENT = defaultInstrumentParent;
        DependencyAnalysis.clear();
    }

    /**
     * Builds the minimal state the regeneration needs: a cached inheritance tree (built from an
     * empty classpath, so only the bundled JDK data is read - this test is about the call graph,
     * not about the contents of the tree) plus a call graph for the target class.
     */
    private void initAnalysisState() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        // Neither IBRANCH nor instrument_context, so that initCallGraph does not walk every class
        // of the tree.
        Properties.CRITERION = new Criterion[]{Criterion.CBRANCH};
        Properties.INSTRUMENT_CONTEXT = false;
        Properties.INSTRUMENT_PARENT = false;
        Properties.INHERITANCE_FILE = "";
        DependencyAnalysis.clear();
        // initCallGraph() resolves classes through the target project classpath. Left unset it
        // defaults to the working directory, which ResourceList then rejects - and whether it is
        // already set depends on which other test class ran first in this JVM. Pin it here so the
        // test does not depend on execution order.
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(
                System.getProperty("user.dir") + "/target/test-classes");
        DependencyAnalysis.initInheritanceTree(Collections.emptyList());
        DependencyAnalysis.initCallGraph(TARGET_CLASS);
    }

    @Test
    public void regenerationLeavesCallGraphForTargetClass() {
        initAnalysisState();
        CallGraph before = DependencyAnalysis.getCallGraph();
        Assertions.assertNotNull(before);

        // What ConcreteClassAnalyzer does when it hits a class the cached tree knows but the
        // running JVM does not.
        DependencyAnalysis.getInheritanceTree().markStale();
        Properties.INHERITANCE_FILE = "stale-inheritance-tree.xml.gz";

        Assertions.assertTrue(DependencyAnalysis.regenerateInheritanceTreeIfStale());
        Assertions.assertEquals("", Properties.INHERITANCE_FILE);

        CallGraph after = DependencyAnalysis.getCallGraph();
        Assertions.assertNotNull(after, "regeneration must leave a call graph for the target class");
        Assertions.assertNotSame(before, after, "the stale call graph must be rebuilt, not reused");
        Assertions.assertEquals(TARGET_CLASS, after.getClassName());
    }

    @Test
    public void noRegenerationWhenTreeIsNotStale() {
        initAnalysisState();
        CallGraph before = DependencyAnalysis.getCallGraph();
        Properties.INHERITANCE_FILE = "stale-inheritance-tree.xml.gz";

        Assertions.assertFalse(DependencyAnalysis.regenerateInheritanceTreeIfStale());
        Assertions.assertSame(before, DependencyAnalysis.getCallGraph());
    }

    @Test
    public void regenerationWithoutTargetClassStillRefreshesTree() {
        initAnalysisState();
        // analyzeTarget() clears TARGET_CLASS between classes; the rebuild must not blow up on it.
        Properties.TARGET_CLASS = "";
        DependencyAnalysis.getInheritanceTree().markStale();
        Properties.INHERITANCE_FILE = "stale-inheritance-tree.xml.gz";

        Assertions.assertTrue(DependencyAnalysis.regenerateInheritanceTreeIfStale());
        Assertions.assertNotNull(DependencyAnalysis.getInheritanceTree());
        Assertions.assertFalse(DependencyAnalysis.getInheritanceTree().hasMissingClasses());
    }
}
