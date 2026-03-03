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

import io.etcd.jetcd.Auth;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.api.AuthDisableRequest;
import io.etcd.jetcd.api.AuthEnableRequest;
import io.etcd.jetcd.api.AuthGrpc;
import io.etcd.jetcd.api.AuthRoleAddRequest;
import io.etcd.jetcd.api.AuthRoleDeleteRequest;
import io.etcd.jetcd.api.AuthRoleGetRequest;
import io.etcd.jetcd.api.AuthRoleGrantPermissionRequest;
import io.etcd.jetcd.api.AuthRoleListRequest;
import io.etcd.jetcd.api.AuthRoleRevokePermissionRequest;
import io.etcd.jetcd.api.AuthUserAddRequest;
import io.etcd.jetcd.api.AuthUserChangePasswordRequest;
import io.etcd.jetcd.api.AuthUserDeleteRequest;
import io.etcd.jetcd.api.AuthUserGetRequest;
import io.etcd.jetcd.api.AuthUserGrantRoleRequest;
import io.etcd.jetcd.api.AuthUserListRequest;
import io.etcd.jetcd.api.AuthUserRevokeRoleRequest;
import io.etcd.jetcd.auth.AuthDisableResponse;
import io.etcd.jetcd.auth.AuthEnableResponse;
import io.etcd.jetcd.auth.AuthRoleAddResponse;
import io.etcd.jetcd.auth.AuthRoleDeleteResponse;
import io.etcd.jetcd.auth.AuthRoleGetResponse;
import io.etcd.jetcd.auth.AuthRoleGrantPermissionResponse;
import io.etcd.jetcd.auth.AuthRoleListResponse;
import io.etcd.jetcd.auth.AuthRoleRevokePermissionResponse;
import io.etcd.jetcd.auth.AuthUserAddResponse;
import io.etcd.jetcd.auth.AuthUserChangePasswordResponse;
import io.etcd.jetcd.auth.AuthUserDeleteResponse;
import io.etcd.jetcd.auth.AuthUserGetResponse;
import io.etcd.jetcd.auth.AuthUserGrantRoleResponse;
import io.etcd.jetcd.auth.AuthUserListResponse;
import io.etcd.jetcd.auth.AuthUserRevokeRoleResponse;
import io.etcd.jetcd.auth.Permission;

import com.google.protobuf.ByteString;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of etcd auth client.
 */
final class AuthImpl extends Impl implements Auth {

    private final AuthGrpc.AuthStub stub;

    AuthImpl(ClientConnectionManager connectionManager) {
        super(connectionManager);

        this.stub = connectionManager.newStub(AuthGrpc::newStub);
    }

    @Override
    public CompletableFuture<AuthEnableResponse> authEnable() {
        AuthEnableRequest enableRequest = AuthEnableRequest.getDefaultInstance();
        return completable(
            obs -> this.stub.authEnable(enableRequest, obs),
            AuthEnableResponse::new);
    }

