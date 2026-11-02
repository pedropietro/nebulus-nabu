package io.github.pedropietro.nebulus.demo;

import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;

/** Port of {@code demo/upload.js}: push a buffer and print the gateway URL. */
public class Upload {

    public static void main(String[] args) throws Exception {
        try (Nebulus nebulus = new Nebulus(NebulusOptions.builder().path("storage").build())) {
            nebulus.connect().join();
            nebulus.on("push", cid -> System.out.println("pushed https://ipfs.io/ipfs/" + cid));
            String cid = nebulus.add("die zauberflote xyz".getBytes(StandardCharsets.UTF_8));
            nebulus.push(cid).join();
        }
    }
}
