# Guia rápido

🇺🇸 [Read in English](quickstart.md) · ← [README](../README.pt-BR.md)

Este guia leva uns 10 minutos. Você vai:

1. Adicionar a dependência.
2. Criar CIDs offline e lê-los de volta.
3. Compartilhar um arquivo entre dois nós na sua máquina.
4. Publicar na rede pública do IPFS.

Cada trecho de código daqui também é um programa executável em
[`examples/quickstart`](../examples/quickstart). O CI roda esses programas a cada commit.

## Pré-requisitos

- **Java 17** ou mais recente (`java -version`).
- **Maven 3.6+**, ou Gradle.
- Acesso à internet para baixar as dependências. Só o passo 4 precisa da rede pública do IPFS.

## 1. Adicione a dependência

Crie um projeto Maven, ou use o [`examples/quickstart/pom.xml`](../examples/quickstart/pom.xml),
e adicione o JitPack e o nebulus-nabu:

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

Com Gradle:

```groovy
repositories { mavenCentral(); maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.pedropietro:nebulus-nabu:main-SNAPSHOT' }
```

> O primeiro build de uma versão nova no JitPack pode levar alguns minutos, e o primeiro pedido
> pode falhar com HTTP 502 enquanto ele compila. Se isso acontecer, espere e rode o build de novo.

## 2. Seus primeiros CIDs (offline)

Nada neste passo inicia um nó IPFS ou envia algo pela rede.

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
            // 1. Adicione bytes: o CID é calculado localmente
            String hello = nebulus.add("hello world".getBytes(StandardCharsets.UTF_8));
            System.out.println("hello world  -> " + hello);

            // 2. Adicione um arquivo: o Nebulus cria um link para ele em vez de copiá-lo
            Path note = Files.writeString(Path.of("quickstart-storage", "note.txt"), "Nebulus keeps this private");
            String noteCid = nebulus.add(note);
            System.out.println("note.txt     -> " + noteCid);

            // 3. Monte uma pasta a partir de CIDs que já estão no Nebulus
            String folder = nebulus.folder(Map.of(
                    "hello.txt", hello,
                    "note.txt", noteCid));
            System.out.println("folder       -> " + folder + " " + nebulus.list(folder));

            // 4. Leia de volta, pelo CID ou pelo caminho na pasta
            System.out.println("note.txt says: " + nebulus.get(folder + "/note.txt", StandardCharsets.UTF_8));
        }
    }
}
```

Rode:

```bash
cd examples/quickstart
mvn -q compile exec:java -Dexec.mainClass=quickstart.QuickStart
```

A saída é a mesma em qualquer máquina, porque o CID depende só do conteúdo:

```
hello world  -> bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e
note.txt     -> bafkreic4cqoc3p5wc6avrsyuejtjuxnztwpyhlhvs7i2u23jnveldjotgq
folder       -> bafybeif4ubfbkzlur7gc2xn4gazuyugmxozdftyne3xalzqku6olwgpwdi [hello.txt, note.txt]
note.txt says: Nebulus keeps this private
```

São CIDs reais do IPFS. O `ipfs add --cid-version=1 --raw-leaves` gera os mesmos, assim como o
Nebulus em JavaScript. Veja o que foi gravado em disco:

```
quickstart-storage/
├── note.txt                                      seu arquivo, intocado
├── src/b94d27b9…cde9                             os bytes de "hello world", nomeados pelo sha256
└── ipfs/
    ├── bafkreifzjut3…36n5e  → ../src/b94d27b9…   um link simbólico por CID
    ├── bafkreic4cqoc…jotgq  → ../note.txt
    └── bafybeif4ubf…wgpwdi/                      a pasta
        ├── hello.txt        → ../bafkreifzjut3…
        └── note.txt         → ../bafkreic4cqoc…
```

Experimente:

- **Baixar de uma URL:** `nebulus.add("https://example.com/logo.png")`. Você pode limitar o
  tamanho com `.maxMegabytes(10)` nas opções.
- **Ler conteúdo grande em stream:** `try (InputStream in = nebulus.stream(cid)) { … }`
- **Rodar o programa duas vezes.** Adicionar o mesmo conteúdo de novo devolve o mesmo CID e não
  cria duplicatas.

## 3. Compartilhe entre dois nós na sua máquina

Agora inicie dois nós Nabu embutidos, Alice e Bob. Alice publica um arquivo e Bob o busca via
Bitswap. Alice não tem peers de bootstrap e Bob só conhece Alice, então nada sai da sua máquina.

```java
Logger.getGlobal().setLevel(Level.WARNING); // o Nabu registra logs no logger global

