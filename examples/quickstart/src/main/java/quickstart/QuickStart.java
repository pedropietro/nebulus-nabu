package quickstart;

import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Quick Start, steps 1-4: everything offline. No IPFS node is started and nothing is published.
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=quickstart.QuickStart</pre>
 */
public class QuickStart {

    public static void main(String[] args) throws Exception {
        NebulusOptions options = NebulusOptions.builder()
                .path("quickstart-storage")
                .build();

        try (Nebulus nebulus = new Nebulus(options)) {
            // 1. Add bytes: the CID is computed locally
            String hello = nebulus.add("hello world".getBytes(StandardCharsets.UTF_8));
            System.out.println("hello world  -> " + hello);

            // 2. Add a file: Nebulus links to it instead of copying it
            Path note = Files.writeString(Path.of("quickstart-storage", "note.txt"), "Nebulus keeps this private");
            String noteCid = nebulus.add(note);
            System.out.println("note.txt     -> " + noteCid);

            // 3. Build a folder from CIDs already in Nebulus
            String folder = nebulus.folder(Map.of(
                    "hello.txt", hello,
                    "note.txt", noteCid));
            System.out.println("folder       -> " + folder + " " + nebulus.list(folder));

            // 4. Read it back, by CID or by folder path
            System.out.println("note.txt says: " + nebulus.get(folder + "/note.txt", StandardCharsets.UTF_8));
        }
    }
}
