package org.maven_test_project.xm;

import java.io.File;

public class ExtensionEnvTarget {

    public static boolean check() {
        File file = new File("thisFileShouldBeMockedInVFS.txt");
        return file.exists();
    }
}
