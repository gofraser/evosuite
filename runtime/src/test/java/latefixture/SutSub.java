package latefixture;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Stands in for a class under test that opens a real file. */
public class SutSub extends SutBase {
    public void touch(File file) throws IOException {
        new RandomAccessFile(file, "rw").close();
    }
}
