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
package org.evosuite.runtime.vfs;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.runtime.mock.java.io.MockFileInputStream;
import org.evosuite.runtime.mock.java.io.MockFileOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

public class VirtualFileSystemTest {

    @BeforeEach
    public void init() {
        MockFramework.enable();
        VirtualFileSystem.getInstance().resetSingleton();
        VirtualFileSystem.getInstance().init();
    }

    @AfterEach
    public void tearDown() {
        VirtualFileSystem.getInstance().resetSingleton();
    }


    @Test
    public void testTokenizeOnWindows() {
        String[] paths = new String[]{
                "C:\\foo\\single",
                "C:\\\\foo\\\\double",
                "C:\\foo\\\\mixed",
                "D:\\foo\\onD",
                "D:\\foo\\trail\\",
                "D:\\foo\\doubleTrail\\\\",
                "D:\\\\\\\\foo\\eight"
        };
        for (String path : paths) {
            String[] tokens = VirtualFileSystem.tokenize(path, '\\');
            Assertions.assertEquals(3, tokens.length, Arrays.toString(tokens));
            for (String token : tokens) {
                Assertions.assertFalse(token.contains("\\"), token);
            }
        }
    }

    @Test
    public void testNoAccessByDefault() {
        Assertions.assertEquals(0, VirtualFileSystem.getInstance().getAccessedFiles().size());
    }

    @Test
    public void testRename() throws IOException {
        File bla = new MockFile("bla");
        File doh = new MockFile("doh");
        Assertions.assertFalse(bla.exists());
        Assertions.assertFalse(doh.exists());

        boolean created = bla.createNewFile();
        Assertions.assertTrue(created);
        Assertions.assertTrue(bla.exists());
        Assertions.assertFalse(doh.exists());

        boolean renamed = bla.renameTo(doh);
        Assertions.assertTrue(renamed);
        Assertions.assertFalse(bla.exists());
        Assertions.assertTrue(doh.exists());

        File inAnotherFolder = new MockFile("foo/hei/hello.tmp");
        Assertions.assertFalse(inAnotherFolder.exists());
        renamed = doh.renameTo(inAnotherFolder);
        Assertions.assertFalse(renamed);
        Assertions.assertFalse(inAnotherFolder.exists());
        Assertions.assertTrue(doh.exists());

        File de = new MockFile("deeee");
        File blup = new MockFile("blup");
        Assertions.assertFalse(de.exists());
        Assertions.assertFalse(blup.exists());
        renamed = de.renameTo(blup);
        Assertions.assertFalse(renamed);
        Assertions.assertFalse(de.exists());
        Assertions.assertFalse(blup.exists());
    }

    @Test
    public void testReadAfterWriteToFile() throws IOException {

        File file = MockFile.createTempFile("foo", ".tmp");
        Assertions.assertTrue(file.exists());

        byte[] data = new byte[]{42, 66};
        MockFileOutputStream out = new MockFileOutputStream(file);
        out.write(data);
        out.close();

        MockFileInputStream in = new MockFileInputStream(file);
        byte[] buffer = new byte[4];
        int count = in.read(buffer);
        in.close();
        Assertions.assertEquals(data.length, count, "End of stream should had been reached");
        Assertions.assertEquals(data[0], buffer[0]);
        Assertions.assertEquals(data[1], buffer[1]);
        Assertions.assertEquals(0, buffer[2]);
        Assertions.assertEquals(0, buffer[3]);
    }

    @Test
    public void testReadingNonExistingFile() throws IOException {
        String fileName = "this_file_should_not_exist";
        File realFile = new File(fileName);
        Assertions.assertFalse(realFile.exists());

        try {
            MockFileInputStream in = new MockFileInputStream(realFile);
            Assertions.fail(); //real file does not exist
        } catch (FileNotFoundException e) {
        }

        File mockFile = new MockFile(fileName);
        Assertions.assertFalse(mockFile.exists());

        try {
            MockFileInputStream in = new MockFileInputStream(mockFile);
            Assertions.fail(); // also the mock file does not exist (yet)
        } catch (FileNotFoundException e) {
        }

        boolean created = mockFile.createNewFile();
        Assertions.assertTrue(created);
        Assertions.assertTrue(mockFile.exists());
        Assertions.assertFalse(realFile.exists()); //real file shouldn's have been created

        //following should work even if real file does not exist
        MockFileInputStream in = new MockFileInputStream(mockFile);
    }

    @Test
    public void testWriteToFile() throws IOException {

        String fileName = "foo_written_with_FOS";
        File realFile = new File(fileName);
        realFile.deleteOnExit(); // be sure to get it deleted in case we accidently create it
        Assertions.assertFalse(realFile.exists());

        File file = new MockFile(fileName);
        Assertions.assertFalse(file.exists());

        byte[] data = new byte[]{42};
        MockFileOutputStream out = new MockFileOutputStream(file);
        out.write(data);

        //writing to such file should create it
        Assertions.assertTrue(file.exists());

        out.close();
        try {
            out.write(data);
            Assertions.fail();
        } catch (Exception e) {
            //this is expected, as the stream is closed
        }

        //be sure that no real file was created
        Assertions.assertFalse(realFile.exists());
    }

    @Test
    public void testTmpFileCreation() throws IOException {

        File file = MockFile.createTempFile("foo", ".tmp");
        Assertions.assertTrue(file.exists());
        String path = file.getAbsolutePath();
        java.lang.System.out.println(path);
        Assertions.assertTrue(path.contains("foo") & path.contains(".tmp"), path);
    }

    @Test
    public void testWorkingDirectoryExists() {
        MockFile workingDir = new MockFile(java.lang.System.getProperty("user.dir"));
        Assertions.assertTrue(workingDir.exists());
    }

    @Test
    public void testCreateDeleteFileDirectly() throws IOException {

        MockFile file = new MockFile("foo");
        Assertions.assertFalse(file.exists());
        boolean created = file.createNewFile();
        Assertions.assertTrue(created);
        Assertions.assertTrue(file.exists());
        boolean deleted = file.delete();
        Assertions.assertTrue(deleted);
        Assertions.assertFalse(file.exists());
    }


    @Test
    public void testCreateDeleteFolderDirectly() throws IOException {

        MockFile folder = new MockFile("foo" + File.separator + "hello");
        Assertions.assertFalse(folder.exists());
        boolean created = folder.mkdir(); // parent doesn't exist, so should fail
        Assertions.assertFalse(created);
        Assertions.assertFalse(folder.exists());

        created = folder.mkdirs();
        Assertions.assertTrue(created);
        Assertions.assertTrue(folder.exists());

        MockFile file = new MockFile(folder.getAbsoluteFile() + File.separator + "evo");
        created = file.createNewFile();
        Assertions.assertTrue(created);
        Assertions.assertTrue(file.exists());

        //deleting non-empty folder should fail
        boolean deleted = folder.delete();
        Assertions.assertFalse(deleted);
        Assertions.assertTrue(folder.exists());

        deleted = file.delete();
        Assertions.assertTrue(deleted);
        Assertions.assertFalse(file.exists());

        //now we can delete the folder
        deleted = folder.delete();
        Assertions.assertTrue(deleted);
        Assertions.assertFalse(folder.exists());
    }

}