    @Override
    public CompletableFuture<AuthDisableResponse> authDisable() {
        AuthDisableRequest disableRequest = AuthDisableRequest.getDefaultInstance();
        return completable(
            obs -> this.stub.authDisable(disableRequest, obs),
            AuthDisableResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserAddResponse> userAdd(ByteSequence user, ByteSequence password) {
        requireNonNull(user, "user can't be null");
        requireNonNull(password, "password can't be null");

        AuthUserAddRequest addRequest = AuthUserAddRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(user.getBytes()))
            .setPasswordBytes(ByteString.copyFrom(password.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userAdd(addRequest, obs),
            AuthUserAddResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserDeleteResponse> userDelete(ByteSequence user) {
        requireNonNull(user, "user can't be null");

        AuthUserDeleteRequest deleteRequest = AuthUserDeleteRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(user.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userDelete(deleteRequest, obs),
            AuthUserDeleteResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserChangePasswordResponse> userChangePassword(ByteSequence user, ByteSequence password) {
        requireNonNull(user, "user can't be null");
        requireNonNull(password, "password can't be null");

        AuthUserChangePasswordRequest changePasswordRequest = AuthUserChangePasswordRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(user.getBytes()))
            .setPasswordBytes(ByteString.copyFrom(password.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userChangePassword(changePasswordRequest, obs),
            AuthUserChangePasswordResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserGetResponse> userGet(ByteSequence user) {
        requireNonNull(user, "user can't be null");

        AuthUserGetRequest userGetRequest = AuthUserGetRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(user.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userGet(userGetRequest, obs),
            AuthUserGetResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserListResponse> userList() {
        AuthUserListRequest userListRequest = AuthUserListRequest.getDefaultInstance();

        return completable(
            obs -> this.stub.userList(userListRequest, obs),
            AuthUserListResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserGrantRoleResponse> userGrantRole(ByteSequence user, ByteSequence role) {
        requireNonNull(user, "user can't be null");
        requireNonNull(role, "key can't be null");

        AuthUserGrantRoleRequest userGrantRoleRequest = AuthUserGrantRoleRequest.newBuilder()
            .setUserBytes(ByteString.copyFrom(user.getBytes()))
            .setRoleBytes(ByteString.copyFrom(role.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userGrantRole(userGrantRoleRequest, obs),
            AuthUserGrantRoleResponse::new);
    }

    @Override
    public CompletableFuture<AuthUserRevokeRoleResponse> userRevokeRole(ByteSequence user, ByteSequence role) {
        requireNonNull(user, "user can't be null");
        requireNonNull(role, "key can't be null");

        AuthUserRevokeRoleRequest userRevokeRoleRequest = AuthUserRevokeRoleRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(user.getBytes()))
            .setRoleBytes(ByteString.copyFrom(role.getBytes()))
            .build();

        return completable(
            obs -> this.stub.userRevokeRole(userRevokeRoleRequest, obs),
            AuthUserRevokeRoleResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleAddResponse> roleAdd(ByteSequence user) {
        requireNonNull(user, "user can't be null");

        AuthRoleAddRequest roleAddRequest = AuthRoleAddRequest.newBuilder().setNameBytes(ByteString.copyFrom(user.getBytes()))
            .build();

        return completable(
            obs -> this.stub.roleAdd(roleAddRequest, obs),
            AuthRoleAddResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleGrantPermissionResponse> roleGrantPermission(ByteSequence role, ByteSequence key,
        ByteSequence rangeEnd, Permission.Type permType) {
        requireNonNull(role, "role can't be null");
        requireNonNull(key, "key can't be null");
        requireNonNull(rangeEnd, "rangeEnd can't be null");
        requireNonNull(permType, "permType can't be null");

        io.etcd.jetcd.api.Permission.Type type;
        switch (permType) {
            case WRITE:
                type = io.etcd.jetcd.api.Permission.Type.WRITE;
                break;
            case READWRITE:
                type = io.etcd.jetcd.api.Permission.Type.READWRITE;
                break;
            case READ:
                type = io.etcd.jetcd.api.Permission.Type.READ;
                break;
            default:
                type = io.etcd.jetcd.api.Permission.Type.UNRECOGNIZED;
                break;
        }

        io.etcd.jetcd.api.Permission perm = io.etcd.jetcd.api.Permission.newBuilder()
            .setKey(ByteString.copyFrom(key.getBytes()))
            .setRangeEnd(ByteString.copyFrom(rangeEnd.getBytes()))
            .setPermType(type)
            .build();

        AuthRoleGrantPermissionRequest roleGrantPermissionRequest = AuthRoleGrantPermissionRequest.newBuilder()
            .setNameBytes(ByteString.copyFrom(role.getBytes()))
            .setPerm(perm)
            .build();

        return completable(
            obs -> this.stub.roleGrantPermission(roleGrantPermissionRequest, obs),
            AuthRoleGrantPermissionResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleGetResponse> roleGet(ByteSequence role) {
        requireNonNull(role, "role can't be null");

        AuthRoleGetRequest roleGetRequest = AuthRoleGetRequest.newBuilder()
            .setRoleBytes(ByteString.copyFrom(role.getBytes()))
            .build();

        return completable(
            obs -> this.stub.roleGet(roleGetRequest, obs),
            AuthRoleGetResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleListResponse> roleList() {
        AuthRoleListRequest roleListRequest = AuthRoleListRequest.getDefaultInstance();

        return completable(
            obs -> this.stub.roleList(roleListRequest, obs),
            AuthRoleListResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleRevokePermissionResponse> roleRevokePermission(ByteSequence role, ByteSequence key,
        ByteSequence rangeEnd) {
        requireNonNull(role, "role can't be null");
        requireNonNull(key, "key can't be null");
        requireNonNull(rangeEnd, "rangeEnd can't be null");

        AuthRoleRevokePermissionRequest roleRevokePermissionRequest = AuthRoleRevokePermissionRequest.newBuilder()
            .setRoleBytes(ByteString.copyFrom(role.getBytes()))
            .setKey(ByteString.copyFrom(key.getBytes()))
            .setRangeEnd(ByteString.copyFrom(rangeEnd.getBytes()))
            .build();

        return completable(
            obs -> this.stub.roleRevokePermission(roleRevokePermissionRequest, obs),
            AuthRoleRevokePermissionResponse::new);
    }

    @Override
    public CompletableFuture<AuthRoleDeleteResponse> roleDelete(ByteSequence role) {
        requireNonNull(role, "role can't be null");
        AuthRoleDeleteRequest roleDeleteRequest = AuthRoleDeleteRequest.newBuilder()
            .setRoleBytes(ByteString.copyFrom(role.getBytes()))
            .build();

        return completable(
            obs -> this.stub.roleDelete(roleDeleteRequest, obs),
            AuthRoleDeleteResponse::new);
    }
}
