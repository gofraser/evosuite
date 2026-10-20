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
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nearest package-distance band of java.security.Principal holds only the abstract
 * IdentityScope (usable through its static getSystemScope()). That must not hide the real
 * implementations in farther packages.
 */
public class ConcreteClassAnalyzerAbstractBandTest {

    private InheritanceTree tree;

    @BeforeEach
    public void setUp() {
        Properties.TARGET_CLASS = ConcreteClassAnalyzerAbstractBandTest.class.getName();
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        tree = new InheritanceTree();
        tree.registerInterface("java.security.Principal");
        tree.addSuperclass("java.security.IdentityScope", "java.security.Identity", 0);
        tree.addInterface("java.security.Identity", "java.security.Principal");
        tree.addInterface("javax.security.auth.x500.X500Principal", "java.security.Principal");
        ConcreteClassAnalyzer.getInstance().clear();
    }

    @AfterEach
    public void tearDown() {
        ConcreteClassAnalyzer.getInstance().clear();
    }

    @Test
    public void realImplementationIsFoundBehindAnAbstractOnlyBand() {
        Set<Class<?>> classes = ConcreteClassAnalyzer.getInstance().getConcreteClasses(Principal.class, tree);
        assertTrue(classes.contains(javax.security.auth.x500.X500Principal.class), classes.toString());
    }
}
