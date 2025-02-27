/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Matchers.FractionMatcher;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} for resolving gRPC target names with "xds:" scheme.
 *
 * <p>Resolving a gRPC target involves contacting the control plane management server via xDS
 * protocol to retrieve service information and produce a service config to the caller.
 *
 * @see XdsNameResolverProvider
 */
final class XdsNameResolver extends NameResolver {

  static final CallOptions.Key<String> CLUSTER_SELECTION_KEY =
      CallOptions.Key.create("io.grpc.xds.CLUSTER_SELECTION_KEY");
  static final CallOptions.Key<Long> RPC_HASH_KEY =
      CallOptions.Key.create("io.grpc.xds.RPC_HASH_KEY");
  private static final NamedFilterConfig LAME_FILTER =
      new NamedFilterConfig(null, LameFilter.LAME_CONFIG);
  @VisibleForTesting
  static boolean enableTimeout =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_TIMEOUT"));

  private final InternalLogId logId;
  private final XdsLogger logger;
  private final String authority;
  private final ServiceConfigParser serviceConfigParser;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduler;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final ThreadSafeRandom random;
  private final FilterRegistry filterRegistry;
  private final XxHash64 hashFunc = XxHash64.INSTANCE;
  // Clusters (with reference counts) to which new/existing requests can be/are routed.
  private final ConcurrentMap<String, AtomicInteger> clusterRefs = new ConcurrentHashMap<>();
  private final ConfigSelector configSelector = new ConfigSelector();

  private volatile RoutingConfig routingConfig = RoutingConfig.empty;
  private Listener2 listener;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private CallCounterProvider callCounterProvider;
  private ResolveState resolveState;

