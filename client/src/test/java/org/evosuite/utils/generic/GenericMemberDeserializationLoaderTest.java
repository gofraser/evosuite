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
package org.evosuite.utils.generic;

import org.evosuite.TestGenerationContext;
import org.evosuite.rmi.MasterClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A member deserialized while the master class loader is initialized (generation running in the
 * master's JVM) has to belong to the same class as its owner, which is resolved through the SUT
 * class loader. Otherwise a test case holds a method of one class and a callee variable of
 * another class with the same name.
 */
public class GenericMemberDeserializationLoaderTest {

    private URLClassLoader master;

    @BeforeEach
    public void installMasterLoader() {
        // The SUT class loader must not be a child of the master loader installed below.
        MasterClassLoader.clearForTest();
        TestGenerationContext.getInstance().rebindToPreferredParentClassLoader();
        URL testClasses = Person.class.getProtectionDomain().getCodeSource().getLocation();
        master = new URLClassLoader(new URL[]{testClasses}, null);
        MasterClassLoader.setForTest(master);
    }

    @AfterEach
    public void removeMasterLoader() throws Exception {
        MasterClassLoader.clearForTest();
        TestGenerationContext.getInstance().rebindToPreferredParentClassLoader();
        master.close();
    }

    @Test
    public void methodIsResolvedInTheClassOfItsOwner() throws Exception {
        GenericMethod recovered = roundTrip(new GenericMethod(Person.class.getMethod("getName"), Person.class));

        assertSame(recovered.getOwnerClass().getRawClass(), recovered.getDeclaringClass());
        assertNotSame(master, recovered.getDeclaringClass().getClassLoader());
    }

    @Test
    public void constructorIsResolvedInTheClassOfItsOwner() throws Exception {
        GenericConstructor recovered = roundTrip(new GenericConstructor(Person.class.getConstructor(), Person.class));

        assertSame(recovered.getOwnerClass().getRawClass(), recovered.getDeclaringClass());
        assertNotSame(master, recovered.getDeclaringClass().getClassLoader());
    }

    @Test
    public void fieldIsResolvedInTheClassOfItsOwner() throws Exception {
        GenericField recovered = roundTrip(new GenericField(Person.class.getDeclaredField("name"), Person.class));

        assertSame(recovered.getOwnerClass().getRawClass(), recovered.getDeclaringClass());
        assertNotSame(master, recovered.getDeclaringClass().getClassLoader());
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
