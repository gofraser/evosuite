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
package org.evosuite.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Scanner;

/**
 * Created by Andrea Arcuri on 07/06/15.
 */
public class FileIOUtilsTest {

    @Test
    public void testCopyDirectoryAndOverwriteFilesIfNeeded() throws Exception {

        File tmpRoot = File.createTempFile("foo" + System.currentTimeMillis(), "");
        tmpRoot.delete();
        tmpRoot.mkdirs();
        tmpRoot.deleteOnExit();

        File src = new File(tmpRoot, "src");
        src.mkdirs();
        src.deleteOnExit();
        File a = new File(src, "a.txt");
        a.deleteOnExit();
        a.createNewFile();
        File b = new File(src, "b.txt");
        b.deleteOnExit();
        b.createNewFile();
        File folder = new File(src, "folder");
        folder.mkdirs();
        folder.deleteOnExit();
        File c = new File(folder, "c.txt");
        c.deleteOnExit();
        c.createNewFile();

        File dest = new File(tmpRoot, "dest");
        File destA = new File(dest, "a.txt");
        File destB = new File(dest, "b.txt");
        File destC = new File(new File(dest, "folder"), "c.txt");
        Assertions.assertFalse(destA.exists());
        Assertions.assertFalse(destB.exists());
        Assertions.assertFalse(destC.exists());


        FileIOUtils.copyDirectoryAndOverwriteFilesIfNeeded(src, dest);

        Assertions.assertTrue(destA.exists());
        Assertions.assertTrue(destB.exists());
        Assertions.assertTrue(destC.exists());
        Assertions.assertEquals(a.lastModified(), destA.lastModified());
        Assertions.assertEquals(b.lastModified(), destB.lastModified());
        Assertions.assertEquals(c.lastModified(), destC.lastModified());

        Thread.sleep(1000); // be sure time stamps ll be different

        boolean deleted = c.delete();
        Assertions.assertTrue(deleted);

        c.createNewFile();
        c.deleteOnExit();

        FileWriter out = new FileWriter(c);
        String line = "foo bar";
        out.write(line + "\n");
        out.close();

        Assertions.assertNotEquals(c.lastModified(), destC.lastModified());

        FileIOUtils.copyDirectoryAndOverwriteFilesIfNeeded(src, dest);

        Assertions.assertEquals(c.lastModified(), destC.lastModified());
        Scanner in = new Scanner(new FileReader(destC));
        String read = in.nextLine();
        in.close();
        Assertions.assertEquals(line, read);
    }
}