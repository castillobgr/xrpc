/*
 * Copyright 2018 Nordstrom, Inc.
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

package com.nordstrom.xrpc.server;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.nordstrom.xrpc.XrpcConstants;
import com.nordstrom.xrpc.server.http.Recipes;
import com.nordstrom.xrpc.server.http.RoutePath;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Compiled routes instance for use at serving time. The term "compiled" is used very loosely. This
 * is responsible for routing requests to a given handler, and for tracking how often handlers are
 * invoked.
 */
@Slf4j
public class CompiledRoutes {
  /**
   * Map of routes to their handlers-by-method maps. Routes are sorted alphabetically by path for
   * consistent application.
   */
  private final ImmutableSortedMap<RoutePath, ImmutableMap<HttpMethod, Handler>> routes;

  /**
   * Returns compiled routes built from the given route map.
   *
   * @param metricRegistry the registry to generate per-(route,method) rate statistics in
   */
  public CompiledRoutes(
      Map<RoutePath, Map<HttpMethod, Handler>> rawRoutes, MetricRegistry metricRegistry) {
    // Build a sorted map of the routes.
    ImmutableSortedMap.Builder<RoutePath, ImmutableMap<HttpMethod, Handler>> routesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<RoutePath, Map<HttpMethod, Handler>> routeEntry : rawRoutes.entrySet()) {
      ImmutableMap.Builder<HttpMethod, Handler> handlers = new ImmutableMap.Builder<>();
      RoutePath route = routeEntry.getKey();
      for (Map.Entry<HttpMethod, Handler> methodHandlerEntry : routeEntry.getValue().entrySet()) {
        HttpMethod method = methodHandlerEntry.getKey();

        // Wrap the user-provided handler in one that tracks request rates.
        String metricName = MetricRegistry.name("routes", method.name(), route.toString());
        String timerName = MetricRegistry.name("routeLatency", method.name(), route.toString());
        final Handler userHandler = methodHandlerEntry.getValue();
        final Meter meter = metricRegistry.meter(metricName);
        final Timer timer = metricRegistry.timer(timerName);

        // TODO (AD): Pull this out into an adapted handler in a separate class.
        Handler adaptedHandler =
            request -> {
              meter.mark();
              try {
                return timer.time(() -> userHandler.handle(request));
              } catch (Exception e) {
                return request.connectionContext().exceptionHandler().handle(request, e);
              }
            };
        handlers.put(method, adaptedHandler);
      }

      routesBuilder.put(route, handlers.build());
    }

    this.routes = routesBuilder.build();
  }

  /**
   * Gets the handler and matched groups for the given path and method.
   *
   * @return the handler for the given path and method. If a path matched, but no method matched, a
   *     handler returning 405 (method not allowed) will be returned. If no path matched, a handler
   *     returning 404 (not found) will be returned.
   */
  public Match match(String path, String methodName) {
    return match(path, HttpMethod.valueOf(methodName));
  }

  /**
   * Gets the handler and matched groups for the given path and method.
   *
   * @return the handler for the given path and method. If a path matched, but no method matched, a
   *     handler returning 405 (method not allowed) will be returned. If no path matched, a handler
   *     returning 404 (not found) will be returned.
   */
  public Match match(String path, HttpMethod method) {
    boolean pathMatched = false;
    for (Map.Entry<RoutePath, ImmutableMap<HttpMethod, Handler>> routeToHandlers :
        routes.entrySet()) {
      Map<String, String> groups = routeToHandlers.getKey().groups(path);
      if (groups != null) {
        pathMatched = true;
        Handler handler = routeToHandlers.getValue().get(method);
        if (handler != null) {
          return new Match(handler, groups);
        }
      }
    }

    if (pathMatched) {
      return Match.METHOD_NOT_ALLOWED;
    } else {
      return Match.NOT_FOUND;
    }
  }

  /** Container for a matched Route. */
  @Value
  static class Match {
    /** The handler that matched the request path. */
    Handler handler;

    /** Groups which were pulled out of the request path. */
    Map<String, String> groups;

    /** A match returning 404 responses. */
    static final Match NOT_FOUND;

    /** A match returning 405 responses. */
    static final Match METHOD_NOT_ALLOWED;

    static {
      byte[] notFound = "Not found".getBytes(XrpcConstants.DEFAULT_CHARSET);
      NOT_FOUND =
          new Match(
              request -> {
                ByteBuf data = Unpooled.wrappedBuffer(notFound);
                return Recipes.newResponse(
                    HttpResponseStatus.NOT_FOUND, data, Recipes.ContentType.Text_Plain);
              },
              ImmutableMap.of());

      byte[] methodNotAllowed = "Method not allowed".getBytes(XrpcConstants.DEFAULT_CHARSET);
      METHOD_NOT_ALLOWED =
          new Match(
              request -> {
                ByteBuf data = Unpooled.wrappedBuffer(methodNotAllowed);
                return Recipes.newResponse(
                    HttpResponseStatus.METHOD_NOT_ALLOWED, data, Recipes.ContentType.Text_Plain);
              },
              ImmutableMap.of());
    }
  }
}
