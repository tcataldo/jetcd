# jetcd — Guide pour Claude Code

## Build

```bash
# Java 21 requis (le système a Java 25 qui casse certains plugins Gradle)
JAVA_HOME=/usr/lib/jvm/jdk-21 ./gradlew build -x test
JAVA_HOME=/usr/lib/jvm/jdk-21 ./gradlew test          # tests d'intégration (nécessitent Docker)
JAVA_HOME=/usr/lib/jvm/jdk-21 ./gradlew spotlessApply # corriger le formatage
JAVA_HOME=/usr/lib/jvm/jdk-21 ./gradlew :jetcd-core:pmdMain # vérifier PMD
```

## Structure des modules

```
jetcd-grpc/     Stubs gRPC générés depuis les .proto (rpc.proto, lock.proto, election.proto)
jetcd-common/   Service, exceptions métier, Preconditions local
jetcd-api/      Interfaces publiques du client (KV, Lease, Watch, etc.)
jetcd-core/     Implémentation principale
jetcd-launcher/ Lanceur etcd embarqué pour les tests (Docker via Testcontainers)
jetcd-test/     Utilitaires de test (EtcdClusterExtension, name resolvers)
jetcd-ctl/      CLI outil de ligne de commande
```

## Architecture interne (jetcd-core)

### Couche gRPC

Tous les `*Impl` étendent `Impl` et utilisent les stubs gRPC async (post-migration Vert.x + Guava) :

| Type d'appel | Stub utilisé | Exemple |
|---|---|---|
| Unaire | `XxxGrpc.XxxStub` + `StreamObserver` | `KVGrpc.newStub(channel)` |
| Server-streaming | `XxxGrpc.XxxStub` + `StreamObserver` | Maintenance.snapshot(), Election.observe() |
| Bidirectionnel | `XxxGrpc.XxxStub` + `StreamObserver` | Watch, LeaseKeepAlive |

### Classe `Impl` (base de tous les impls)

```java
// Appel unaire — wrapping d'un appel async en CompletableFuture
static unary(Consumer<StreamObserver<S>> call) → CompletableFuture<S>

// Appel unaire sans retry (2 surcharges)
completable(CompletableFuture<S>, Function<S,T> convert)      // pour withNewChannel()
completable(Consumer<StreamObserver<S>>, Function<S,T> convert) // → completable(unary(call), convert)

// Appel unaire avec retry (Failsafe) — 2 surcharges × 2 types du premier arg
execute(Consumer<StreamObserver<S>>, Function<S,T>, boolean|Predicate<Status>)
execute(Supplier<CompletableFuture<S>>, Function<S,T>, boolean|Predicate<Status>)
```

**Inférence de types Java 21** : quand les deux args sont des lambdas, Java ne peut pas toujours inférer `S`.
Utiliser un type witness explicite pour les cas qui échouent :
```java
this.<io.etcd.jetcd.api.RangeResponse, GetResponse>execute(obs -> stub.range(req, obs), r -> new GetResponse(r, ns), pred)
```
Affecte : `KVImpl.get/put/delete/txn`, `LockImpl.lock`, `ElectionImpl.leader`. Les method references (`CompactResponse::new`) n'ont pas ce problème.

### `ClientConnectionManager`

- Crée le `ManagedChannel` via `NettyChannelBuilder`
- Gère les stubs (`newStub()`), le `ExecutorService`, et les `AuthCredential`
- `withNewChannel(target, stubFactory, consumer)` pour les opérations sur un nœud spécifique (Maintenance)

### `ClientBuilder`

Builder public exposé via `Client.builder()`. Champs clés :
- `target` — format `scheme://authority/addresses` (ex: `ip:///127.0.0.1:2379`)
- `namespace`, `user`/`password`, `sslContext`, `keepaliveTime`/`Timeout`
- `retryDelay`, `retryMaxDelay`, `retryMaxAttempts`
- `loadBalancerPolicy` (défaut: `"round_robin"`)

### Services avec streaming

**`LeaseImpl`** : un seul `LeaseStub` pour tout (grant/revoke/timeToLive + keepAlive bidirectionnel). Utilise un `ScheduledExecutorService` interne pour les timers keepalive (500ms) et deadline (1000ms).

**`WatchImpl`** : `WatchStub` pour le streaming bidirectionnel. `ScheduledExecutorService` pour le reschedule (500ms). Gère la reconnexion automatique via `shouldReschedule(Status)`.

**`AuthCredential`** : `CallCredentials` qui intercepte chaque appel pour injecter le token auth. Utilise `AuthGrpc.AuthStub` avec `StreamObserver` pour s'authentifier et rafraîchit via `refresh()`.

### Resolvers d'adresses

Dans `jetcd-core/src/main/java/io/etcd/jetcd/resolver/` :
- `IPNameResolver` — schéma `ip://`, parse `host:port` via classe interne privée `HostPort`
- `HttpNameResolver` / `HttpsNameResolver` — schéma `http://` / `https://`
- `DnsSrvNameResolver` — schéma `dns+srv://`, utilise `Arrays.asList(s.split(" "))`

## Dépendances clés

- **gRPC** : `grpc-netty`, `grpc-protobuf`, `grpc-stub`, `grpc-core`
- **Failsafe** : retry policy sur les appels gRPC
- **SLF4J** : logging
- **Log4j** : implémentation logging en test

## Qualité de code

- **Spotless** : formatage imposé — toujours lancer `spotlessApply` après des modifications
- **ErrorProne** : activé, `excludedPaths = '.*/generated/.*'`
- **PMD** : règles dans `etc/pmd-ruleset.xml`
- `compileJava` target Java 11 (`options.release = 11`)

## Conventions

- Les packages générés sont `io.etcd.jetcd.api` (et `io.etcd.jetcd.api.lock`)
- La classe `io.etcd.jetcd.Preconditions` est locale — utiliser à la place de Guava
- Tests d'intégration dans `*IntegrationTest.java`, unitaires dans `*Test.java`
