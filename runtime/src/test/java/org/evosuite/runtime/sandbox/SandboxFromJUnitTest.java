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
package org.evosuite.runtime.sandbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("removal")
public class SandboxFromJUnitTest {

    private static ExecutorService executor;

    @BeforeAll
    public static void initEvoSuiteFramework() {
        if (Sandbox.isSecurityManagerSupported()) {
            Assertions.assertNull(System.getSecurityManager());
        }

        Sandbox.initializeSecurityManagerForSUT();
        executor = Executors.newCachedThreadPool();

    }

    @AfterAll
    public static void clearEvoSuiteFramework() {
        if (Sandbox.isSecurityManagerSupported()) {
            Assertions.assertNotNull(System.getSecurityManager());
        }

        executor.shutdownNow();
        Sandbox.resetDefaultSecurityManager();

        if (Sandbox.isSecurityManagerSupported()) {
            Assertions.assertNull(System.getSecurityManager());
        }
    }

    @BeforeEach
    public void initTest() {
        Sandbox.goingToExecuteSUTCode();
        //TestGenerationContext.getInstance().goingToExecuteSUTCode();
    }

    @AfterEach
    public void doneWithTestCase() {
        Sandbox.doneWithExecutingSUTCode();
    }


    @Test
    public void testExit() throws Exception {
        // Skip test if Security Manager is not supported (Java 24+)
        if (!Sandbox.isSecurityManagerSupported()) {
            System.out.println("Skipping testExit: Security Manager not supported in this JVM (Java 24+)");
            return;
        }

        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                //-------
                Foo foo = new Foo();
                try {
                    foo.tryToExit();
                    Assertions.fail();
                } catch (SecurityException e) {
                    //expected
                }
                //-------
            }
        });
        future.get(5000, TimeUnit.MILLISECONDS);

    }

}


class Foo {

    public void tryToExit() {
        System.exit(0);
    }
}


