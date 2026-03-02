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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import io.etcd.jetcd.api.AuthGrpc;
import io.etcd.jetcd.api.AuthenticateRequest;
import io.etcd.jetcd.api.AuthenticateResponse;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;

import static io.etcd.jetcd.Preconditions.checkArgument;

/**
 * AuthTokenInterceptor fills header with Auth token of any rpc calls and
 * refreshes token if the rpc results an invalid Auth token error.
 */
class AuthCredential extends CallCredentials {
    public static final Metadata.Key<String> TOKEN = Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER);

    private final ClientConnectionManager manager;
    private volatile Metadata meta;

    public AuthCredential(ClientConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        final Metadata meta = this.meta;

        if (meta != null) {
            applier.apply(meta);
        } else {
            authenticate(applier);
        }
    }

    public void refresh() {
        meta = null;
    }

    @SuppressWarnings("rawtypes")
    private void authenticate(MetadataApplier applier) {
        checkArgument(!manager.builder().user().isEmpty(), "username can not be empty.");
        checkArgument(!manager.builder().password().isEmpty(), "password can not be empty.");

        AuthGrpc.AuthFutureStub authFutureStub = AuthGrpc.newFutureStub(this.manager.getChannel());

        List<ClientInterceptor> interceptorsChain = new ArrayList<>();
        if (manager.builder().authHeaders() != null) {
            Metadata metadata = new Metadata();
            manager.builder().authHeaders().forEach((BiConsumer<Metadata.Key, Object>) metadata::put);

            interceptorsChain.add(MetadataUtils.newAttachHeadersInterceptor(metadata));
        }
        if (manager.builder().authInterceptors() != null) {
            interceptorsChain.addAll(manager.builder().authInterceptors());
        }

        if (!interceptorsChain.isEmpty()) {
            authFutureStub = authFutureStub.withInterceptors(
                interceptorsChain.toArray(new ClientInterceptor[0]));
        }

        final ByteString user = ByteString.copyFrom(this.manager.builder().user().getBytes());
        final ByteString pass = ByteString.copyFrom(this.manager.builder().password().getBytes());

        AuthenticateRequest request = AuthenticateRequest.newBuilder()
            .setNameBytes(user)
            .setPasswordBytes(pass)
            .build();

        try {
            ListenableFuture<AuthenticateResponse> future = authFutureStub.authenticate(request);
            future.addListener(() -> {
                try {
                    AuthenticateResponse h = future.get();
                    Metadata meta = new Metadata();
                    meta.put(TOKEN, h.getToken());
                    this.meta = meta;
                    applier.apply(this.meta);
                } catch (ExecutionException e) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(e.getCause()));
                } catch (Exception e) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(e));
                }
            }, Runnable::run);
        } catch (Exception e) {
            applier.fail(Status.UNAUTHENTICATED.withCause(e));
        }
    }
}
