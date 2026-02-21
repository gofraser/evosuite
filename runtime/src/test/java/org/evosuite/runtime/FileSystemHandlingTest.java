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
package org.evosuite.runtime;

import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.runtime.mock.java.io.MockFileInputStream;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.runtime.testdata.FileSystemHandling;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Scanner;

public class FileSystemHandlingTest {

    private static final boolean VFS = RuntimeSettings.useVFS;

    @AfterEach
    public void restoreProperties() {
        RuntimeSettings.useVFS = VFS;
    }

    @Test
    public void createNewFileByAddingData() throws IOException {

        RuntimeSettings.useVFS = true;
        Runtime.getInstance().resetRuntime();

        byte[] data = new byte[]{42, 66};

        EvoSuiteFile file = new EvoSuiteFile("foo");
        MockFile mf = new MockFile(file.getPath());
        Assertions.assertFalse(mf.exists());

        FileSystemHandling.appendDataToFile(file, data);

        Assertions.assertTrue(mf.exists());

        MockFileInputStream in = new MockFileInputStream(file.getPath());
        byte[] buffer = new byte[4];
        int count = in.read(buffer);
        in.close();
        Assertions.assertEquals(data.length, count);
        Assertions.assertEquals(data[0], buffer[0]);
        Assertions.assertEquals(data[1], buffer[1]);
        Assertions.assertEquals(0, buffer[2]);
        Assertions.assertEquals(0, buffer[3]);
    }

    @Test
    public void createNewFileByAddingLine() throws IOException {

        RuntimeSettings.useVFS = true;
        Runtime.getInstance().resetRuntime();

        String data = "A new line to be added";

        EvoSuiteFile file = new EvoSuiteFile("foo");
        MockFile mf = new MockFile(file.getPath());
        Assertions.assertFalse(mf.exists());

        FileSystemHandling.appendStringToFile(file, data);

        Assertions.assertTrue(mf.exists());

        //try read bytes directly
        MockFileInputStream in = new MockFileInputStream(file.getPath());
        byte[] buffer = new byte[1024];
        in.read(buffer);
        in.close();
        String byteString = new String(buffer);
        Assertions.assertTrue(byteString.startsWith(data), "Read: " + byteString);

        //try with InputStreamReader
        InputStreamReader reader = new InputStreamReader(new MockFileInputStream(file.getPath()));
        char[] cbuf = new char[1024];
        reader.read(cbuf);
        reader.close();
        String charString = new String(cbuf);
        Assertions.assertTrue(charString.startsWith(data), "Read: " + charString);

        //try BufferedReader
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new MockFileInputStream(file.getPath())));
        cbuf = new char[1024];
        bufferedReader.read(cbuf);
        bufferedReader.close();
        charString = new String(cbuf);
        Assertions.assertTrue(charString.startsWith(data), "Read: " + charString);


        //try with Scanner
        Scanner fromFile = new Scanner(new MockFileInputStream(file.getPath()));
        String fileContent = fromFile.nextLine();
        fromFile.close();

        Assertions.assertEquals(data, fileContent);
    }

}
