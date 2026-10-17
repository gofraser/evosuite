package latefixture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Stands in for a class under test whose static initializer opens a real file. */
public class StaticInitSut extends SutBase {
    public static final String STATE;

    static {
        String state;
        try {
            new FileOutputStream("static_init_output.txt").close();
            state = "written";
        } catch (Throwable t) {
            state = t.getClass().getName();
        }
        STATE = state;
    }

    public void touch(File file) throws IOException {
        new RandomAccessFile(file, "rw").close();
    }
}