  XdsNameResolver(String name, ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler) {
    this(name, serviceConfigParser, syncContext, scheduler,
        SharedXdsClientPoolProvider.getDefaultProvider(), ThreadSafeRandomImpl.instance,
        FilterRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  XdsNameResolver(String name, ServiceConfigParser serviceConfigParser,
      SynchronizationContext syncContext, ScheduledExecutorService scheduler,
      XdsClientPoolFactory xdsClientPoolFactory, ThreadSafeRandom random,
      FilterRegistry filterRegistry) {
    authority = GrpcUtil.checkAuthority(checkNotNull(name, "name"));
    this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.scheduler = checkNotNull(scheduler, "scheduler");
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    this.random = checkNotNull(random, "random");
    this.filterRegistry = checkNotNull(filterRegistry, "filterRegistry");
    logId = InternalLogId.allocate("xds-resolver", name);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created resolver for {0}", name);
  }

  @Override
  public String getServiceAuthority() {
    return authority;
  }

  @Override
  public void start(Listener2 listener) {
    this.listener = checkNotNull(listener, "listener");
    try {
      xdsClientPool = xdsClientPoolFactory.getXdsClientPool();
    } catch (Exception e) {
      listener.onError(
          Status.UNAVAILABLE.withDescription("Failed to initialize xDS").withCause(e));
      return;
    }
    xdsClient = xdsClientPool.getObject();
    callCounterProvider = SharedCallCounterMap.getInstance();
    resolveState = new ResolveState();
    resolveState.start();
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (resolveState != null) {
      resolveState.stop();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
  }

  @VisibleForTesting
  static Map<String, ?> generateServiceConfigWithMethodTimeoutConfig(long timeoutNano) {
    String timeout = timeoutNano / 1_000_000_000.0 + "s";
    Map<String, Object> methodConfig = new HashMap<>();
    methodConfig.put(
        "name", Collections.singletonList(Collections.emptyMap()));
    methodConfig.put("timeout", timeout);
    return Collections.singletonMap(
        "methodConfig", Collections.singletonList(Collections.unmodifiableMap(methodConfig)));
  }

  @VisibleForTesting
  static Map<String, ?> generateServiceConfigWithLoadBalancingConfig(Collection<String> clusters) {
    Map<String, Object> childPolicy = new HashMap<>();
    for (String cluster : clusters) {
      List<Map<String, Map<String, String>>> lbPolicy =
          Collections.singletonList(
              Collections.singletonMap(
                  "cds_experimental", Collections.singletonMap("cluster", cluster)));
      childPolicy.put(cluster, Collections.singletonMap("lbPolicy", lbPolicy));
    }
    return Collections.singletonMap("loadBalancingConfig",
        Collections.singletonList(
            Collections.singletonMap(
                "cluster_manager_experimental", Collections.singletonMap(
                    "childPolicy", Collections.unmodifiableMap(childPolicy)))));
  }

  @VisibleForTesting
  XdsClient getXdsClient() {
    return xdsClient;
  }

  private void updateResolutionResult() {
    Map<String, ?> rawServiceConfig =
        generateServiceConfigWithLoadBalancingConfig(clusterRefs.keySet());
    if (logger.isLoggable(XdsLogLevel.INFO)) {
      logger.log(
          XdsLogLevel.INFO, "Generated service config:\n{0}", new Gson().toJson(rawServiceConfig));
    }
    ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
    Attributes attrs =
        Attributes.newBuilder()
            .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
            .set(InternalXdsAttributes.CALL_COUNTER_PROVIDER, callCounterProvider)
            .set(InternalConfigSelector.KEY, configSelector)
            .build();
    ResolutionResult result =
        ResolutionResult.newBuilder()
            .setAttributes(attrs)
            .setServiceConfig(parsedServiceConfig)
            .build();
    listener.onResult(result);
  }

  @VisibleForTesting
  @Nullable
  static VirtualHost findVirtualHostForHostName(List<VirtualHost> virtualHosts, String hostName) {
    // Domain search order:
    //  1. Exact domain names: ``www.foo.com``.
    //  2. Suffix domain wildcards: ``*.foo.com`` or ``*-bar.foo.com``.
    //  3. Prefix domain wildcards: ``foo.*`` or ``foo-*``.
    //  4. Special wildcard ``*`` matching any domain.
    //
    //  The longest wildcards match first.
    //  Assuming only a single virtual host in the entire route configuration can match
    //  on ``*`` and a domain must be unique across all virtual hosts.
    int matchingLen = -1; // longest length of wildcard pattern that matches host name
    boolean exactMatchFound = false;  // true if a virtual host with exactly matched domain found
    VirtualHost targetVirtualHost = null;  // target VirtualHost with longest matched domain
    for (VirtualHost vHost : virtualHosts) {
      for (String domain : vHost.domains()) {
        boolean selected = false;
        if (matchHostName(hostName, domain)) { // matching
          if (!domain.contains("*")) { // exact matching
            exactMatchFound = true;
            targetVirtualHost = vHost;
            break;
          } else if (domain.length() > matchingLen) { // longer matching pattern
            selected = true;
          } else if (domain.length() == matchingLen && domain.startsWith("*")) { // suffix matching
            selected = true;
          }
        }
        if (selected) {
          matchingLen = domain.length();
          targetVirtualHost = vHost;
        }
      }
      if (exactMatchFound) {
        break;
      }
    }
    return targetVirtualHost;
  }

  /**
   * Returns {@code true} iff {@code hostName} matches the domain name {@code pattern} with
   * case-insensitive.
   *
   * <p>Wildcard pattern rules:
   * <ol>
   * <li>A single asterisk (*) matches any domain.</li>
   * <li>Asterisk (*) is only permitted in the left-most or the right-most part of the pattern,
   *     but not both.</li>
   * </ol>
   */
  @VisibleForTesting
  static boolean matchHostName(String hostName, String pattern) {
    checkArgument(hostName.length() != 0 && !hostName.startsWith(".") && !hostName.endsWith("."),
        "Invalid host name");
    checkArgument(pattern.length() != 0 && !pattern.startsWith(".") && !pattern.endsWith("."),
        "Invalid pattern/domain name");

    hostName = hostName.toLowerCase(Locale.US);
    pattern = pattern.toLowerCase(Locale.US);
    // hostName and pattern are now in lower case -- domain names are case-insensitive.

    if (!pattern.contains("*")) {
      // Not a wildcard pattern -- hostName and pattern must match exactly.
      return hostName.equals(pattern);
    }
    // Wildcard pattern

    if (pattern.length() == 1) {
      return true;
    }

    int index = pattern.indexOf('*');

    // At most one asterisk (*) is allowed.
    if (pattern.indexOf('*', index + 1) != -1) {
      return false;
    }

    // Asterisk can only match prefix or suffix.
    if (index != 0 && index != pattern.length() - 1) {
      return false;
    }

    // HostName must be at least as long as the pattern because asterisk has to
    // match one or more characters.
    if (hostName.length() < pattern.length()) {
      return false;
    }

    if (index == 0 && hostName.endsWith(pattern.substring(1))) {
      // Prefix matching fails.
      return true;
    }

    // Pattern matches hostname if suffix matching succeeds.
    return index == pattern.length() - 1
        && hostName.startsWith(pattern.substring(0, pattern.length() - 1));
  }

  private final class ConfigSelector extends InternalConfigSelector {
    @Override
    public Result selectConfig(PickSubchannelArgs args) {
      // Index ASCII headers by key, multi-value headers are concatenated for matching purposes.
      Map<String, String> asciiHeaders = new HashMap<>();
      Metadata headers = args.getHeaders();
      for (String headerName : headers.keys()) {
        if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          continue;
        }
        Metadata.Key<String> key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
        Iterable<String> values = headers.getAll(key);
        if (values != null) {
          asciiHeaders.put(headerName, Joiner.on(",").join(values));
        }
      }
      // Special hack for exposing headers: "content-type".
      asciiHeaders.put("content-type", "application/grpc");
      String cluster = null;
      Route selectedRoute = null;
      RoutingConfig routingCfg;
      Map<String, FilterConfig> selectedOverrideConfigs;
      List<ClientInterceptor> filterInterceptors = new ArrayList<>();
      do {
        routingCfg = routingConfig;
        selectedOverrideConfigs = new HashMap<>(routingCfg.virtualHostOverrideConfig);
        if (routingCfg.filterChain != null
            && Iterables.getLast(routingCfg.filterChain).equals(LAME_FILTER)) {
          break;
        }
        for (Route route : routingCfg.routes) {
          if (matchRoute(route.routeMatch(), "/" + args.getMethodDescriptor().getFullMethodName(),
              asciiHeaders, random)) {
            selectedRoute = route;
            selectedOverrideConfigs.putAll(route.filterConfigOverrides());
            break;
          }
        }
        if (selectedRoute == null) {
          return Result.forError(
              Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"));
        }
        RouteAction action = selectedRoute.routeAction();
        if (action.cluster() != null) {
          cluster = action.cluster();
        } else if (action.weightedClusters() != null) {
          int totalWeight = 0;
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            totalWeight += weightedCluster.weight();
          }
          int select = random.nextInt(totalWeight);
          int accumulator = 0;
          for (ClusterWeight weightedCluster : action.weightedClusters()) {
            accumulator += weightedCluster.weight();
            if (select < accumulator) {
              cluster = weightedCluster.name();
              selectedOverrideConfigs.putAll(weightedCluster.filterConfigOverrides());
              break;
            }
          }
        }
      } while (!retainCluster(cluster));
      // TODO(chengyuanzhang): avoid service config generation and parsing for each call.
      Map<String, ?> rawServiceConfig = Collections.emptyMap();
      if (enableTimeout) {
        Long timeoutNanos = null;
        if (selectedRoute != null) {
          timeoutNanos = selectedRoute.routeAction().timeoutNano();
        }
        if (timeoutNanos == null) {
          timeoutNanos = routingCfg.fallbackTimeoutNano;
        }
        if (timeoutNanos > 0) {
          rawServiceConfig = generateServiceConfigWithMethodTimeoutConfig(timeoutNanos);
        }
      }
      ConfigOrError parsedServiceConfig = serviceConfigParser.parseServiceConfig(rawServiceConfig);
      Object config = parsedServiceConfig.getConfig();
      if (config == null) {
        releaseCluster(cluster);
        return Result.forError(
            parsedServiceConfig.getError().augmentDescription(
                "Failed to parse service config (method config)"));
      }
      if (routingCfg.filterChain != null) {
        for (NamedFilterConfig namedFilter : routingCfg.filterChain) {
          FilterConfig filterConfig = namedFilter.filterConfig;
          Filter filter;
          if (namedFilter.equals(LAME_FILTER)) {
            filter = LameFilter.INSTANCE;
          } else {
            filter = filterRegistry.get(filterConfig.typeUrl());
          }
          if (filter instanceof ClientInterceptorBuilder) {
            ClientInterceptor interceptor = ((ClientInterceptorBuilder) filter)
                .buildClientInterceptor(
                    filterConfig, selectedOverrideConfigs.get(namedFilter.name),
                    args, scheduler);
            if (interceptor != null) {
              filterInterceptors.add(interceptor);
            }
          }
        }
        if (Iterables.getLast(routingCfg.filterChain).equals(LAME_FILTER)) {
          return Result.newBuilder()
              .setConfig(config)
              .setInterceptor(combineInterceptors(filterInterceptors))
              .build();
        }
      }
      final String finalCluster = cluster;
      final long hash = generateHash(selectedRoute.routeAction().hashPolicies(), asciiHeaders);
      class ClusterSelectionInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions,
            final Channel next) {
          final CallOptions callOptionsForCluster =
              callOptions.withOption(CLUSTER_SELECTION_KEY, finalCluster)
                  .withOption(RPC_HASH_KEY, hash);
          return new SimpleForwardingClientCall<ReqT, RespT>(
              next.newCall(method, callOptionsForCluster)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
              listener = new SimpleForwardingClientCallListener<RespT>(listener) {
                boolean committed;

                @Override
                public void onHeaders(Metadata headers) {
                  committed = true;
                  releaseCluster(finalCluster);
                  delegate().onHeaders(headers);
                }

                @Override
                public void onClose(Status status, Metadata trailers) {
                  if (!committed) {
                    releaseCluster(finalCluster);
                  }
                  delegate().onClose(status, trailers);
                }
              };
              delegate().start(listener, headers);
            }
          };
        }
      }

      filterInterceptors.add(new ClusterSelectionInterceptor());
      return
          Result.newBuilder()
              .setConfig(config)
              .setInterceptor(combineInterceptors(filterInterceptors))
              .build();
    }

