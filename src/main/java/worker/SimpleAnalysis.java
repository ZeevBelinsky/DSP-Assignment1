package worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Locale;

class SimpleAnalysis {
    static void run(String analysis, Path input, Path out) throws Exception {
        long lines = 0, words = 0, chars = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(input.toFile()))) {
            String s;
            while ((s = br.readLine()) != null) {
                lines++;
                chars += s.length();
                words += s.isBlank() ? 0 : s.trim().split("\\s+").length;
            }
        }
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(out.toFile()))) {
            bw.write(String.format(Locale.US,
                    "%s RESULT\nlines=%d words=%d chars=%d\n", analysis, lines, words, chars));
        }
    }
}
