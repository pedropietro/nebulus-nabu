package io.github.pedropietro.nebulus.demo;

import io.github.pedropietro.nebulus.Nebulus;

import java.nio.charset.StandardCharsets;

/** Port of {@code demo/helloworld.js}: add privately, then push and wait for the filtered event. */
public class HelloWorld {

    public static void main(String[] args) throws Exception {
        try (Nebulus n = new Nebulus()) {
            String cid = n.add("hello world".getBytes(StandardCharsets.UTF_8));
            System.out.println("cid = " + cid);
            n.connect().join();
            n.on("push:bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e",
                    pushed -> System.out.println("pushed " + pushed));
            n.push(cid).join();
        }
    }
}
