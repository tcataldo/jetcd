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

package io.etcd.jetcd.resolver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.etcd.jetcd.common.exception.ErrorCode;
import io.etcd.jetcd.common.exception.EtcdExceptionFactory;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;

public class IPNameResolver extends AbstractNameResolver {
    public static final String SCHEME = "ip";

    private final List<HostPort> addresses;

    public IPNameResolver(URI targetUri) {
        super(targetUri);

        this.addresses = Stream.of(targetUri.getPath().split(","))
            .map(address -> address.startsWith("/") ? address.substring(1) : address)
            .map(HostPort::fromString)
            .collect(Collectors.toList());
    }

    @Override
    protected List<EquivalentAddressGroup> computeAddressGroups() {
        if (addresses.isEmpty()) {
            throw EtcdExceptionFactory.newEtcdException(
                ErrorCode.INVALID_ARGUMENT,
                "Unable to resolve endpoint " + getTargetUri());
        }

        return addresses.stream()
            .map(address -> {
                return new EquivalentAddressGroup(
                    new InetSocketAddress(
                        address.getHost(),
                        address.getPortOrDefault(ETCD_CLIENT_PORT)),
                    getServiceAuthority() == null || getServiceAuthority().isEmpty()
                        ? Attributes.newBuilder()
                            .set(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE, address.toString())
                            .build()
                        : Attributes.EMPTY);
            })
            .collect(Collectors.toList());
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort fromString(String s) {
            int colon = s.lastIndexOf(':');
            if (colon < 0) {
                return new HostPort(s, -1);
            }
            return new HostPort(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
        }

        String getHost() {
            return host;
        }

        int getPortOrDefault(int defaultPort) {
            return port < 0 ? defaultPort : port;
        }

        @Override
        public String toString() {
            return port >= 0 ? host + ":" + port : host;
        }
    }
}
