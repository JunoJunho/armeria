/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.internal.common.thrift.ThriftFunction;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * An {@link RpcService} that handles a Thrift {@link RpcRequest}.
 *
 * @see THttpService
 */
public final class ThriftCallService implements RpcService {

    private static final Logger logger = LoggerFactory.getLogger(ThriftCallService.class);

    private static final AsyncMethodCallback<Object> ONEWAY_CALLBACK = new AsyncMethodCallback<Object>() {
        @Override
        public void onComplete(Object response) {}

        @Override
        public void onError(Exception e) {
            logOneWayFunctionFailure(RequestContext.currentOrNull(), e);
        }
    };

    /**
     * Creates a new {@link ThriftCallService} with the specified service implementation.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     */
    public static ThriftCallService of(Object implementation) {
        requireNonNull(implementation, "implementation");
        return new ThriftCallService(ImmutableMap.of("", ImmutableList.of(implementation)));
    }

    /**
     * Creates a new multiplexed {@link ThriftCallService} with the specified list service implementations.
     *
     * @param implementations a {@link Map} whose key is service name and value is the list of implementations
     *                        of {@code *.Iface} or {@code *.AsyncIface} service interface generated by
     *                        the Apache Thrift compiler
     */
    public static ThriftCallService of(Map<String, ? extends Iterable<?>> implementations) {
        requireNonNull(implementations, "implementations");
        return new ThriftCallService(implementations);
    }

    private final Map<String, ThriftServiceEntry> entries;

    private ThriftCallService(Map<String, ? extends Iterable<?>> implementations) {
        requireNonNull(implementations, "implementations");
        if (implementations.isEmpty()) {
            throw new IllegalArgumentException("empty implementations");
        }

        entries = implementations.entrySet().stream().collect(
                toImmutableMap(Map.Entry::getKey, ThriftServiceEntry::new));
    }

    /**
     * Returns the information about the Thrift services being served.
     *
     * @return a {@link Map} whose key is a service name, which could be an empty string if this service
     *         is not multiplexed
     */
    public Map<String, ThriftServiceEntry> entries() {
        return entries;
    }

    @Override
    public RpcResponse serve(ServiceRequestContext ctx, RpcRequest call) throws Exception {
        final int colonPos = call.method().indexOf(':');
        final String method;
        final String serviceName;
        if (colonPos < 0) {
            serviceName = "";
            method = call.method();
        } else {
            serviceName = call.method().substring(0, colonPos);
            method = call.method().substring(colonPos + 1);
        }

        // Ensure that such a service exists.
        final ThriftServiceEntry e = entries.get(serviceName);
        if (e != null) {
            // Ensure that such a method exists.
            final ThriftFunction f = e.metadata.function(method);
            if (f != null) {
                if (f.implementation() != null) {
                    final DefaultRpcResponse reply = new DefaultRpcResponse();
                    invoke(ctx, f.implementation(), f, call.params(), reply);
                    return reply;
                }
                // Should never reach here because of the way ThriftServiceEntry is created
                return RpcResponse.ofFailure(new TApplicationException(
                        TApplicationException.UNKNOWN, "null implementation: " + call.method()));
            }
        }
        return RpcResponse.ofFailure(new TApplicationException(
                TApplicationException.UNKNOWN_METHOD, "unknown method: " + call.method()));
    }

    private static void invoke(
            ServiceRequestContext ctx,
            Object impl, ThriftFunction func, List<Object> args, DefaultRpcResponse reply) {

        try {
            final TBase<?, ?> tArgs = func.newArgs(args);
            if (func.isAsync()) {
                invokeAsynchronously(impl, func, tArgs, reply);
            } else {
                invokeSynchronously(ctx, impl, func, tArgs, reply);
            }
        } catch (Throwable t) {
            reply.completeExceptionally(t);
        }
    }

    private static void invokeAsynchronously(Object impl, ThriftFunction func, TBase<?, ?> args,
                                             DefaultRpcResponse reply) throws TException {

        final AsyncProcessFunction<Object, TBase<?, ?>, Object> f = func.asyncFunc();
        if (func.isOneWay()) {
            f.start(impl, args, ONEWAY_CALLBACK);
            reply.complete(null);
        } else {
            f.start(impl, args, new AsyncMethodCallback<Object>() {
                @Override
                public void onComplete(Object response) {
                    reply.complete(response);
                }

                @Override
                public void onError(Exception e) {
                    reply.completeExceptionally(e);
                }
            });
        }
    }

    private static void invokeSynchronously(
            ServiceRequestContext ctx, Object impl,
            ThriftFunction func, TBase<?, ?> args, DefaultRpcResponse reply) {

        final ProcessFunction<Object, TBase<?, ?>> f = func.syncFunc();
        ctx.blockingTaskExecutor().execute(() -> {
            if (reply.isDone()) {
                // Closed already most likely due to timeout.
                return;
            }

            try {
                if (func.isOneWay()) {
                    reply.complete(null);
                    f.getResult(impl, args);
                } else {
                    final TBase<?, ?> result = f.getResult(impl, args);
                    reply.complete(func.getResult(result));
                }
            } catch (Throwable t) {
                if (func.isOneWay()) {
                    reply.complete(null);
                    logOneWayFunctionFailure(ctx, t);
                } else {
                    reply.completeExceptionally(t);
                }
            }
        });
    }

    private static void logOneWayFunctionFailure(@Nullable RequestContext ctx, Throwable cause) {
        if (ctx != null) {
            logger.warn("{} Unexpected exception from a one-way function:", ctx, cause);
        } else {
            // Should never reach here, but for completeness.
            logger.warn("Unexpected exception from a one-way function:", cause);
        }
    }
}
