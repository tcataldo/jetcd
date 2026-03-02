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

Tous les `*Impl` étendent `Impl` et utilisent les stubs gRPC standard (post-migration Vert.x) :

| Type d'appel | Stub utilisé | Exemple |
|---|---|---|
| Unaire | `XxxGrpc.XxxFutureStub` | `KVGrpc.newFutureStub(channel)` |
| Server-streaming | `XxxGrpc.XxxStub` + `StreamObserver` | Maintenance.snapshot(), Election.observe() |
| Bidirectionnel | `XxxGrpc.XxxStub` + `StreamObserver` | Watch, LeaseKeepAlive |

### Classe `Impl` (base de tous les impls)

```java
// Appel unaire avec retry (Failsafe)
execute(Supplier<ListenableFuture<S>>, Function<S,T> convert, Predicate<Status> doRetry)

// Appel unaire sans retry
completable(ListenableFuture<S>, Function<S,T> convert)
```

`ListenableFuture` → `CompletableFuture` via `addListener(Runnable::run)` sans Guava.

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

**`LeaseImpl`** : deux stubs — `LeaseFutureStub` (grant/revoke/timeToLive) + `LeaseStub` (keepAlive bidirectionnel). Utilise un `ScheduledExecutorService` interne pour les timers keepalive (500ms) et deadline (1000ms).

**`WatchImpl`** : `WatchStub` pour le streaming bidirectionnel. `ScheduledExecutorService` pour le reschedule (500ms). Gère la reconnexion automatique via `shouldReschedule(Status)`.

**`AuthCredential`** : `CallCredentials` qui intercepte chaque appel pour injecter le token auth. Utilise `AuthGrpc.AuthFutureStub` pour s'authentifier et rafraîchit via `refresh()`.

### Resolvers d'adresses

Dans `jetcd-core/src/main/java/io/etcd/jetcd/resolver/` :
- `IPNameResolver` — schéma `ip://`, parse `host:port` avec Guava `HostAndPort`
- `HttpNameResolver` / `HttpsNameResolver` — schéma `http://` / `https://`
- `DnsSrvNameResolver` — schéma `dns+srv://`

## Dépendances clés

- **gRPC** : `grpc-netty`, `grpc-protobuf`, `grpc-stub`, `grpc-core`
- **Failsafe** : retry policy sur les appels gRPC
- **Guava** : encore présent (`Strings`, `HostAndPort`, `Preconditions`, `ListenableFuture`) — à retirer
- **SLF4J** : logging
- **Log4j** : implémentation logging en test

## Qualité de code

- **Spotless** : formatage imposé — toujours lancer `spotlessApply` après des modifications
- **ErrorProne** : activé, `excludedPaths = '.*/generated/.*'`
- **PMD** : règles dans `etc/pmd-ruleset.xml`
- `compileJava` target Java 11 (`options.release = 11`)

## Conventions

- Les packages générés sont `io.etcd.jetcd.api` (et `io.etcd.jetcd.api.lock`)
- La classe `io.etcd.jetcd.Preconditions` est locale (éviter Guava pour ça)
- Tests d'intégration dans `*IntegrationTest.java`, unitaires dans `*Test.java`
- `@VisibleForTesting` de Guava utilisé dans quelques endroits (à retirer avec Guava)

## Travaux en cours / à faire

- [ ] Retirer Guava : `Strings.isNullOrEmpty()` → inline, `HostAndPort` → `URI`, `Splitter` → `split()`, `@VisibleForTesting` → supprimer
