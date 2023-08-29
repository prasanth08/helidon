/*
 * Copyright (c) 2019, 2023 Oracle and/or its affiliates.
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

import io.helidon.common.features.api.Feature;
import io.helidon.common.features.api.HelidonFlavor;

/**
 * Helidon Database Client Tracing.
 */
@Feature(value = "Tracing",
        description = "Database client tracing support",
        in = HelidonFlavor.SE,
        path = {"DbClient", "Tracing"}
)
module io.helidon.dbclient.tracing {

    requires io.helidon.tracing.config;
    requires io.opentracing.api;
    requires io.opentracing.util;

    requires static io.helidon.common.features.api;

    requires transitive io.helidon.dbclient;

    exports io.helidon.dbclient.tracing;

    provides io.helidon.dbclient.spi.DbClientServiceProvider with io.helidon.dbclient.tracing.DbClientTracingProvider;

}
