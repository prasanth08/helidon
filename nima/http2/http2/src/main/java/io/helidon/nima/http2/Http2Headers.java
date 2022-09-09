/*
 * Copyright (c) 2022 Oracle and/or its affiliates.
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

package io.helidon.nima.http2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.helidon.common.buffers.BufferData;
import io.helidon.common.http.Headers;
import io.helidon.common.http.HeadersServerRequest;
import io.helidon.common.http.HeadersWritable;
import io.helidon.common.http.Http;
import io.helidon.common.http.Http.Header;
import io.helidon.common.http.Http.HeaderName;
import io.helidon.common.http.Http.HeaderValue;

import static java.lang.System.Logger.Level.DEBUG;

/**
 * HTTP/2 headers combining pseudo headers and {@link io.helidon.common.http.Headers}.
 * HTTP/2 headers are all lower case.
 */
public class Http2Headers {
    /*
     * Pseudo headers
     */
    /**
     * Authority pseudo header name.
     */
    static final String AUTHORITY = ":authority";
    /**
     * Header name of the authority pseudo header.
     */
    public static final HeaderName AUTHORITY_NAME = Header.create(AUTHORITY);
    static final String METHOD = ":method";
    /**
     * Header name of the method pseudo header.
     */
    public static final HeaderName METHOD_NAME = Header.create(METHOD);
    static final String PATH = ":path";
    /**
     * Header name of the path pseudo header.
     */
    public static final HeaderName PATH_NAME = Header.create(PATH);
    static final String SCHEME = ":scheme";
    /**
     * Header name of the scheme pseudo header.
     */
    public static final HeaderName SCHEME_NAME = Header.create(SCHEME);
    static final String STATUS = ":status";
    /**
     * Header name of the status pseudo header.
     */
    public static final HeaderName STATUS_NAME = Header.create(STATUS);
    static final DynamicHeader EMPTY_HEADER_RECORD = new DynamicHeader(null, null, 0);
    private static final System.Logger LOGGER = System.getLogger(Http2Headers.class.getName());
    private static final String TRAILERS = "trailers";
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final String PATH_SLASH = "/";
    private static final String PATH_INDEX = "/index.html";
    /*
    TODO - in http/2 we may have cookies sent as separate headers or a single one
    in http/1.1, this must be concatenated into the first representation
     cookie: a=b; c=d; e=f

     cookie: a=b
     cookie: c=d
     cookie: e=f
     */
    private final Headers headers;
    private final PseudoHeaders pseudoHeaders;

    private Http2Headers(Headers httpHeaders, PseudoHeaders pseudoHeaders) {
        this.headers = httpHeaders;
        this.pseudoHeaders = pseudoHeaders;
    }

    /*
    https://www.rfc-editor.org/rfc/rfc7541.html#3.2
     */

