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
package org.evosuite.classpath;

import com.examples.with.different.packagename.classpath.Foo;
import com.examples.with.different.packagename.classpath.subp.SubPackageFoo;
import org.evosuite.TestGenerationContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;

public class ResourceListTest {

    private static final String basePrefix = "com.examples.with.different.packagename.classpath";

    @BeforeAll
    public static void initClass() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @BeforeEach
    public void resetCache() {
        ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).resetCache();
    }

    //-------------------------------------------------------------------------------------------------

    @Test
    public void testGetPackageName() {
        Assertions.assertEquals("", ResourceList.getParentPackageName(""));
        Assertions.assertEquals("", ResourceList.getParentPackageName("foo"));
        Assertions.assertEquals("foo", ResourceList.getParentPackageName("foo.bar"));
        Assertions.assertEquals("bar.foo", ResourceList.getParentPackageName("bar.foo.evo"));
    }

    @Test
    public void testStreamFromFolder() throws Exception {
        File localFolder = new File("local_test_data" + File.separator + "aCpEntry");
        Assertions.assertTrue(localFolder.exists(), "ERROR: file " + localFolder + " should be available on local file system");
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(localFolder.getAbsolutePath());

        String className = "foo.ExternalClass";
        InputStream stream = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(className);
        Assertions.assertNotNull(stream);
        stream.close();
    }


    @Test
    public void testStreamFromJar() throws Exception {
        File localJar = new File("local_test_data" + File.separator + "water-simulator.jar");
        Assertions.assertTrue(localJar.exists(), "ERROR: file " + localJar + " should be avaialable on local file system");
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(localJar.getAbsolutePath());

        String className = "simulator.DAWN";
        InputStream stream = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(className);
        Assertions.assertNotNull(stream);
        stream.close();
    }

    @Test
    public void testHandleUnKnownJarFile() {

        File localJar = new File("local_test_data" + File.separator + "water-simulator.jar");
        Assertions.assertTrue(localJar.exists(), "ERROR: file " + localJar + " should be avaialable on local file system");
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(localJar.getAbsolutePath());

        String prefix = "simulator";
        String target = prefix + ".DAWN";

        Assertions.assertTrue(ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).hasClass(target), "Missing: " + target);

        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), prefix, false);
        Assertions.assertTrue(classes.contains(target));
    }

    @Test
    public void testHandleKnownJarFile() {

        File localJar = new File("local_test_data" + File.separator + "asm-all-4.2.jar");
        Assertions.assertTrue(localJar.exists(), "ERROR: file " + localJar + " should be avaialable on local file system");
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(localJar.getAbsolutePath());

        // we use one class among the jars EvoSuite depends on
        String target = org.objectweb.asm.util.ASMifier.class.getName();
        String prefix = org.objectweb.asm.util.ASMifier.class.getPackage().getName();

        Assertions.assertTrue(ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).hasClass(target), "Missing: " + target);

        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), prefix, false);
        Assertions.assertTrue(classes.contains(target));
    }

    @Test
    public void testHasClass() {
        Assertions.assertTrue(ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).hasClass(Foo.class.getName()));
        Assertions.assertTrue(ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).hasClass(SubPackageFoo.class.getName()));
    }


    @Test
    public void testSubPackage() {
        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), basePrefix, false);
        Assertions.assertTrue(classes.contains(Foo.class.getName()));
        Assertions.assertTrue(classes.contains(SubPackageFoo.class.getName()));

        classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), basePrefix + ".subp", false);
        Assertions.assertFalse(classes.contains(Foo.class.getName()));
        Assertions.assertTrue(classes.contains(SubPackageFoo.class.getName()));
    }

    @Test
    public void testGatherClassNoInternal() {
        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), basePrefix, false);
        Assertions.assertTrue(classes.contains(Foo.class.getName()));
        Assertions.assertFalse(classes.contains(Foo.InternalFooClass.class.getName()));
        Assertions.assertEquals(2, classes.size()); //there is also SubPFoo
    }

    @Test
    public void testGatherClassWithInternalButNoAnonymous() {
        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), basePrefix, true);
        Assertions.assertTrue(classes.contains(Foo.class.getName()));
        Assertions.assertTrue(classes.contains(Foo.InternalFooClass.class.getName()), "" + Arrays.toString(classes.toArray()));
        Assertions.assertEquals(3, classes.size());//there is also SubPFoo
    }

    @Test
    public void testGatherClassWithInternalIncludingAnonymous() {
        Collection<String> classes = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getAllClasses(
                ClassPathHandler.getInstance().getTargetProjectClasspath(), basePrefix, true, false);
        Assertions.assertTrue(classes.contains(Foo.class.getName()));
        Assertions.assertTrue(classes.contains(Foo.InternalFooClass.class.getName()), "" + Arrays.toString(classes.toArray()));
        Assertions.assertEquals(4, classes.size());//there is also SubPFoo
    }


    @Test
    public void testLoadOfEvoSuiteTestClassesAsStream() throws IOException {
        String className = ResourceListFoo.class.getName();
        InputStream res = ResourceList.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getClassAsStream(className);
        Assertions.assertNotNull(res);
        res.close();
    }


    private class ResourceListFoo {
    }

    ;

}
