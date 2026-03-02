/*
 * Copyright 2016-2021 The jetcd authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.etcd.jetcd.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.ClientBuilder;
import io.etcd.jetcd.support.Util;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.AbstractStub;
import io.netty.channel.ChannelOption;

import static io.etcd.jetcd.common.exception.EtcdExceptionFactory.toEtcdException;

final class ClientConnectionManager {
    private final Object lock;
    private final ClientBuilder builder;
    private final ExecutorService executorService;
    private final AuthCredential credential;
    private volatile ManagedChannel managedChannel;

    ClientConnectionManager(ClientBuilder builder) {
        this(builder, null);
    }

    ClientConnectionManager(ClientBuilder builder, ManagedChannel managedChannel) {
        this.lock = new Object();
        this.builder = builder;
        this.managedChannel = managedChannel;
        this.credential = new AuthCredential(this);

        if (builder.executorService() == null) {
            // default to daemon
            this.executorService = Executors.newCachedThreadPool(Util.createThreadFactory("jetcd-", true));
        } else {
            this.executorService = builder.executorService();
        }
    }

    ManagedChannel getChannel() {
        if (managedChannel == null) {
            synchronized (lock) {
                if (managedChannel == null) {
                    managedChannel = defaultChannelBuilder().build();
                }
            }
        }

        return managedChannel;
    }

    ByteSequence getNamespace() {
        return builder.namespace();
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    ClientBuilder builder() {
        return builder;
    }

    AuthCredential authCredential() {
        return this.credential;
    }

    /**
     * create stub with saved channel.
     *
     * @param  supplier the stub supplier
     * @param  <T>      the type of stub
     * @return          the attached stub
     */
    <T extends AbstractStub<T>> T newStub(Function<ManagedChannel, T> supplier) {
        return newStub(supplier, getChannel());
    }

    private <T extends AbstractStub<T>> T newStub(Function<ManagedChannel, T> stubCustomizer, ManagedChannel channel) {
        T stub = stubCustomizer.apply(channel);
        if (builder.waitForReady()) {
            stub = stub.withWaitForReady();
        }
        if (builder.user() != null && builder.password() != null) {
            stub = stub.withCallCredentials(this.authCredential());
        }

        return stub;
    }

    void close() {
        synchronized (lock) {
            if (managedChannel != null) {
                managedChannel.shutdownNow();
            }
        }

        if (builder.executorService() == null) {
            executorService.shutdownNow();
        }
    }

    <T extends AbstractStub<T>, R> CompletableFuture<R> withNewChannel(
        String target,
        Function<ManagedChannel, T> stubCustomizer,
        Function<T, CompletableFuture<R>> stubConsumer) {

        final ManagedChannel channel = defaultChannelBuilder(target).build();
        final T stub = newStub(stubCustomizer, channel);

        try {
            return stubConsumer.apply(stub).whenComplete((r, t) -> channel.shutdown());
        } catch (Exception e) {
            channel.shutdown();
            throw toEtcdException(e);
        }
    }

    ManagedChannelBuilder<?> defaultChannelBuilder() {
        return defaultChannelBuilder(builder.target());
    }

    @SuppressWarnings("rawtypes")
    ManagedChannelBuilder<?> defaultChannelBuilder(String target) {
        if (target == null) {
            throw new IllegalArgumentException("At least one endpoint should be provided");
        }

        final NettyChannelBuilder channelBuilder = NettyChannelBuilder.forTarget(target);

        if (builder.authority() != null) {
            channelBuilder.overrideAuthority(builder.authority());
        }
        if (builder.maxInboundMessageSize() != null) {
            channelBuilder.maxInboundMessageSize(builder.maxInboundMessageSize());
        }
        if (builder.sslContext() != null) {
            channelBuilder.negotiationType(NegotiationType.TLS);
            channelBuilder.sslContext(builder.sslContext());
        } else {
            channelBuilder.negotiationType(NegotiationType.PLAINTEXT);
        }

        if (builder.keepaliveTime() != null) {
            channelBuilder.keepAliveTime(builder.keepaliveTime().toMillis(), TimeUnit.MILLISECONDS);
        }
        if (builder.keepaliveTimeout() != null) {
            channelBuilder.keepAliveTimeout(builder.keepaliveTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        if (builder.keepaliveWithoutCalls() != null) {
            channelBuilder.keepAliveWithoutCalls(builder.keepaliveWithoutCalls());
        }
        if (builder.connectTimeout() != null) {
            channelBuilder.withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) builder.connectTimeout().toMillis());
        }

        if (builder.loadBalancerPolicy() != null) {
            channelBuilder.defaultLoadBalancingPolicy(builder.loadBalancerPolicy());
        } else {
            channelBuilder.defaultLoadBalancingPolicy("round_robin");
        }

        if (builder.headers() != null) {
            channelBuilder.intercept(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    CallOptions callOptions,
                    Channel next) {

                    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                        @Override
                        public void start(Listener<RespT> responseListener, Metadata headers) {
                            builder.headers().forEach((BiConsumer<Metadata.Key, Object>) headers::put);
                            super.start(responseListener, headers);
                        }
                    };
                }
            });
        }

        if (builder.interceptors() != null) {
            channelBuilder.intercept(builder.interceptors());
        }

        return channelBuilder;
    }
}