Nebulus alice = new Nebulus(NebulusOptions.builder()
        .path("quickstart-alice")
        .swarmAddresses(List.of("/ip4/127.0.0.1/tcp/4101"))
        .bootstrapAddresses(List.of())   // fica fora da rede pública
        .verifyOnGateway(false)          // não há gateway público para esperar
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

Isso leva alguns segundos. O peer ID muda de uma máquina para outra e se mantém entre execuções,
porque fica gravado em `quickstart-alice/repo/identity`. É assim que o Nebulus funciona como
**canal privado de dados**: aponte o `bootstrapAddresses` para os seus próprios peers em vez da
rede pública.

## 4. Publique na rede pública do IPFS

Com as opções padrão, o Nebulus entra na rede pública. O `push` termina quando
`https://ipfs.io` passa a servir o seu conteúdo.

```java
try (Nebulus nebulus = new Nebulus(NebulusOptions.builder()
        .path("quickstart-public")
        .pushTimeout(Duration.ofMinutes(15))
        .build())) {

    nebulus.connect().join();

    // Busque um conteúdo que outra pessoa publicou
    String helloWorld = "bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e";
    nebulus.pull(helloWorld).join();
    System.out.println("Pulled: " + nebulus.get(helloWorld, StandardCharsets.UTF_8));

    // Publique algo novo
    String cid = nebulus.add(("hello from nebulus-nabu " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    nebulus.on("push:" + cid, pushed -> System.out.println("Live at https://ipfs.io/ipfs/" + pushed));
    nebulus.push(cid).join();
}
```

```bash
mvn -q compile exec:java -Dexec.mainClass=quickstart.PublicNetwork
```

Quando aparecer `Live at …`, abra o link: qualquer pessoa já consegue buscar o seu conteúdo. O
gateway guarda uma cópia por um tempo, então você pode parar o seu nó nesse ponto. O Nebulus
chama isso de *IPFS efêmero*.

Conectar e publicar na rede pública pode levar **vários minutos**. O gateway precisa encontrar e
alcançar o seu nó, o que é mais difícil atrás de NAT. Se não terminar nunca, veja
[Solução de problemas](#solução-de-problemas).

## A partir do código-fonte

Para testar mudanças no nebulus-nabu ou no Nabu sem o JitPack, instale os dois localmente e rode
os exemplos com `-Plocal`:

```bash
git clone https://github.com/pedropietro/nabu
mvn -f nabu/pom.xml install -DskipTests

git clone https://github.com/pedropietro/nebulus-nabu
cd nebulus-nabu
mvn -Pnabu-local install            # compila e roda a suíte de testes

cd examples/quickstart
mvn -q -Plocal compile exec:java -Dexec.mainClass=quickstart.QuickStart
```

Os ports dos demos originais (`HelloWorld`, `Upload`, `UploadFolder`) estão em
`src/test/java/io/github/pedropietro/nebulus/demo`.

## Solução de problemas

**Logs demais.** O Nabu registra no logger global do `java.util.logging` com nível `INFO`. Chame
`Logger.getGlobal().setLevel(Level.WARNING)` no código, ou inicie a JVM com
`-Djava.util.logging.config.file=logging.properties` e um arquivo com:

```properties
handlers=java.util.logging.ConsoleHandler
.level=INFO
global.level=WARNING
```

**`BindException: Address already in use`.** Outro processo está usando a porta do swarm (4002
por padrão, ou 4101 e 4102 no passo 3). Escolha outra porta com `swarmAddresses(...)`. Cada
instância do Nebulus numa máquina precisa da sua própria porta e do seu próprio `path`.

**O `push` nunca termina, ou termina com `TimeoutException`.** O gateway não consegue buscar no
seu nó, normalmente por causa de NAT ou firewall. Você pode:

- Redirecionar a porta TCP do swarm no roteador.
- Rodar numa máquina com IP público.
- Usar `verifyOnGateway(false)`, para que o `push` termine logo depois do anúncio na DHT. Mantenha
  o nó rodando até alguém buscar o conteúdo.

**O `pull` estoura o tempo.** Nenhum peer alcançável tem o conteúdo, ou quem o fornece está atrás
de NAT. Confira se o CID está acessível por algum gateway, ou faça bootstrap direto no nó que tem
o conteúdo, como no passo 3.

**Windows.** Criar links simbólicos exige o modo de desenvolvedor ou direitos de administrador.
Sem isso, o Nebulus copia os arquivos para `ipfs/` e registra um aviso.

**JitPack: `Could not resolve com.github.pedropietro:nebulus-nabu`.** O JitPack compila versões
novas sob demanda. Espere alguns minutos e tente de novo, ou veja o log do build em
[jitpack.io](https://jitpack.io/#pedropietro/nebulus-nabu).

## Próximos passos

- O [README](../README.pt-BR.md) traz a referência completa da API, as opções e as diferenças em
  relação à versão em JavaScript.
- Os [testes](../src/test/java/io/github/pedropietro/nebulus) mostram cada recurso em uso.
