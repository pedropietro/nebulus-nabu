# Quick Start

🇧🇷 [Leia em português](quickstart.pt-BR.md) · ← [README](../README.md)

This guide takes about 10 minutes. You will:

1. Add the dependency.
2. Create CIDs offline and read them back.
3. Share a file between two nodes on your machine.
4. Publish to the public IPFS network.

Every snippet here is also a runnable program in [`examples/quickstart`](../examples/quickstart).
CI runs those programs on every commit.

## Prerequisites

- **Java 17** or newer (`java -version`).
- **Maven 3.6+**, or Gradle.
- Internet access to download dependencies. Only step 4 needs the public IPFS network.

## 1. Add the dependency

Create a Maven project, or use [`examples/quickstart/pom.xml`](../examples/quickstart/pom.xml),
and add JitPack and nebulus-nabu:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.pedropietro</groupId>
    <artifactId>nebulus-nabu</artifactId>
    <version>main-SNAPSHOT</version>
  </dependency>
</dependencies>
```

With Gradle:

```groovy
repositories { mavenCentral(); maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.pedropietro:nebulus-nabu:main-SNAPSHOT' }
```

> The first build of a new version on JitPack can take a few minutes, and the first request may
> fail with HTTP 502 while it builds. If that happens, wait and run the build again.

## 2. Your first CIDs (offline)

Nothing in this step starts an IPFS node or sends anything over the network.

```java
import io.github.pedropietro.nebulus.Nebulus;
import io.github.pedropietro.nebulus.NebulusOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
```

Run it:

```bash
cd examples/quickstart
mvn -q compile exec:java -Dexec.mainClass=quickstart.QuickStart
```

The output is the same on every machine, because a CID depends only on the content:

```
hello world  -> bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e
note.txt     -> bafkreic4cqoc3p5wc6avrsyuejtjuxnztwpyhlhvs7i2u23jnveldjotgq
folder       -> bafybeif4ubfbkzlur7gc2xn4gazuyugmxozdftyne3xalzqku6olwgpwdi [hello.txt, note.txt]
note.txt says: Nebulus keeps this private
```

These are real IPFS CIDs. `ipfs add --cid-version=1 --raw-leaves` produces the same ones, and
so does the JavaScript Nebulus. Here is what was written to disk:

```
quickstart-storage/
├── note.txt                                      your file, untouched
├── src/b94d27b9…cde9                             the "hello world" bytes, named by sha256
└── ipfs/
    ├── bafkreifzjut3…36n5e  → ../src/b94d27b9…   one symbolic link per CID
    ├── bafkreic4cqoc…jotgq  → ../note.txt
    └── bafybeif4ubf…wgpwdi/                      the folder
        ├── hello.txt        → ../bafkreifzjut3…
        └── note.txt         → ../bafkreic4cqoc…
```

Things to try:

- **Download from a URL:** `nebulus.add("https://example.com/logo.png")`. You can limit the
  size with `.maxMegabytes(10)` in the options.
- **Stream large content:** `try (InputStream in = nebulus.stream(cid)) { … }`
- **Run the program twice.** Adding the same content again returns the same CID and creates no
  duplicates.

## 3. Share between two nodes on your machine

Now start two embedded Nabu nodes, Alice and Bob. Alice publishes a file and Bob fetches it over
Bitswap. Alice has no bootstrap peers and Bob only knows Alice, so nothing leaves your machine.

```java
Logger.getGlobal().setLevel(Level.WARNING); // Nabu logs to the global logger

Nebulus alice = new Nebulus(NebulusOptions.builder()
        .path("quickstart-alice")
        .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/4101"))
        .bootstrapAddresses(List.of())   // stay off the public network
        .verifyOnGateway(false)          // no public gateway to wait for
        .build());
alice.connect().join();
String aliceAddress = "/ip4/127.0.0.1/tcp/4101/p2p/" + alice.peerId().orElseThrow();

Nebulus bob = new Nebulus(NebulusOptions.builder()
        .path("quickstart-bob")
        .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/4102"))
        .bootstrapAddresses(List.of(aliceAddress))
        .verifyOnGateway(false)
        .build());

try (alice; bob) {
    String cid = alice.add("a private hello from Alice".getBytes(StandardCharsets.UTF_8));
    alice.push(cid).join();

    bob.on("pull", pulled -> System.out.println("Bob pulled  " + pulled));
    bob.pull(cid).join();
    System.out.println("Bob reads:   " + bob.get(cid, StandardCharsets.UTF_8));
}
```

```bash
mvn -q compile exec:java -Dexec.mainClass=quickstart.LocalNetwork
```

```
Alice is /ip4/127.0.0.1/tcp/4101/p2p/12D3KooW…
Alice pushed bafkrei…
Bob pulled  bafkrei…
Bob reads:   a private hello from Alice
```

This takes a few seconds. The peer ID differs on each machine and stays the same across runs,
because it is stored in `quickstart-alice/repo/identity`. This setup is how Nebulus works as a
**private data channel**: point `bootstrapAddresses` at your own peers instead of the public
network.

## 4. Publish to the public IPFS network

With the default options, Nebulus joins the public network. `push` completes once
`https://ipfs.io` serves your content.

```java
try (Nebulus nebulus = new Nebulus(NebulusOptions.builder()
        .path("quickstart-public")
        .pushTimeout(Duration.ofMinutes(15))
        .build())) {

    nebulus.connect().join();

    // Fetch content someone else published
    String helloWorld = "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e";
    nebulus.pull(helloWorld).join();
    System.out.println("Pulled: " + nebulus.get(helloWorld, StandardCharsets.UTF_8));

    // Publish something new
    String cid = nebulus.add(("hello from nebulus-nabu " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    nebulus.on("push:" + cid, pushed -> System.out.println("Live at https://ipfs.io/ipfs/" + pushed));
    nebulus.push(cid).join();
}
```

```bash
mvn -q compile exec:java -Dexec.mainClass=quickstart.PublicNetwork
```

Once you see `Live at …`, open the link: anyone can fetch your content now. The gateway keeps a
copy for a while, so you could stop your node at this point. Nebulus calls this
*ephemeral IPFS*.

Connecting and publishing on the public network can take **several minutes**. The gateway has
to find and reach your node, which is harder behind NAT. If it never completes, see
[Troubleshooting](#troubleshooting).

## From source

To try changes to nebulus-nabu or Nabu without JitPack, install both locally and run the examples
with `-Plocal`:

```bash
git clone https://github.com/pedropietro/nabu
mvn -f nabu/pom.xml install -DskipTests

git clone https://github.com/pedropietro/nebulus-nabu
cd nebulus-nabu
mvn -Pnabu-local install            # builds and runs the test suite

cd examples/quickstart
mvn -q -Plocal compile exec:java -Dexec.mainClass=quickstart.QuickStart
```

The ports of the original demos (`HelloWorld`, `Upload`, `UploadFolder`) are in
`src/test/java/io/github/pedropietro/nebulus/demo`.

## Troubleshooting

**Too many log lines.** Nabu logs to the global `java.util.logging` logger at `INFO`. Call
`Logger.getGlobal().setLevel(Level.WARNING)` in code, or start the JVM with
`-Djava.util.logging.config.file=logging.properties` and a file that contains:

```properties
handlers=java.util.logging.ConsoleHandler
.level=INFO
global.level=WARNING
```

**`BindException: Address already in use`.** Another process is using the swarm port (4002 by
default, or 4101 and 4102 in step 3). Choose a different port with `swarmAddresses(...)`. Each
Nebulus instance on a machine needs its own port and its own `path`.

**`push` never completes, or ends with `TimeoutException`.** The gateway cannot fetch from your
node, usually because of NAT or a firewall. You can:

- Forward the swarm TCP port on your router.
- Run on a host with a public IP address.
- Use `verifyOnGateway(false)`, so `push` completes right after the DHT announcement. Keep the
  node running until someone fetches the content.

**`pull` times out.** No reachable peer has the content, or the provider is behind NAT. Check
that the CID is reachable through a gateway, or bootstrap directly to the node that has it, as
in step 3.

**Windows.** Creating symbolic links needs developer mode or administrator rights. Without
them, Nebulus copies files into `ipfs/` instead and logs a warning.

**JitPack: `Could not resolve com.github.pedropietro:nebulus-nabu`.** JitPack builds new
versions on demand. Wait a few minutes and try again, or check the build log on
[jitpack.io](https://jitpack.io/#pedropietro/nebulus-nabu).

## Next steps

- The [README](../README.md) has the full API reference, the options, and the differences from
  the JavaScript version.
- The [tests](../src/test/java/io/github/pedropietro/nebulus) show every feature in use.