    private boolean retainCluster(String cluster) {
      AtomicInteger refCount = clusterRefs.get(cluster);
      if (refCount == null) {
        return false;
      }
      int count;
      do {
        count = refCount.get();
        if (count == 0) {
          return false;
        }
      } while (!refCount.compareAndSet(count, count + 1));
      return true;
    }

    private void releaseCluster(final String cluster) {
      int count = clusterRefs.get(cluster).decrementAndGet();
      if (count == 0) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (clusterRefs.get(cluster).get() == 0) {
              clusterRefs.remove(cluster);
              updateResolutionResult();
            }
          }
        });
      }
    }

    private long generateHash(List<HashPolicy> hashPolicies, Map<String, String> headers) {
      Long hash = null;
      for (HashPolicy policy : hashPolicies) {
        Long newHash = null;
        if (policy.type() == HashPolicy.Type.HEADER) {
          if (headers.containsKey(policy.headerName())) {
            String value = headers.get(policy.headerName());
            if (policy.regEx() != null && policy.regExSubstitution() != null) {
              value = policy.regEx().matcher(value).replaceAll(policy.regExSubstitution());
            }
            newHash = hashFunc.hashAsciiString(value);
          }
        } else if (policy.type() == HashPolicy.Type.CHANNEL_ID) {
          newHash = hashFunc.hashLong(logId.getId());
        }
        if (newHash != null ) {
          // Rotating the old value prevents duplicate hash rules from cancelling each other out
          // and preserves all of the entropy.
          long oldHash = hash != null ? ((hash << 1L) | (hash >> 63L)) : 0;
          hash = oldHash ^ newHash;
        }
        // If the policy is a terminal policy and a hash has been generated, ignore
        // the rest of the hash policies.
        if (policy.isTerminal() && hash != null) {
          break;
        }
      }
      return hash == null ? random.nextLong() : hash;
    }
  }

  private static ClientInterceptor combineInterceptors(final List<ClientInterceptor> interceptors) {
    checkArgument(!interceptors.isEmpty(), "empty interceptors");
    if (interceptors.size() == 1) {
      return interceptors.get(0);
    }
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        next = ClientInterceptors.interceptForward(next, interceptors);
        return next.newCall(method, callOptions);
      }
    };
  }

  @VisibleForTesting
  static boolean matchRoute(RouteMatch routeMatch, String fullMethodName,
      Map<String, String> headers, ThreadSafeRandom random) {
    if (!matchPath(routeMatch.pathMatcher(), fullMethodName)) {
      return false;
    }
    if (!matchHeaders(routeMatch.headerMatchers(), headers)) {
      return false;
    }
    FractionMatcher fraction = routeMatch.fractionMatcher();
    return fraction == null || random.nextInt(fraction.denominator()) < fraction.numerator();
  }

  @VisibleForTesting
  static boolean matchPath(PathMatcher pathMatcher, String fullMethodName) {
    if (pathMatcher.path() != null) {
      return pathMatcher.caseSensitive()
          ? pathMatcher.path().equals(fullMethodName)
          : pathMatcher.path().equalsIgnoreCase(fullMethodName);
    } else if (pathMatcher.prefix() != null) {
      return pathMatcher.caseSensitive()
          ? fullMethodName.startsWith(pathMatcher.prefix())
          : fullMethodName.toLowerCase().startsWith(pathMatcher.prefix().toLowerCase());
    }
    return pathMatcher.regEx().matches(fullMethodName);
  }

  private static boolean matchHeaders(
      List<HeaderMatcher> headerMatchers, Map<String, String> headers) {
    for (HeaderMatcher headerMatcher : headerMatchers) {
      if (!matchHeader(headerMatcher, headers.get(headerMatcher.name()))) {
        return false;
      }
    }
    return true;
  }

  @VisibleForTesting
  static boolean matchHeader(HeaderMatcher headerMatcher, @Nullable String value) {
    if (headerMatcher.present() != null) {
      return (value == null) == headerMatcher.present().equals(headerMatcher.inverted());
    }
    if (value == null) {
      return false;
    }
    boolean baseMatch;
    if (headerMatcher.exactValue() != null) {
      baseMatch = headerMatcher.exactValue().equals(value);
    } else if (headerMatcher.safeRegEx() != null) {
      baseMatch = headerMatcher.safeRegEx().matches(value);
    } else if (headerMatcher.range() != null) {
      long numValue;
      try {
        numValue = Long.parseLong(value);
        baseMatch = numValue >= headerMatcher.range().start()
            && numValue <= headerMatcher.range().end();
      } catch (NumberFormatException ignored) {
        baseMatch = false;
      }
    } else if (headerMatcher.prefix() != null) {
      baseMatch = value.startsWith(headerMatcher.prefix());
    } else {
      baseMatch = value.endsWith(headerMatcher.suffix());
    }
    return baseMatch != headerMatcher.inverted();
  }

  private class ResolveState implements LdsResourceWatcher {
    private final ConfigOrError emptyServiceConfig =
        serviceConfigParser.parseServiceConfig(Collections.<String, Object>emptyMap());
    private final ResolutionResult emptyResult =
        ResolutionResult.newBuilder()
            .setServiceConfig(emptyServiceConfig)
            // let channel take action for no config selector
            .build();
    private boolean stopped;
    @Nullable
    private Set<String> existingClusters;  // clusters to which new requests can be routed
    @Nullable
    private RouteDiscoveryState routeDiscoveryState;

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(XdsLogLevel.INFO, "Receive LDS resource update: {0}", update);
          List<VirtualHost> virtualHosts = update.virtualHosts;
          String rdsName = update.rdsName;
          cleanUpRouteDiscoveryState();
          if (virtualHosts != null) {
            updateRoutes(virtualHosts, update.httpMaxStreamDurationNano, update.filterChain);
          } else {
            routeDiscoveryState = new RouteDiscoveryState(
                rdsName, update.httpMaxStreamDurationNano, update.filterChain);
            logger.log(XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
            xdsClient.watchRdsResource(rdsName, routeDiscoveryState);
          }
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          listener.onError(error);
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          logger.log(XdsLogLevel.INFO, "LDS resource {0} unavailable", resourceName);
          cleanUpRouteDiscoveryState();
          cleanUpRoutes();
        }
      });
    }

    private void start() {
      logger.log(XdsLogLevel.INFO, "Start watching LDS resource {0}", authority);
      xdsClient.watchLdsResource(authority, this);
    }

    private void stop() {
      logger.log(XdsLogLevel.INFO, "Stop watching LDS resource {0}", authority);
      stopped = true;
      cleanUpRouteDiscoveryState();
      xdsClient.cancelLdsResourceWatch(authority, this);
    }

    private void updateRoutes(List<VirtualHost> virtualHosts, long httpMaxStreamDurationNano,
        @Nullable List<NamedFilterConfig> filterConfigs) {
      VirtualHost virtualHost = findVirtualHostForHostName(virtualHosts, authority);
      if (virtualHost == null) {
        logger.log(XdsLogLevel.WARNING,
            "Failed to find virtual host matching hostname {0}", authority);
        cleanUpRoutes();
        return;
      }

      // A router filter is required for request routing. For backward compatibility, routing
      // is always enabled for gRPC clients without HttpFilter support.
      List<Route> routes = virtualHost.routes();
      List<NamedFilterConfig> filterChain = null;
      if (filterConfigs != null) {
        boolean hasRouter = false;
        filterChain = new ArrayList<>(filterConfigs.size());
        for (NamedFilterConfig namedFilter : filterConfigs) {
          filterChain.add(namedFilter);
          if (namedFilter.filterConfig.equals(RouterFilter.ROUTER_CONFIG)) {
            hasRouter = true;
            break;
          }
        }
        if (!hasRouter) {
          // Fail all RPCs if a router filter is not present. Reference counts for all currently
          // selectable clusters should be reclaimed.
          filterChain.add(LAME_FILTER);
          routes = Collections.emptyList();
        }
      }

      // Populate all clusters to which requests can be routed to through the virtual host.
      Set<String> clusters = new HashSet<>();
      for (Route route : routes) {
        RouteAction action = route.routeAction();
        if (action.cluster() != null) {
          clusters.add(action.cluster());
        } else if (action.weightedClusters() != null) {
          for (ClusterWeight weighedCluster : action.weightedClusters()) {
            clusters.add(weighedCluster.name());
          }
        }
      }

      // Updates channel's load balancing config whenever the set of selectable clusters changes.
      boolean shouldUpdateResult = existingClusters == null;
      Set<String> addedClusters =
          existingClusters == null ? clusters : Sets.difference(clusters, existingClusters);
      Set<String> deletedClusters =
          existingClusters == null
              ? Collections.<String>emptySet() : Sets.difference(existingClusters, clusters);
      existingClusters = clusters;
      for (String cluster : addedClusters) {
        if (clusterRefs.containsKey(cluster)) {
          clusterRefs.get(cluster).incrementAndGet();
        } else {
          clusterRefs.put(cluster, new AtomicInteger(1));
          shouldUpdateResult = true;
        }
      }
      // Update service config to include newly added clusters.
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
      // Make newly added clusters selectable by config selector and deleted clusters no longer
      // selectable.
      routingConfig =
          new RoutingConfig(
              httpMaxStreamDurationNano, routes, filterChain,
              virtualHost.filterConfigOverrides());
      shouldUpdateResult = false;
      for (String cluster : deletedClusters) {
        int count = clusterRefs.get(cluster).decrementAndGet();
        if (count == 0) {
          clusterRefs.remove(cluster);
          shouldUpdateResult = true;
        }
      }
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
    }

    private void cleanUpRoutes() {
      if (existingClusters != null) {
        for (String cluster : existingClusters) {
          int count = clusterRefs.get(cluster).decrementAndGet();
          if (count == 0) {
            clusterRefs.remove(cluster);
          }
        }
        existingClusters = null;
      }
      routingConfig = RoutingConfig.empty;
      listener.onResult(emptyResult);
    }

    private void cleanUpRouteDiscoveryState() {
      if (routeDiscoveryState != null) {
        String rdsName = routeDiscoveryState.resourceName;
        logger.log(XdsLogLevel.INFO, "Stop watching RDS resource {0}", rdsName);
        xdsClient.cancelRdsResourceWatch(rdsName, routeDiscoveryState);
        routeDiscoveryState = null;
      }
    }

    /**
     * Discovery state for RouteConfiguration resource. One instance for each Listener resource
     * update.
     */
    private class RouteDiscoveryState implements RdsResourceWatcher {
      private final String resourceName;
      private final long httpMaxStreamDurationNano;
      @Nullable
      private final List<NamedFilterConfig> filterConfigs;

      private RouteDiscoveryState(String resourceName, long httpMaxStreamDurationNano,
          @Nullable List<NamedFilterConfig> filterConfigs) {
        this.resourceName = resourceName;
        this.httpMaxStreamDurationNano = httpMaxStreamDurationNano;
        this.filterConfigs = filterConfigs;
      }

      @Override
      public void onChanged(final RdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "Received RDS resource update: {0}", update);
            updateRoutes(update.virtualHosts, httpMaxStreamDurationNano, filterConfigs);
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState) {
              return;
            }
            listener.onError(error);
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (RouteDiscoveryState.this != routeDiscoveryState) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "RDS resource {0} unavailable", resourceName);
            cleanUpRoutes();
          }
        });
      }
    }
  }

  /**
   * VirtualHost-level configuration for request routing.
   */
  private static class RoutingConfig {
    private final long fallbackTimeoutNano;
    final List<Route> routes;
    // Null if HttpFilter is not supported.
    @Nullable final List<NamedFilterConfig> filterChain;
    final Map<String, FilterConfig> virtualHostOverrideConfig;

    private static RoutingConfig empty = new RoutingConfig(
        0L, Collections.<Route>emptyList(), null, Collections.<String, FilterConfig>emptyMap());

    private RoutingConfig(
        long fallbackTimeoutNano, List<Route> routes, @Nullable List<NamedFilterConfig> filterChain,
        Map<String, FilterConfig> virtualHostOverrideConfig) {
      this.fallbackTimeoutNano = fallbackTimeoutNano;
      this.routes = routes;
      checkArgument(filterChain == null || !filterChain.isEmpty(), "filterChain is empty");
      this.filterChain = filterChain == null ? null : Collections.unmodifiableList(filterChain);
      this.virtualHostOverrideConfig = Collections.unmodifiableMap(virtualHostOverrideConfig);
    }
  }
}
