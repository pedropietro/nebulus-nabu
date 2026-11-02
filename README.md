# nebulus-nabu

> Virtual IPFS for Java

A Java port of [Nebulus](https://github.com/rarepress/nebulus) built on
[Nabu](https://github.com/pedropietro/nabu), a Java implementation of IPFS.

Nebulus separates the IPFS **file format** from the IPFS **network**. You compute IPFS CIDs
locally, keep files private and offline, and publish to the public network only when you
choose to. It works like Git: `add` happens locally, and `push`/`pull` sync with the network.

```java
try (Nebulus nebulus = new Nebulus()) {
    String cid = nebulus.add("Hello world".getBytes());  // 1. private, offline
    nebulus.push(cid).join();                           // 2. now on the public IPFS network
}
```

The CIDs are byte-for-byte the same as those from the JavaScript Nebulus and from
`ipfs add --cid-version=1 --raw-leaves`. The test suite checks this against vectors generated
with `ipfs-unixfs-importer@8`, the importer Nebulus uses.

## How it works

Nebulus keeps everything under one folder (`.nebulus` by default):

```
.nebulus/
  src/<sha256>          buffers and downloads, stored once by content hash
  ipfs/<cid>            symbolic link to the file with that CID's content
  ipfs/<cid>/<name>     folders: one link per entry
  repo/                 Nabu node state (blocks, identity), only after connect()
```

`add` computes the CID with a built-in UnixFS importer: 256 KiB chunks, raw leaves, a balanced
DAG with 174 links per node, CIDv1 and sha2-256. It does not need a node.

`connect` starts an embedded Nabu node. `push` writes the file's blocks to Nabu's blockstore,
announces the root in the DHT, and serves the blocks over Bitswap. By default it then waits
until the gateway (`https://ipfs.io`) serves the CID, the same check the original does. `pull`
fetches the DAG over Bitswap, verifies each block against its CID, and recreates the file or
folder locally.

## Install

Maven, through [JitPack](https://jitpack.io), which builds Nabu from `pedropietro/nabu`:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.pedropietro</groupId>
  <artifactId>nebulus-nabu</artifactId>
  <version>main-SNAPSHOT</version>
</dependency>
```

To build from source (Java 17+):

```bash
mvn verify                    # Nabu resolved through JitPack
# or with Nabu built locally from the fork:
git clone https://github.com/pedropietro/nabu && mvn -f nabu/pom.xml install -DskipTests
mvn -Pnabu-local verify
```

## Usage

### Options

```java
Nebulus nebulus = new Nebulus(NebulusOptions.builder()
        .path("storage")                                 // default: .nebulus
        .maxMegabytes(100)                               // download limit; default: none
        .swarmAddresses(List.of("/ip4/0.0.0.0/tcp/4030")) // default: /ip4/0.0.0.0/tcp/4002
        .bootstrapAddresses(List.of(...))                // default: public libp2p bootstrap
        .build());
```

`swarmAddresses` and `bootstrapAddresses` replace the IPFS `config` object of the original.
Give each instance its own address to run several nodes on one machine. Other options:
`gateway`, `verifyOnGateway`, `gatewayPollInterval`, `gatewayRequestTimeout`, `pushTimeout`
and `pullTimeout` (`Duration.ZERO` waits forever, like the original), and `network` (plug in
another `IpfsNetwork`).

### Writing

```java
String a = nebulus.add("hello world".getBytes());          // a buffer
String b = nebulus.add(Path.of("fixture/hello.txt"));      // a file (linked, not copied)
String c = nebulus.add("https://example.com/image.png");   // a URL (downloaded)
String d = nebulus.download("https://ipfs.io/ipfs/bafkrei...");

String folder = nebulus.folder(Map.of(                     // a depth-1 folder
        "readme.md", a,
        "index.js", b));
```

### Reading

```java
byte[] bytes = nebulus.get(cid);
String text = nebulus.get(cid, StandardCharsets.UTF_8);
try (InputStream in = nebulus.stream(folder + "/readme.md")) { ... }
List<String> names = nebulus.list(folder);
```

### IPFS network

```java
nebulus.connect().join();      // optional: push/pull connect on demand
nebulus.push(cid).join();      // publish and wait until the gateway serves it
nebulus.pull(cid).join();      // fetch from the network into Nebulus
nebulus.disconnect().join();
```

### Events

`push` and `pull` return a `CompletableFuture<String>`. They also fire the events of the original
API, either globally or once per CID:

```java
nebulus.on("push", cid -> System.out.println("https://ipfs.io/ipfs/" + cid));
nebulus.on("pull:" + cid, pulled -> ...);   // fires once, then is removed
nebulus.push(cid);
```

The original's `upload` and `download` names are fired as aliases.

## Mapping from the JavaScript API

| Nebulus (JS)                     | nebulus-nabu (Java)                                      |
|----------------------------------|----------------------------------------------------------|
| `new Nebulus({ path, max, config })` | `new Nebulus(NebulusOptions.builder()...build())`    |
| `await nebulus.add(buffer \| path)` | `nebulus.add(byte[] \| Path \| String)`               |
| `await nebulus.download(url)`    | `nebulus.download(url)`                                  |
| `await nebulus.folder({...})`    | `nebulus.folder(Map<String, String>)`                    |
| `nebulus.get(cid, encoding)`     | `nebulus.get(cid)` / `nebulus.get(cid, charset)`         |
| `nebulus.stream(cid)`            | `nebulus.stream(cid)` (`InputStream`)                    |
| `connect()` / `disconnect()`     | same, returning `CompletableFuture<Void>`                |
| `push(cid)` / `pull(cid)`        | same, returning `CompletableFuture<String>`              |
| `on(event, cb)`                  | `on(event, Consumer<String>)`, plus `off(...)`           |

Differences from the original:

- `on` adds a listener instead of replacing the previous one, and a failing listener doesn't stop the rest.
- `push` of a folder publishes that folder's own CID. The original copied each file into the
  node's MFS root and checked the gateway under that root's CID instead.
- `pull` links content under the CID that was requested. Content added by other tools (CIDv0,
  other chunkers) also works, and is linked under the CID Nebulus computes for it too.
- `max` also applies when the server sends no `Content-Length`.
- Folder entry names are validated, since `pull` takes them from the network.
- On file systems without symbolic links (Windows without developer mode), files are copied.

## Limitations

- Folders are depth-1, as in the original. Nested and sharded (HAMT) directories are rejected.
- `push` checks the gateway by default. That takes as long as the gateway needs to find a node
  that is often behind NAT. Use `verifyOnGateway(false)` to complete once the DHT announcement
  is made.
- Only sha2-256 blocks are verified on `pull`. Blocks with other hash functions are accepted as
  received.

## Tests

```bash
mvn -Pnabu-local verify                          # offline: CID vectors, API, push/pull, 2 local Nabu nodes
mvn -Pnabu-local verify -Dnebulus.live=true      # also against the public network and ipfs.io
```

The demos in `src/test/java/.../demo` port `helloworld.js`, `upload.js` and `uploadFolder.js`.

## License

BSD 3-Clause, see [LICENSE](LICENSE). This is a port of Nebulus (MIT, © 2021 Skogard); see
[NOTICE](NOTICE).
