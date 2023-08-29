/*
 * Copyright (c) 2022, 2023 Oracle and/or its affiliates.
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

import io.helidon.webserver.observe.log.LogObserveProvider;
import io.helidon.webserver.observe.spi.ObserveProvider;

/**
 * Helidon WebServer Observability Log Support.
 * Log observability allows reading and configuring of log levels of various loggers and reading log messages.
 * <p>
 * Log endpoint is protected by default and is available at {@code /observe/log} (configurable).
 */
module io.helidon.webserver.observe.log {

    requires io.helidon.http.media.jsonp;
    requires io.helidon.webserver;
    requires java.logging;

    requires transitive io.helidon.common.config;
    requires transitive io.helidon.webserver.observe;

    exports io.helidon.webserver.observe.log;

    provides ObserveProvider with LogObserveProvider;
	
}