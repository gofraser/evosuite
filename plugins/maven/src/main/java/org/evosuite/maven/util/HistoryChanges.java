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
package org.evosuite.maven.util;

import org.evosuite.Properties;
import org.evosuite.utils.MD5;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HistoryChanges class.
 *
 * <p>On each execution of EvoSuite-Maven-Plugin this class checks which
 * files have changed since EvoSuite was invoked the last time.</p>
 *
 * <p>How? This class keeps a file with the following format
 * __absolute_file_path__ \t __md5_hash__</p>
 *
 * <p>then, if a file has a different hash, i.e., has been changed, another
 * file called 'history_file' is keep with the following format
 * (Added)A/(Modified)M \t __absolute_file_path__</p>
 *
 * <p>therefore, when EvoSuite-Maven-Plugin is executed with HistorySchedule
 * enabled, the 'history_file' will be used to identify which files have
 * added/modified and perform the schedule.</p>
 *
 * @author José Campos
 */
public class HistoryChanges {

    public static void keepTrack(String basedir, List<File> files) throws Exception {

        File dotEvoSuite = new File(basedir + File.separator + Properties.CTG_DIR);
        if (!dotEvoSuite.exists()) {
            if (!dotEvoSuite.mkdir()) {
                throw new Exception("No permission to create the directory '"
                        + basedir + File.separator + Properties.CTG_DIR + "'");
            }
        }

        File hashFile = new File(basedir + File.separator + Properties.CTG_DIR + File.separator + "hash_file");
        File historyFile = new File(basedir + File.separator + Properties.CTG_DIR + File.separator + "history_file");

        if (!hashFile.exists()) {
            try {
                // create the hash_file <Path, Hash>
                if (!hashFile.createNewFile()) {
                    throw new Exception("No permission to create the file '" + basedir + File.separator
                            + Properties.CTG_DIR + File.separator + "hash_file" + "'");
                }
                // and the history_file as well
                if (!historyFile.createNewFile()) {
                    throw new Exception("No permission to create the file '" + basedir + File.separator
                            + Properties.CTG_DIR + File.separator + "history_file" + "'");
                }

                FileWriter hashFileFw = new FileWriter(hashFile.getAbsoluteFile());
                BufferedWriter hashFileBw = new BufferedWriter(hashFileFw);
                FileWriter historyFileFw = new FileWriter(historyFile.getAbsoluteFile());
                BufferedWriter historyFileBw = new BufferedWriter(historyFileFw);

                // add content to hash_file and to history_file
                for (File file : files) {
                    hashFileBw.write(file.getAbsolutePath() + "\t" + MD5.hash(file) + "\n");
                    historyFileBw.write("A" + "\t" + file.getAbsolutePath() + "\n");
                }

                hashFileBw.close();
                historyFileBw.close();
            } catch (IOException e) {
                throw new Exception("IOException: ", e);
            }
        } else {
            // read content of hash_file
            Map<String, String> hashFileContent = new LinkedHashMap<>();

            try (BufferedReader br = new BufferedReader(new FileReader(hashFile))) {
                String currentLine;
                while ((currentLine = br.readLine()) != null) {
                    String[] split = currentLine.split("\t");
                    hashFileContent.put(split[0], split[1]);
                }
            } catch (IOException e) {
                throw new Exception("reading the content of hash_file ", e);
            }

            try {
                FileWriter hashFileFw = new FileWriter(hashFile.getAbsoluteFile());
                BufferedWriter hashFileBw = new BufferedWriter(hashFileFw);
                hashFileBw.write(""); // clean file

                FileWriter historyFileFw = new FileWriter(historyFile.getAbsoluteFile());
                BufferedWriter historyFileBw = new BufferedWriter(historyFileFw);
                historyFileBw.write(""); // clean file

                // compare each hash
                for (File file : files) {
                    String hash = MD5.hash(file);
                    hashFileBw.write(file.getAbsolutePath() + "\t" + hash + "\n");

                    if (!hashFileContent.containsKey(file.getAbsolutePath())) {
                        historyFileBw.write("A" + "\t" + file.getAbsolutePath() + "\n");
                    } else if (!hashFileContent.get(file.getAbsolutePath()).equals(hash)) {
                        historyFileBw.write("M" + "\t" + file.getAbsolutePath() + "\n");
                    }
                }

                hashFileBw.close();
                historyFileBw.close();
            } catch (IOException e) {
                throw new Exception("IOException: ", e);
            }
        }
    }
}
