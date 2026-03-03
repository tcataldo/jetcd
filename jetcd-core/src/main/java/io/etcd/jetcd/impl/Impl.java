package io.etcd.jetcd.impl;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.etcd.jetcd.common.exception.EtcdExceptionFactory;
import io.etcd.jetcd.support.Errors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import dev.failsafe.RetryPolicyBuilder;

import static io.etcd.jetcd.support.Errors.isAuthStoreExpired;
import static io.etcd.jetcd.support.Errors.isInvalidTokenError;

abstract class Impl {
    private final Logger logger;
    private final ClientConnectionManager connectionManager;

    protected Impl(ClientConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.logger = LoggerFactory.getLogger(getClass());
    }

    protected ClientConnectionManager connectionManager() {
        return this.connectionManager;
    }

    protected Logger logger() {
        return this.logger;
    }

    /**
     * Initiates a unary async gRPC call and returns a CompletableFuture for the response.
     * Use with an explicit type witness when Java cannot infer S:
     * {@code Impl.<ApiType>unary(obs -> stub.method(req, obs))}
     *
     * @param  call a consumer that accepts a StreamObserver and initiates the gRPC unary call
     * @param  <S>  the response type
     * @return      a CompletableFuture that completes with the response or fails with the gRPC error
     */
    protected static <S> CompletableFuture<S> unary(Consumer<StreamObserver<S>> call) {
        CompletableFuture<S> cf = new CompletableFuture<>();
        call.accept(new StreamObserver<S>() {
            @Override
            public void onNext(S value) {
                cf.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                cf.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        });
        return cf;
    }

    /**
     * Converts a CompletableFuture of Type S to CompletableFuture of Type T, with error wrapping.
     *
     * @param  sourceFuture  the CompletableFuture to wrap
     * @param  resultConvert the result converter
     * @return               a {@link CompletableFuture} wrapping the given future
     */
    protected <S, T> CompletableFuture<T> completable(CompletableFuture<S> sourceFuture, Function<S, T> resultConvert) {
        return sourceFuture.handle((s, t) -> {
            if (t != null) {
                throw EtcdExceptionFactory.toEtcdException(t);
            }
            return resultConvert.apply(s);
        });
    }

    /**
     * Executes a unary gRPC call converting the response to the target type, with error wrapping.
     * Works when Java can infer the response type S from both arguments together.
     * Use {@link #completable(CompletableFuture, Function)} with {@link #unary} and an explicit
     * type witness when inference fails.
     *
     * @param  call          a consumer that initiates the unary gRPC call
     * @param  resultConvert the result converter
     * @return               a {@link CompletableFuture} with the converted result
     */
    protected <S, T> CompletableFuture<T> completable(
        Consumer<StreamObserver<S>> call,
        Function<S, T> resultConvert) {

        return completable(unary(call), resultConvert);
    }

    /**
     * execute the task and retry it in case of failure.
     * Use with an explicit type witness when Java cannot infer S:
     * {@code this.<ApiType, TargetType>execute(obs -> stub.method(req, obs), convert, true)}
     *
     * @param  call          a consumer that initiates the unary gRPC call
     * @param  resultConvert a function that converts Type S to Type T.
     * @param  autoRetry     whether to retry safe-to-redo operations
     * @param  <S>           Source type
     * @param  <T>           Converted Type.
     * @return               a CompletableFuture with type T.
     */
    protected <S, T> CompletableFuture<T> execute(
        Consumer<StreamObserver<S>> call,
        Function<S, T> resultConvert,
        boolean autoRetry) {

        return execute(() -> unary(call), resultConvert,
            autoRetry ? Errors::isRetryableForSafeRedoOp : Errors::isRetryableForNoSafeRedoOp);
    }

    /**
     * execute the task and retry it in case of failure.
     * Use with an explicit type witness when Java cannot infer S:
     * {@code this.<ApiType, TargetType>execute(obs -> stub.method(req, obs), convert, pred)}
     *
     * @param  call          a consumer that initiates the unary gRPC call
     * @param  resultConvert a function that converts Type S to Type T.
     * @param  doRetry       a predicate to determine if a failure has to be retried
     * @param  <S>           Source type
     * @param  <T>           Converted Type.
     * @return               a CompletableFuture with type T.
     */
    protected <S, T> CompletableFuture<T> execute(
        Consumer<StreamObserver<S>> call,
        Function<S, T> resultConvert,
        Predicate<Status> doRetry) {

        return execute(() -> unary(call), resultConvert, doRetry);
    }

    /**
     * execute the task and retry it in case of failure.
     *
     * @param  supplier      a function that returns a new CompletableFuture.
     * @param  resultConvert a function that converts Type S to Type T.
     * @param  <S>           Source type
     * @param  <T>           Converted Type.
     * @return               a CompletableFuture with type T.
     */
    protected <S, T> CompletableFuture<T> execute(
        Supplier<CompletableFuture<S>> supplier,
        Function<S, T> resultConvert,
        boolean autoRetry) {

        return execute(supplier, resultConvert,
            autoRetry ? Errors::isRetryableForSafeRedoOp : Errors::isRetryableForNoSafeRedoOp);
    }

    /**
     * execute the task and retry it in case of failure.
     *
     * @param  supplier      a function that returns a new CompletableFuture.
     * @param  resultConvert a function that converts Type S to Type T.
     * @param  doRetry       a predicate to determine if a failure has to be retried
     * @param  <S>           Source type
     * @param  <T>           Converted Type.
     * @return               a CompletableFuture with type T.
     */
    protected <S, T> CompletableFuture<T> execute(
        Supplier<CompletableFuture<S>> supplier,
        Function<S, T> resultConvert,
        Predicate<Status> doRetry) {

        return Failsafe
            .with(retryPolicy(doRetry))
            .with(connectionManager.getExecutorService())
            .getStageAsync(() -> supplier.get())
            .thenApply(resultConvert);
    }

    protected <S> RetryPolicy<S> retryPolicy(Predicate<Status> doRetry) {
        RetryPolicyBuilder<S> policy = RetryPolicy.<S> builder()
            .onFailure(e -> {
                logger.warn("retry failure (attempt: {}, error: {})",
                    e.getAttemptCount(),
                    e.getException() != null ? e.getException().getMessage() : "<none>");
            })
            .onRetry(e -> {
                logger.debug("retry (attempt: {}, error: {})",
                    e.getAttemptCount(),
                    e.getLastException() != null ? e.getLastException().getMessage() : "<none>");
            })
            .onRetriesExceeded(e -> {
                logger.warn("maximum number of auto retries reached (attempt: {}, error: {})",
                    e.getAttemptCount(),
                    e.getException() != null ? e.getException().getMessage() : "<none>");
            })
            .handleIf(throwable -> {
                Status status = Status.fromThrowable(throwable);
                if (isInvalidTokenError(status)) {
                    connectionManager.authCredential().refresh();
                }
                if (isAuthStoreExpired(status)) {
                    connectionManager.authCredential().refresh();
                }
                return doRetry.test(status);
            })
            .withMaxRetries(connectionManager.builder().retryMaxAttempts())
            .withBackoff(
                connectionManager.builder().retryDelay(),
                connectionManager.builder().retryMaxDelay(),
                connectionManager.builder().retryChronoUnit());

        if (connectionManager.builder().retryMaxDuration() != null) {
            policy = policy.withMaxDuration(connectionManager.builder().retryMaxDuration());
        }

        return policy.build();
    }
}
