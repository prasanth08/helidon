/*
 * Copyright (c) 2019, 2024 Oracle and/or its affiliates.
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

import io.helidon.grpc.core.JsonbMarshaller;
import io.helidon.grpc.core.MarshallerSupplier;

/**
 * gRPC Core Module.
 */
module io.helidon.grpc.core {
    exports io.helidon.grpc.core;

    requires io.helidon.tracing;
    requires transitive io.helidon.config;
    requires transitive io.helidon.config.objectmapping;
    requires transitive io.helidon.common.configurable;
    requires transitive io.helidon.common;
    requires io.helidon.common.context;
    requires io.helidon.common.http;

    requires io.grpc.netty;
    requires transitive io.grpc.protobuf;
    requires transitive io.grpc.stub;
    requires transitive io.grpc;
    requires io.netty.handler;
    requires io.netty.transport;
    requires transitive com.google.protobuf;

    requires jakarta.annotation;
    requires static jakarta.json.bind;
    requires java.logging;
    requires java.naming;

    requires jakarta.inject;

    requires static io.helidon.config.metadata;

    provides MarshallerSupplier with
            MarshallerSupplier.DefaultMarshallerSupplier,
            MarshallerSupplier.ProtoMarshallerSupplier,
            JsonbMarshaller.Supplier;
}