    /**
     * Create headers from HTTP request.
     *
     * @param stream  stream that owns these headers
     * @param table   dynamic table for this connection
     * @param huffman huffman decoder
     * @param frames  frames of the headers
     * @return new headers parsed from the frames
     * @throws Http2Exception in case of protocol errors
     */
    public static Http2Headers create(Http2Stream stream,
                                      DynamicTable table,
                                      Http2HuffmanDecoder huffman,
                                      Http2FrameData... frames) {

        if (frames.length == 0) {
            return create(HeadersServerRequest.create(HeadersWritable.create()),
                          new PseudoHeaders());
        }

        // the first frame is the important one
        Http2FrameData firstFrame = frames[0];
        BufferData firstFrameData = firstFrame.data();
        Http2Flag.HeaderFlags flags = firstFrame.header().flags(Http2FrameTypes.HEADERS);

        int padLength = 0;
        if (flags.padded()) {
            padLength = firstFrameData.read();
        }

        if (flags.priority()) {
            Http2Priority priority = Http2Priority.create(firstFrameData);
            stream.priority(priority);
        }

        HeadersWritable<?> headers = HeadersWritable.create();

        BufferData[] buffers = new BufferData[frames.length];
        for (int i = 0; i < frames.length; i++) {
            Http2FrameData frame = frames[i];
            buffers[i] = frame.data();
        }
        BufferData data = BufferData.create(buffers);
        PseudoHeaders pseudoHeaders = new PseudoHeaders();
        boolean lastIsPseudoHeader = true;

        while (true) {
            if (data.available() == padLength) {
                if (padLength > 0) {
                    data.skip(padLength);
                }
                return create(HeadersServerRequest.create(headers), pseudoHeaders);
            }

            if (data.available() == 0) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Expecting more header bytes");
            }

            lastIsPseudoHeader = readHeader(headers, pseudoHeaders, table, huffman, data, lastIsPseudoHeader);
        }
    }

    /**
     * Create HTTP/2 headers from HTTP headers.
     *
     * @param headers headers to base these headers on
     * @return new HTTP/2 headers
     */
    public static Http2Headers create(Headers headers) {
        if (headers instanceof HeadersWritable) {
            return createFromWritable((HeadersWritable<?>) headers);
        }
        return createFromWritable(HeadersWritable.create(headers));
    }

    /**
     * Create from writable HTTP headers.
     *
     * @param writableHeaders header to use
     * @return new HTTP/2 headers
     */
    public static Http2Headers create(HeadersWritable<?> writableHeaders) {
        return createFromWritable(writableHeaders);
    }

    /**
     * Status pseudo header.
     *
     * @return status or null if none defined
     */
    public Http.Status status() {
        return pseudoHeaders.status();
    }

    /**
     * Path pseudo header.
     *
     * @return path or null if none defined
     */
    public String path() {
        return pseudoHeaders.path();
    }

    /**
     * Method pseudo header.
     *
     * @return method or null if none defined
     */
    public Http.Method method() {
        return pseudoHeaders.method();
    }

    /**
     * Scheme pseudo header.
     *
     * @return method or null if none defined
     */
    public String scheme() {
        return pseudoHeaders.scheme();
    }

    /**
     * Authority pseudo header.
     *
     * @return authority or null if none defined
     */
    public String authority() {
        return pseudoHeaders.authority();
    }

    /**
     * Path of the request.
     *
     * @param path HTTP path of the request
     * @return updated headers
     */
    public Http2Headers path(String path) {
        this.pseudoHeaders.path(path);
        return this;
    }

    /**
     * HTTP method to be used.
     *
     * @param method HTTP method of the request
     * @return updated headers
     */
    public Http2Headers method(Http.Method method) {
        this.pseudoHeaders.method(method);
        return this;
    }

    /**
     * Authority of the request.
     * This is an HTTP/2 pseudo-header.
     * This is an equivalent of {@code HOST} header in HTTP/1.
     *
     * @param authority authority of the request, such as {@code localhost:8080}
     * @return updated headers
     */
    public Http2Headers authority(String authority) {
        this.pseudoHeaders.authority(authority);
        return this;
    }

    /**
     * HTTP scheme, such as {@code http} or {@code https}.
     *
     * @param scheme scheme of the request
     * @return updated headeres
     */
    public Http2Headers scheme(String scheme) {
        this.pseudoHeaders.scheme(scheme);
        return this;
    }

    /**
     * HTTP headers.
     *
     * @return headers
     */
    public Headers httpHeaders() {
        return headers;
    }

    /**
     * Validate server response or client response.
     *
     * @throws Http2Exception in case the response is invalid
     */
    public void validateResponse() throws Http2Exception {
        if (!pseudoHeaders.hasStatus()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Missing :status pseudo header");
        }
        if (headers.contains(Header.CONNECTION)) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Connection in response headers");
        }
        if (pseudoHeaders.hasScheme()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, ":scheme in response headers");
        }
        if (pseudoHeaders.hasPath()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, ":path in response headers");
        }
        if (pseudoHeaders.hasMethod()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, ":method in response headers");
        }
    }

    /**
     * Validate client or server request.
     *
     * @throws Http2Exception in case the request is invalid
     */
    public void validateRequest() throws Http2Exception {
        if (pseudoHeaders.hasStatus()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, ":status in request headers");
        }
        if (headers.contains(Header.CONNECTION)) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Connection in request headers");
        }
        if (headers.contains(Header.TE)) {
            List<String> values = headers.all(Header.TE, List::of);
            if (!values.equals(List.of(TRAILERS))) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL, "te in headers with other value than trailers: \n"
                        + BufferData.create(values.toString()).debugDataHex());
            }
        }
        if (!pseudoHeaders.hasScheme()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Missing :scheme pseudo header");
        }
        if (!pseudoHeaders.hasPath()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Missing :path pseudo header");
        }
        if (!pseudoHeaders.hasMethod()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Missing :method pseudo header");
        }
    }

    @Override
    public String toString() {
        return pseudoHeaders.toString() + "\n" + headers.toString();
    }

    /**
     * Status pseudo header.
     *
     * @param status status to use
     * @return updated headers
     */
    public Http2Headers status(Http.Status status) {
        pseudoHeaders.status(status);
        return this;
    }

    /**
     * Write headers to a buffer.
     *
     * @param table         dynamic table to use
     * @param huffman       huffman encoder to use
     * @param growingBuffer buffer to write to
     */
    public void write(DynamicTable table, Http2HuffmanEncoder huffman, BufferData growingBuffer) {
        // first write pseudoheaders
        if (pseudoHeaders.hasStatus()) {
            StaticHeader indexed = null;
            Http.Status status = pseudoHeaders.status();
            if (status == Http.Status.OK_200) {
                indexed = StaticHeader.STATUS_200;
            } else if (status == Http.Status.NO_CONTENT_204) {
                indexed = StaticHeader.STATUS_204;
            } else if (status == Http.Status.PARTIAL_CONTENT_206) {
                indexed = StaticHeader.STATUS_206;
            } else if (status == Http.Status.NOT_MODIFIED_304) {
                indexed = StaticHeader.STATUS_304;
            } else if (status == Http.Status.BAD_REQUEST_400) {
                indexed = StaticHeader.STATUS_400;
            } else if (status == Http.Status.NOT_FOUND_404) {
                indexed = StaticHeader.STATUS_404;
            } else if (status == Http.Status.INTERNAL_SERVER_ERROR_500) {
                indexed = StaticHeader.STATUS_500;
            }
            if (indexed == null) {
                writeHeader(huffman,
                            table,
                            growingBuffer,
                            STATUS_NAME,
                            status().codeText(),
                            true,
                            false);
            } else {
                writeHeader(growingBuffer, indexed);
            }
        }
        if (pseudoHeaders.hasMethod()) {
            Http.Method method = pseudoHeaders.method();
            StaticHeader indexed = null;
            if (method == Http.Method.GET) {
                indexed = StaticHeader.METHOD_GET;
            } else if (method == Http.Method.POST) {
                indexed = StaticHeader.METHOD_POST;
            }
            if (indexed == null) {
                writeHeader(huffman, table, growingBuffer, METHOD_NAME, method.text(), true, false);
            } else {
                writeHeader(growingBuffer, indexed);
            }
        }
        if (pseudoHeaders.hasScheme()) {
            String scheme = pseudoHeaders.scheme();
            if (scheme.equals(HTTP)) {
                writeHeader(growingBuffer, StaticHeader.SCHEME_HTTP);
            } else if (scheme.equals(HTTPS)) {
                writeHeader(growingBuffer, StaticHeader.SCHEME_HTTPS);
            } else {
                writeHeader(huffman, table, growingBuffer, SCHEME_NAME, scheme, true, false);
            }
        }
        if (pseudoHeaders.hasPath()) {
            String path = pseudoHeaders.path();
            if (path.equals(PATH_SLASH)) {
                writeHeader(growingBuffer, StaticHeader.PATH_ROOT);
            } else if (path.equals(PATH_INDEX)) {
                writeHeader(growingBuffer, StaticHeader.PATH_INDEX);
            } else {
                writeHeader(huffman, table, growingBuffer, PATH_NAME, path, true, false);
            }
        }
        if (pseudoHeaders.hasAuthority()) {
            writeHeader(huffman, table, growingBuffer, AUTHORITY_NAME, pseudoHeaders.authority, true, false);
        }

        for (HeaderValue header : headers) {
            String value = header.value();
            boolean shouldIndex = !header.changing();
            boolean neverIndex = header.sensitive();

            writeHeader(huffman, table, growingBuffer, header.headerName(), value, shouldIndex, neverIndex);
        }
    }

    private static Http2Headers create(HeadersServerRequest httpHeaders, PseudoHeaders pseudoHeaders) {
        return new Http2Headers(httpHeaders, pseudoHeaders);
    }

    private static boolean readHeader(HeadersWritable<?> headers,
                                      PseudoHeaders pseudoHeaders,
                                      DynamicTable table,
                                      Http2HuffmanDecoder huffman,
                                      BufferData data,
                                      boolean lastIsPseudoHeader) {
        // find out what kind of header we have
        HeaderApproach approach = HeaderApproach.resolve(data);

        if (approach.tableSizeUpdate) {
            table.maxTableSize(approach.number);
            if (headers.size() > 0 || pseudoHeaders.size() > 0) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION, "Table size update is after headers");
            }
            return lastIsPseudoHeader;
        } else {
            HeaderRecord record = EMPTY_HEADER_RECORD;
            HeaderName headerName;
            String value;

            if (approach.number != 0) {
                record = table.get(approach.number);
            }

            if (approach.hasName) {
                // read from bytes
                String name;
                try {
                    name = readString(huffman, data);
                } catch (IllegalArgumentException e) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Received a header with"
                            + " non ASCII character(s)\n" + data.debugDataHex(true));
                }
                if (!(name.toLowerCase(Locale.ROOT).equals(name))) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL,
                                             "Received a header with uppercase letters\n"
                                                     + BufferData.create(name).debugDataHex());
                }
                if (name.charAt(0) == ':') {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL,
                                             "Received invalid pseudo-header field (or explicit value instead of indexed)\n"
                                                     + BufferData.create(name).debugDataHex());
                }
                headerName = Header.create(name);
            } else {
                headerName = record.headerName();
            }

            boolean isPseudoHeader = false;
            if (headerName != null && headerName.isPseudoHeader()) {
                isPseudoHeader = true;
                // pseudo header field
                if (!lastIsPseudoHeader) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Pseudo header field appears after regular "
                            + "header");
                }
            }

            if (approach.hasValue) {
                // read from bytes
                value = readString(huffman, data);
            } else {
                value = record.value();
                if (value == null) {
                    value = ""; // fallback to empty string - this is probably an error on client side
                }
            }

            if (isPseudoHeader) {
                if (value == null) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Value of a pseudo header must not be null");
                }
                if (headerName.equals(PATH_NAME) && value.length() == 0) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL, ":path pseudo header has empty value");
                }
                if (headerName.equals(PATH_NAME)) {
                    validateAndSetPseudoHeader(headerName, pseudoHeaders::hasPath, pseudoHeaders::path, value);
                } else if (headerName.equals(AUTHORITY_NAME)) {
                    validateAndSetPseudoHeader(headerName, pseudoHeaders::hasAuthority, pseudoHeaders::authority, value);
                } else if (headerName.equals(METHOD_NAME)) {
                    validateAndSetPseudoHeader(headerName, pseudoHeaders::hasMethod, pseudoHeaders::method, value);
                } else if (headerName.equals(SCHEME_NAME)) {
                    validateAndSetPseudoHeader(headerName, pseudoHeaders::hasScheme, pseudoHeaders::scheme, value);
                } else if (headerName.equals(STATUS_NAME)) {
                    validateAndSetPseudoHeader(headerName, pseudoHeaders::hasStatus, pseudoHeaders::status, value);
                }
            } else {
                if (headerName == null || value == null) {
                    String tHeaderName = headerName == null ? "null" : headerName.lowerCase();
                    String tValue = value == null
                            ? "null"
                            : BufferData.create(value.getBytes(StandardCharsets.US_ASCII))
                                    .debugDataHex();

                    throw new Http2Exception(Http2ErrorCode.COMPRESSION,
                                             "Failed to get name or value. Name: "
                                                     + tHeaderName
                                                     + ", value "
                                                     + tValue);
                }
            }

            if (approach.addToIndex) {
                table.add(headerName, value);
            }

            if (!isPseudoHeader) {
                headers.add(HeaderValue.create(headerName,
                                               !approach.addToIndex,
                                               approach.neverIndex,
                                               value));
            }
            return isPseudoHeader;
        }
    }

    private static String readString(Http2HuffmanDecoder huffman, BufferData data) {
        if (data.available() < 1) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION, "No data available to read header");
        }
        int first = data.read();

        boolean isHuffman = (first & 0b10000000) != 0;
        //   0   1   2   3   4   5   6   7
        // +---+---+-----------------------+
        // | H |     Name Length (7+)      |
        // +---+---------------------------+
        // |  Name String (Length octets)  |
        // +---+---+-----------------------+
        // | H |     Value Length (7+)     |
        // +---+---------------------------+
        // | Value String (Length octets)  |
        // +-------------------------------+
        int length = data.readHpackInt(first, 7);

        if (isHuffman) {
            return huffman.decodeString(data, length);
        } else {
            return data.readString(length);
        }
    }

    private static void validateAndSetPseudoHeader(HeaderName name,
                                                   Supplier<Boolean> isSet,
                                                   Consumer<String> setter,
                                                   String value) {
        if (isSet.get()) {
            // it is safe to print the pseudo header name here, as we are sure it came from StaticHeader index
            throw new Http2Exception(Http2ErrorCode.PROTOCOL, "Duplicated pseudo header: " + name);
        }
        setter.accept(value);
    }

    private static Http2Headers createFromWritable(HeadersWritable<?> headers) {
        PseudoHeaders pseudoHeaders = new PseudoHeaders();
        removeFromHeadersAddToPseudo(headers, pseudoHeaders::status, STATUS_NAME);
        removeFromHeadersAddToPseudo(headers, pseudoHeaders::path, PATH_NAME);
        removeFromHeadersAddToPseudo(headers, pseudoHeaders::authority, AUTHORITY_NAME);
        removeFromHeadersAddToPseudo(headers, pseudoHeaders::scheme, SCHEME_NAME);
        removeFromHeadersAddToPseudo(headers, pseudoHeaders::method, METHOD_NAME);

        headers.remove(Header.HOST, it -> {
            if (!pseudoHeaders.hasAuthority()) {
                pseudoHeaders.authority(it.value());
            }
        });

        return new Http2Headers(headers, pseudoHeaders);
    }

    /*
    +---------------+
    |Pad Length? (8)|
    +-+-------------+-----------------------------------------------+
    |E|                 Stream Dependency? (31)                     |
    +-+-------------+-----------------------------------------------+
    |  Weight? (8)  |
    +-+-------------+-----------------------------------------------+
    |                   Header Block Fragment (*)                 ...
    +---------------------------------------------------------------+
    |                           Padding (*)                       ...
    +---------------------------------------------------------------+
       Pad Length:  An 8-bit field containing the length of the frame
      padding in units of octets.  This field is only present if the
      PADDED flag is set.

   E: A single-bit flag indicating that the stream dependency is
      exclusive (see Section 5.3).  This field is only present if the
      PRIORITY flag is set.

   Stream Dependency:  A 31-bit stream identifier for the stream that
      this stream depends on (see Section 5.3).  This field is only
      present if the PRIORITY flag is set.

   Weight:  An unsigned 8-bit integer representing a priority weight for
      the stream (see Section 5.3).  Add one to the value to obtain a
      weight between 1 and 256.  This field is only present if the
      PRIORITY flag is set.
     */

    private static void removeFromHeadersAddToPseudo(HeadersWritable<?> headers,
                                                     Consumer<String> valueConsumer,
                                                     HeaderName pseudoHeader) {
        headers.remove(pseudoHeader, it -> valueConsumer.accept(it.value()));
    }

    private void writeHeader(Http2HuffmanEncoder huffman, DynamicTable table,
                             BufferData buffer,
                             HeaderName name,
                             String value,
                             boolean shouldIndex,
                             boolean neverIndex) {
        IndexedHeaderRecord record = table.find(name, value);
        HeaderApproach approach;

        if (record == null) {
            // neither name nor value exists in an index
            if (shouldIndex) {
                table.add(name, value);
            }
            approach = new HeaderApproach(shouldIndex,
                                          neverIndex,
                                          true,
                                          true,
                                          0);
        } else {
            // at least name is available in index, maybe even value
            if (value.equals(record.value())) {
                // this is the exact same name and value
                approach = new HeaderApproach(false,
                                              neverIndex,
                                              false,
                                              false,
                                              record.index());
            } else {
                // same name
                if (shouldIndex) {
                    table.add(name, value);
                }
                // in both cases, we use index to record name
                approach = new HeaderApproach(shouldIndex,
                                              neverIndex,
                                              false,
                                              true,
                                              record.index());
            }
        }

        approach.write(huffman, buffer, name, value);
    }

    private void writeHeader(BufferData buffer,
                             StaticHeader header) {
        new HeaderApproach(false, false, false, false, header.index)
                .write(buffer);
    }

    enum StaticHeader implements IndexedHeaderRecord {
        AUTHORITY(1, AUTHORITY_NAME, true),
        METHOD_GET(2, METHOD_NAME, "GET"),
        METHOD_POST(3, METHOD_NAME, "POST"),
        PATH_ROOT(4, PATH_NAME, "/"),
        PATH_INDEX(5, PATH_NAME, "/index.html"),
        SCHEME_HTTP(6, SCHEME_NAME, "http"),
        SCHEME_HTTPS(7, SCHEME_NAME, "https"),
        STATUS_200(8, STATUS_NAME, "200"),
        STATUS_204(9, STATUS_NAME, "204"),
        STATUS_206(10, STATUS_NAME, "206"),
        STATUS_304(11, STATUS_NAME, "304"),
        STATUS_400(12, STATUS_NAME, "400"),
        STATUS_404(13, STATUS_NAME, "404"),
        STATUS_500(14, STATUS_NAME, "500"),
        ACCEPT_CHARSET(15, Header.ACCEPT_CHARSET),
        ACCEPT_ENCODING(16, Header.ACCEPT_ENCODING, "gzip, deflate", false),
        ACCEPT_LANGUAGE(17, Header.ACCEPT_LANGUAGE),
        ACCEPT_RANGES(18, Header.ACCEPT_RANGES),
        ACCEPT(19, Header.ACCEPT),
        ACCESS_CONTROL_ALLOW_ORIGIN(20, Header.ACCESS_CONTROL_ALLOW_ORIGIN),
        AGE(21, Header.AGE),
        ALLOW(22, Header.ALLOW),
        AUTHORIZATION(23, Header.AUTHORIZATION),
        CACHE_CONTROL(24, Header.CACHE_CONTROL),
        CONTENT_DISPOSITION(25, Header.CONTENT_DISPOSITION),
        CONTENT_ENCODING(26, Header.CONTENT_ENCODING),
        CONTENT_LANGUAGE(27, Header.CONTENT_LANGUAGE),
        CONTENT_LENGTH(28, Header.CONTENT_LENGTH),
        CONTENT_LOCATION(29, Header.CONTENT_LOCATION),
        CONTENT_RANGE(30, Header.CONTENT_RANGE),
        CONTENT_TYPE(31, Header.CONTENT_TYPE),
        COOKIE(32, Header.COOKIE),
        DATE(33, Header.DATE),
        ETAG(34, Header.ETAG),
        EXPECT(35, Header.EXPECT),
        EXPIRES(36, Header.EXPIRES),
        FROM(37, Header.FROM),
        HOST(38, Header.HOST),
        IF_MATCH(39, Header.IF_MATCH),
        IF_MODIFIED_SINCE(40, Header.IF_MODIFIED_SINCE),
        IF_NONE_MATCH(41, Header.IF_NONE_MATCH),
        IF_RANGE(42, Header.IF_RANGE),
        IF_UNMODIFIED_SINCE(43, Header.IF_UNMODIFIED_SINCE),
        LAST_MODIFIED(44, Header.LAST_MODIFIED),
        LINK(45, Header.LINK),
        LOCATION(46, Header.LOCATION),
        MAX_FORWARDS(47, Header.MAX_FORWARDS),
        PROXY_AUTHENTICATE(48, Header.PROXY_AUTHENTICATE),
        PROXY_AUTHORIZATION(49, Header.PROXY_AUTHORIZATION),
        RANGE(50, Header.CONTENT_LOCATION),
        REFERER(51, Header.REFERER),
        REFRESH(52, Header.REFRESH),
        RETRY_AFTER(53, Header.RETRY_AFTER),
        SERVER(54, Header.SERVER),
        SET_COOKIE(55, Header.SET_COOKIE),
        STRICT_TRANSPORT_SECURITY(56, Header.STRICT_TRANSPORT_SECURITY),
        TRANSFER_ENCODING(57, Header.TRANSFER_ENCODING),
        USER_AGENT(58, Header.USER_AGENT),
        VARY(59, Header.VARY),
        VIA(60, Header.VIA),
        WWW_AUTHENTICATE(61, Header.WWW_AUTHENTICATE);

        /**
         * Maximal index of the static table of headers.
         */
        public static final int MAX_INDEX;

        private static final Map<Integer, StaticHeader> BY_INDEX = new HashMap<>();
        private static final Map<String, StaticHeader> BY_NAME_NO_VALUE = new HashMap<>();
        private static final Map<String, Map<String, StaticHeader>> BY_NAME_VALUE = new HashMap<>();

        static {
            int maxIndex = 0;

            for (StaticHeader predefinedHeader : StaticHeader.values()) {
                BY_INDEX.put(predefinedHeader.index(), predefinedHeader);
                maxIndex = Math.max(maxIndex, predefinedHeader.index);
                if (predefinedHeader.hasValue()) {
                    BY_NAME_VALUE.computeIfAbsent(predefinedHeader.headerName().lowerCase(), it -> new HashMap<>())
                            .put(predefinedHeader.value(), predefinedHeader);
                } else {
                    BY_NAME_NO_VALUE.put(predefinedHeader.headerName().lowerCase(), predefinedHeader);
                }
            }

            MAX_INDEX = maxIndex;
        }

        private final boolean isPseudoHeader;
        private final int index;
        private final HeaderName name;
        private final String value;
        private final boolean hasValue;

        StaticHeader(int index, HeaderName name) {
            this(index, name, false);
        }

        StaticHeader(int index, HeaderName name, boolean isPseudoHeader) {
            this.index = index;
            this.name = name;
            this.value = null;
            this.hasValue = false;
            this.isPseudoHeader = isPseudoHeader;
        }

        StaticHeader(int index, HeaderName name, String value) {
            this(index, name, value, true);
        }

        StaticHeader(int index, HeaderName name, String value, boolean isPseudoHeader) {
            this.index = index;
            this.name = name;
            this.value = value;
            this.hasValue = true;
            this.isPseudoHeader = isPseudoHeader;
        }

        static StaticHeader get(int index) {
            if (index > MAX_INDEX) {
                throw new IllegalArgumentException("Max index for predefined headers is " + MAX_INDEX
                                                           + ", but requested " + index);
            }
            return BY_INDEX.get(index);
        }

        static StaticHeader find(HeaderName headerName, String headerValue) {
            Map<String, StaticHeader> map = BY_NAME_VALUE.get(headerName.lowerCase());
            if (map == null) {
                return BY_NAME_NO_VALUE.get(headerName.lowerCase());
            }
            StaticHeader staticHeader = map.get(headerValue);
            if (staticHeader == null) {
                return BY_NAME_NO_VALUE.get(headerName.lowerCase());
            }
            return staticHeader;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public HeaderName headerName() {
            return name;
        }

        @Override
        public String value() {
            return value;
        }

        boolean hasValue() {
            return hasValue;
        }
    }

    interface HeaderRecord {
        HeaderName headerName();

        String value();
    }

    interface IndexedHeaderRecord extends HeaderRecord {
        int index();
    }

    private static class HeaderApproach {
        private final boolean addToIndex;
        private final boolean neverIndex;
        private final boolean hasName;
        private final boolean hasValue;
        private final boolean tableSizeUpdate;
        private final int number;

        private HeaderApproach(boolean addToIndex,
                               boolean neverIndex,
                               boolean hasName,
                               boolean hasValue,
                               int number) {
            this.addToIndex = addToIndex;
            this.neverIndex = neverIndex;
            this.hasName = hasName;
            this.hasValue = hasValue;
            this.tableSizeUpdate = false;
            this.number = number;
        }

        HeaderApproach(int size) {
            this.tableSizeUpdate = true;
            this.number = size;
            this.addToIndex = false;
            this.neverIndex = false;
            this.hasValue = false;
            this.hasName = false;
        }

        static HeaderApproach resolve(BufferData data) {
            int value = data.read();
            HeaderApproach approach = resolve(data, value);
            //            System.out.println("Decoded value " + Integer.toBinaryString(value) + " as " + approach + " (maybe
            //            used more bytes)");
            return approach;
        }

        static HeaderApproach resolve(BufferData data, int value) {
            /*
            The mask has 8 bits
             0 0 0 0 0 0 0 0 - do not add to index, custom name and custom value
             0 1 0 0 0 0 0 0 - add to index (custom name and value)
             0 0 0 1 0 0 0 0 - never index, custom name and custom value
             0 0 0 0 - do not add to index, indexed name and custom value
             0 0 0 1 - never index, indexed name and custom value
             0 0 1 - dynamic table size update
             1 - indexed
             0 1 - indexed name with custom value, add to index
            */

            // literal header field with no indexing - 0b00000000
            if (value == 0) {
                return new HeaderApproach(false, false, true, true, 0);
            }
            // literal header field with indexing - 0b01000000
            if (value == 0b01000000) {
                return new HeaderApproach(true, false, true, true, 0);
            }
            // literal header value without indexing - 0b00010000
            if (value == 0b00010000) {
                return new HeaderApproach(false, true, true, true, 0);
            }

            // name and value from index - 0b1xxxxxxx
            if ((value & 0b10000000) != 0) {
                int indexPart = data.readHpackInt(value, 7);
                return new HeaderApproach(false, false, false, false, indexPart);
            }

            // name from index, literal value, index - 0b01xxxxxx
            if ((value & 0b11000000) == 0b01000000) {
                int indexPart = data.readHpackInt(value, 6);
                return new HeaderApproach(true, false, false, true, indexPart);
            }

            // dynamic table size update
            if ((value & 0b11100000) == 0b00100000) {
                int size = data.readHpackInt(value, 5);
                return new HeaderApproach(size);
            }

            if ((value & 0b11110000) == 0) {
                int indexPart = data.readHpackInt(value, 4);
                return new HeaderApproach(false, false, false, true, indexPart);
            }
            if ((value & 0b11110000) == 0b00010000) {
                int indexPart = data.readHpackInt(value, 4);
                return new HeaderApproach(false, true, false, true, indexPart);
            }

            throw new Http2Exception(Http2ErrorCode.COMPRESSION,
                                     "Header approach cannot be determined from value " + value);
        }

        @Override
        public String toString() {
            if (tableSizeUpdate) {
                return "table_size_update: " + number;
            } else {
                return (addToIndex ? "do_index" : "do_not_index")
                        + ", " + (neverIndex ? "never_index" : "may_index")
                        + ", " + (hasName ? "name_from_stream" : "name_from_indexed")
                        + ", " + (hasValue ? "value_from_stream" : "value_from_indexed")
                        + ", " + number;
            }
        }

        public boolean nameFromIndex() {
            return !hasName;
        }

        public void write(Http2HuffmanEncoder huffman, BufferData buffer, HeaderName headerName, String value) {
            /*
             0   1   2   3   4   5   6   7
           +---+---+---+---+---+---+---+---+
           | 0 | 0 | 0 | 0 |  Index (4+)   |
           +---+---+-----------------------+
           | H |     Value Length (7+)     |
           +---+---------------------------+
           | Value String (Length octets)  |
           +-------------------------------+
             */
            // write flags + index beginning
            boolean hasValue = hasValue();

            if (neverIndex()) {
                if (hasName()) {
                    // never indexed, custom name and value
                    buffer.writeInt8(0b00010000);
                } else {
                    // indexed name
                    if (!hasValue) {
                        // this is garbage, cannot "never index" a header that is already indexed
                        if (LOGGER.isLoggable(DEBUG)) {
                            LOGGER.log(DEBUG, "Never index on field with indexed value: " + headerName + ": " + value);
                        }

                        hasValue = true;
                    }
                    buffer.writeHpackInt(number, 0b00010000, 4);
                }
            } else if (addToIndex()) {
                if (hasName) {
                    // index, custom name and value
                    buffer.writeInt8(0b01000000);
                } else {
                    // index and name from index
                    if (!hasValue) {
                        if (LOGGER.isLoggable(DEBUG)) {
                            LOGGER.log(DEBUG, "Index on field with indexed value: " + headerName + ": " + value);
                        }
                        hasValue = true;
                    }
                    buffer.writeHpackInt(number, 0b01000000, 6);
                }
            } else {
                // do not add to index
                if (hasName) {
                    // custom name and value
                    buffer.write(0);
                } else {
                    // indexed name
                    if (hasValue) {
                        // indexed name, custom value
                        buffer.writeHpackInt(number, 0, 4);
                    } else {
                        // indexed name, indexed value
                        buffer.writeHpackInt(number, 0b10000000, 7);
                    }
                }
            }

            if (hasName) {
                String name = headerName.lowerCase();
                if (name.length() > 3) {
                    huffman.encode(buffer, name);
                } else {
                    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
                    buffer.writeHpackInt(nameBytes.length, 0, 7);
                    buffer.write(nameBytes);
                }

            }
            if (hasValue) {
                if (value.length() > 3) {
                    huffman.encode(buffer, value);
                } else {
                    byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
                    buffer.writeHpackInt(valueBytes.length, 0, 7);
                    buffer.write(valueBytes);
                }

            }
        }

        public boolean hasValue() {
            return hasValue;
        }

        public boolean neverIndex() {
            return neverIndex;
        }

        public boolean hasName() {
            return hasName;
        }

        public boolean addToIndex() {
            return addToIndex;
        }

        // write fully indexed value
        void write(BufferData buffer) {
            buffer.writeHpackInt(number, 0b10000000, 7);
        }
    }

    /**
     * There is one dynamic table for inbound headers and one for outbound headers for each connection.
     * This is to minimize size of headers on the transport.
     * The table caches header names and values and then uses indexes only when transferring headers over network.
     */
    public static class DynamicTable {
        private final List<DynamicHeader> headers = new ArrayList<>();
        private volatile long protocolMaxTableSize;
        private long maxTableSize;
        private int currentTableSize;

        private DynamicTable(long protocolMaxTableSize) {
            this.protocolMaxTableSize = protocolMaxTableSize;
            this.maxTableSize = protocolMaxTableSize;
        }

        /**
         * Create dynamic header table with the defined size.
         *
         * @param maxTableSize size in bytes
         * @return new dynamic table
         */
        public static DynamicTable create(long maxTableSize) {
            return new DynamicTable(maxTableSize);
        }

        static DynamicTable create(Http2Settings settings) {
            return create(settings.value(Http2Setting.HEADER_TABLE_SIZE));
        }

        /**
         * Update protocol max table size.
         *
         * @param number maximal table size in bytes
         */
        public void protocolMaxTableSize(long number) {
            this.protocolMaxTableSize = number;
        }

        HeaderRecord get(int index) {
            if (index > StaticHeader.MAX_INDEX) {
                return doGet(index - StaticHeader.MAX_INDEX);
            } else {
                return StaticHeader.get(index);
            }
        }

        void maxTableSize(long number) {
            if (number > protocolMaxTableSize) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION, "Attempt to set larger size than protocol max");
            }
            this.maxTableSize = number;
            if (maxTableSize == 0) {
                headers.clear();
            }
            while (maxTableSize < currentTableSize) {
                evict();
            }
        }

        int add(HeaderName headerName, String headerValue) {
            String name = headerName.lowerCase();
            int size = name.length() + headerValue.getBytes(StandardCharsets.US_ASCII).length + 32;

            if (currentTableSize + size <= maxTableSize) {
                return add(headerName, headerValue, size);
            }

            while ((currentTableSize + size) > maxTableSize) {
                evict();
                if (currentTableSize <= 0) {
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION,
                                             "Cannot add header record, max table size too low. "
                                                     + "current size: " + currentTableSize + ", max size: " + maxTableSize + ","
                                                     + " header size: " + size);
                }
            }
            return add(headerName, headerValue, size);
        }

        long protocolMaxTableSize() {
            return protocolMaxTableSize;
        }

        long maxTableSize() {
            return maxTableSize;
        }

        int currentTableSize() {
            return currentTableSize;
        }

        private IndexedHeaderRecord find(HeaderName headerName, String headerValue) {
            StaticHeader staticHeader = StaticHeader.find(headerName, headerValue);
            IndexedHeaderRecord candidate = null;

            if (staticHeader != null) {
                if (staticHeader.name.equals(headerName)
                        && staticHeader.hasValue
                        && staticHeader.value().equals(headerValue)) {
                    return staticHeader;
                }
                candidate = staticHeader;
            }
            for (int i = 0; i < headers.size(); i++) {
                DynamicHeader header = headers.get(i);
                if (header.headerName.equals(headerName)) {
                    if (header.value().equals(headerValue)) {
                        return new IndexedHeader(header, StaticHeader.MAX_INDEX + i + 1);
                    }
                    if (candidate == null) {
                        candidate = new IndexedHeader(header, StaticHeader.MAX_INDEX + i + 1);
                    }
                }
            }

            return candidate;
        }

        private void evict() {
            if (headers.isEmpty()) {
                return;
            }
            DynamicHeader removed = headers.remove(headers.size() - 1);

            if (removed != null) {
                currentTableSize -= removed.size();
            }
        }

        private int add(HeaderName name, String value, int size) {
            headers.add(0, new DynamicHeader(name, value, size));
            currentTableSize += size;
            return 0;
        }

        private HeaderRecord doGet(int index) {
            try {
                // table is 1 based, list is 0 based
                return headers.get(index - 1);
            } catch (IndexOutOfBoundsException e) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL,
                                         "Dynamic table does not contain required header at index " + index);
            }
        }
    }

    private static record DynamicHeader(HeaderName headerName, String value, int size) implements HeaderRecord {
    }

    private static record IndexedHeader(HeaderRecord delegate, int index) implements IndexedHeaderRecord {
        @Override
        public HeaderName headerName() {
            return delegate().headerName();
        }

        @Override
        public String value() {
            return delegate.value();
        }
    }

    private static class PseudoHeaders {
        private String authority;
        private Http.Method method;
        private String path;
        private String scheme;
        private Http.Status status;
        private int size;

        public int size() {
            return size;
        }

        @Override
        public String toString() {
            return "PseudoHeaders{"
                    + "authority='" + authority + '\''
                    + ", method=" + method
                    + ", path='" + path + '\''
                    + ", scheme='" + scheme + '\''
                    + ", status=" + status
                    + '}';
        }

        PseudoHeaders authority(String authority) {
            this.authority = authority;
            size++;
            return this;
        }

        void method(String method) {
            method(Http.Method.create(method));
        }

        PseudoHeaders method(Http.Method method) {
            this.method = method;
            size++;
            return this;
        }

        PseudoHeaders path(String path) {
            this.path = path;
            size++;
            return this;
        }

        PseudoHeaders scheme(String scheme) {
            this.scheme = scheme;
            size++;
            return this;
        }

        void status(String status) {
            status(Http.Status.create(Integer.parseInt(status)));
        }

        PseudoHeaders status(Http.Status status) {
            this.status = status;
            size++;
            return this;
        }

        boolean hasAuthority() {
            return authority != null;
        }

        String authority() {
            return authority;
        }

        boolean hasMethod() {
            return method != null;
        }

        Http.Method method() {
            return method;
        }

        boolean hasPath() {
            return path != null;
        }

        String path() {
            return path;
        }

        boolean hasScheme() {
            return scheme != null;
        }

        String scheme() {
            return scheme;
        }

        boolean hasStatus() {
            return status != null;
        }

        Http.Status status() {
            return status;
        }
    }

}