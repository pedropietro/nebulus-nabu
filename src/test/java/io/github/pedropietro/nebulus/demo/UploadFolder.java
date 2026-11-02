package io.github.pedropietro.nebulus.demo;

import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Port of {@code demo/uploadFolder.js}: build a folder offline, push it, then disconnect. */
public class UploadFolder {

    public static void main(String[] args) throws Exception {
        try (Nebulus nebulus = new Nebulus(NebulusOptions.builder().path("storage").build())) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("hello.txt", nebulus.add("hello xyz".getBytes(StandardCharsets.UTF_8)));
            files.put("world.txt", nebulus.add("world xyz".getBytes(StandardCharsets.UTF_8)));
            String cid = nebulus.folder(files);

            System.out.println("connect to ipfs...");
            nebulus.connect().join();
            System.out.println("push " + cid);
            nebulus.on("push", pushed -> System.out.println("uploaded " + pushed));
            nebulus.push(cid).join();

            System.out.println("disconnecting from ipfs...");
            nebulus.disconnect().join();
            System.out.println("disconnected");
        }
    }
}
