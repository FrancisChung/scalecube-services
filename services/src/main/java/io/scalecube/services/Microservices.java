package io.scalecube.services;

import static java.util.stream.Collectors.toMap;

import com.codahale.metrics.MetricRegistry;
import io.scalecube.services.ServiceCall.Call;
import io.scalecube.services.discovery.ServiceScanner;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscoveryConfig;
import io.scalecube.services.exceptions.DefaultErrorMapper;
import io.scalecube.services.exceptions.ServiceProviderErrorMapper;
import io.scalecube.services.gateway.Gateway;
import io.scalecube.services.gateway.GatewayConfig;
import io.scalecube.services.methods.ServiceMethodRegistry;
import io.scalecube.services.methods.ServiceMethodRegistryImpl;
import io.scalecube.services.metrics.Metrics;
import io.scalecube.services.registry.ServiceRegistryImpl;
import io.scalecube.services.registry.api.ServiceRegistry;
import io.scalecube.services.transport.ServiceTransportConfig;
import io.scalecube.services.transport.api.Address;
import io.scalecube.services.transport.api.ClientTransport;
import io.scalecube.services.transport.api.ServerTransport;
import io.scalecube.services.transport.api.ServiceTransport;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

/**
 * The ScaleCube-Services module enables to provision and consuming microservices in a cluster.
 * ScaleCube-Services provides Reactive application development platform for building distributed
 * applications Using microservices and fast data on a message-driven runtime that scales
 * transparently on multi-core, multi-process and/or multi-machines Most microservices frameworks
 * focus on making it easy to build individual microservices. ScaleCube allows developers to run a
 * whole system of microservices from a single command. removing most of the boilerplate code,
 * ScaleCube-Services focuses development on the essence of the service and makes it easy to create
 * explicit and typed protocols that compose. True isolation is achieved through shared-nothing
 * design. This means the services in ScaleCube are autonomous, loosely coupled and mobile (location
 * transparent)—necessary requirements for resilance and elasticity ScaleCube services requires
 * developers only to two simple Annotations declaring a Service but not regards how you build the
 * service component itself. the Service component is simply java class that implements the service
 * Interface and ScaleCube take care for the rest of the magic. it derived and influenced by Actor
 * model and reactive and streaming patters but does not force application developers to it.
 * ScaleCube-Services is not yet-anther RPC system in the sense its is cluster aware to provide:
 *
 * <ul>
 *   <li>location transparency and discovery of service instances.
 *   <li>fault tolerance using gossip and failure detection.
 *   <li>share nothing - fully distributed and decentralized architecture.
 *   <li>Provides fluent, java 8 lambda apis.
 *   <li>Embeddable and lightweight.
 *   <li>utilizes completable futures but primitives and messages can be used as well completable
 *       futures gives the advantage of composing and chaining service calls and service results.
 *   <li>low latency
 *   <li>supports routing extensible strategies when selecting service end-points
 * </ul>
 *
 * <b>basic usage example:</b>
 *
 * <pre>{@code
 * // Define a service interface and implement it:
 * &#64; Service
 * public interface GreetingService {
 *      &#64; ServiceMethod
 *      Mono<String> sayHello(String string);
 *  }
 *
 *  public class GreetingServiceImpl implements GreetingService {
 *    &#64; Override
 *    public Mono<String> sayHello(String name) {
 *      return Mono.just("hello to: " + name);
 *    }
 *  }
 *
 *  // Build a microservices cluster instance:
 *  Microservices microservices = Microservices.builder()
 *       // Introduce GreetingServiceImpl pojo as a micro-service:
 *      .services(new GreetingServiceImpl())
 *      .startAwait();
 *
 *  // Create microservice proxy to GreetingService.class interface:
 *  GreetingService service = microservices.call().create()
 *      .api(GreetingService.class);
 *
 *  // Invoke the greeting service async:
 *  service.sayHello("joe").subscribe(resp->{
 *    // handle response
 *  });
 *
 * }</pre>
 */
public class Microservices {

  private static final Logger LOGGER = LoggerFactory.getLogger(Microservices.class);

  private final MonoProcessor<Void> shutdown = MonoProcessor.create();
  private final MonoProcessor<Void> onShutdown = MonoProcessor.create();

  private String id = UUID.randomUUID().toString();
  private Metrics metrics;
  private Map<String, String> tags = new HashMap<>();
  private List<ServiceInfo> serviceInfos = new ArrayList<>();
  private List<ServiceProvider> serviceProviders = new ArrayList<>();
  private ServiceRegistry serviceRegistry = new ServiceRegistryImpl();
  private ServiceMethodRegistry methodRegistry = new ServiceMethodRegistryImpl();
  private GatewayBootstrap gatewayBootstrap = new GatewayBootstrap();
  private ServiceDiscovery discovery = ServiceDiscovery.getDiscovery();
  private Consumer<ServiceDiscoveryConfig.Builder> discoveryOptions;
  private ServiceProviderErrorMapper errorMapper = DefaultErrorMapper.INSTANCE;
  private ServiceTransportBootstrap transportBootstrap =
      new ServiceTransportBootstrap(ServiceTransportConfig.builder(null).build());

  /** Default constructor for Microservice creation. */
  public Microservices() {}

  /**
   * Constructor of {@code Microservices} object copying (copy constructor).
   *
   * @param msBase copied object
   */
  private Microservices(Microservices msBase) {
    this();
    this.tags = new HashMap<>(msBase.tags);
    this.serviceInfos = new ArrayList<>(msBase.serviceInfos);
    this.serviceProviders = new ArrayList<>(msBase.serviceProviders);
    this.serviceRegistry = msBase.serviceRegistry;
    this.methodRegistry = msBase.methodRegistry;
    this.gatewayBootstrap = msBase.gatewayBootstrap;
    this.discovery = msBase.discovery;
    this.errorMapper = msBase.errorMapper;
    this.transportBootstrap = msBase.transportBootstrap;
    this.discoveryOptions = msBase.discoveryOptions;
    this.metrics = msBase.metrics;
  }

  /**
   * Create {@code new Microservices} object with changed property defined by {@code Consumer}.
   * For example to change metrics: define {@code create(p -> p.metrics = metrics)}.
   *
   * @param property property consumer to change
   * @return
   */
  private Microservices create(Consumer<Microservices> property) {
    Microservices ms = new Microservices(this);
    property.accept(ms);
    return ms;
  }

  /**
   * Build new {@code Microservices} based on current with different {@code metrics} property.
   *
   * @param metrics new property
   * @return new Microservice
   */
  public Microservices metrics(Metrics metrics) {
    return create(p -> p.metrics = metrics);
  }

  /**
   * Build new {@code Microservices} based on current with different {@code metrics} property.
   *
   * @param metrics new property
   * @return new {@code Microservices}
   */
  public Microservices metrics(MetricRegistry metrics) {
    return create(p -> p.metrics = new Metrics(metrics));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code tags} property.
   *
   * @param tags new property
   * @return new {@code Microservices}
   */
  public Microservices tags(Map<String, String> tags) {
    return create(p -> p.tags.putAll(tags));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code serviceProvider}
   * property.
   *
   * @param serviceProvider new property
   * @return new {@code Microservices}
   */
  public Microservices services(ServiceProvider serviceProvider) {
    return create(p -> p.serviceProviders.add(serviceProvider));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code services} property.
   *
   * @param services new property
   * @return new {@code Microservices}
   */
  public Microservices services(Object... services) {
    return create(
        p ->
            p.serviceProviders.add(
                call ->
                    Arrays.stream(services)
                        .map(
                            s ->
                                s instanceof ServiceInfo
                                    ? (ServiceInfo) s
                                    : ServiceInfo.fromServiceInstance(s).build())
                        .collect(Collectors.toList())));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code services} property.
   *
   * @param services new property
   * @return new {@code Microservices}
   */
  public Microservices services(ServiceInfo... services) {
    return create(
        p -> p.serviceProviders.add(call -> Arrays.stream(services).collect(Collectors.toList())));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code serviceRegistry}
   * property.
   *
   * @param serviceRegistry new property
   * @return new {@code Microservices}
   */
  public Microservices serviceRegistry(ServiceRegistry serviceRegistry) {
    return create(p -> p.serviceRegistry = serviceRegistry);
  }

  /**
   * Build new {@code Microservices} based on current with different {@code methodRegistry}
   * property.
   *
   * @param methodRegistry new property
   * @return new {@code Microservices}
   */
  public Microservices methodRegistry(ServiceMethodRegistry methodRegistry) {
    return create(p -> p.methodRegistry = methodRegistry);
  }

  /**
   * Build new {@code Microservices} based on current with different {@code discovery} property.
   *
   * @param discovery new property
   * @return new {@code Microservices}
   */
  public Microservices discovery(ServiceDiscovery discovery) {
    return create(p -> p.discovery = discovery);
  }

  /**
   * Build new {@code Microservices} based on current with different {@code discoveryOptions}
   * property.
   *
   * @param discoveryOptions new property
   * @return new {@code Microservices}
   */
  public Microservices discovery(Consumer<ServiceDiscoveryConfig.Builder> discoveryOptions) {
    return create(p -> p.discoveryOptions = discoveryOptions);
  }

  public ServiceDiscovery discovery() {
    return this.discovery;
  }

  /**
   * Build new {@code Microservices} based on current with different {@code transportOptions}
   * property.
   *
   * @param transportOptions new property
   * @return new {@code Microservices}
   */
  public Microservices transport(Consumer<ServiceTransportConfig.Builder> transportOptions) {
    return create(
        p ->
            p.transportBootstrap =
                new ServiceTransportBootstrap(
                    ServiceTransportConfig.builder(transportOptions).build()));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code config} property.
   *
   * @param config new property
   * @return new {@code Microservices}
   */
  public Microservices gateway(GatewayConfig config) {
    return create(p -> p.gatewayBootstrap.addConfig(config));
  }

  /**
   * Build new {@code Microservices} based on current with different {@code errorMapper} property.
   *
   * @param errorMapper new property
   * @return new {@code Microservices}
   */
  public Microservices errorMapper(ServiceProviderErrorMapper errorMapper) {
    return create(p -> p.errorMapper = errorMapper);
  }

  public Microservices startAwait() {
    return start().block();
  }

  /**
   * Start deferred {@code Microservices} method.
   *
   * @return started {@code Microservices} instance
   */
  public Mono<Microservices> start() {

    return Mono.defer(
        () -> {
          Microservices ms = new Microservices(this);
          return ms.transportBootstrap
              .start(ms.methodRegistry)
              .flatMap(
                  input -> {
                    ClientTransport clientTransport = ms.transportBootstrap.clientTransport();
                    InetSocketAddress serviceAddress = ms.transportBootstrap.serviceAddress();

                    Call call = new Call(clientTransport, ms.methodRegistry, ms.serviceRegistry);

                    // invoke service providers and register services
                    ms.serviceProviders.stream()
                        .flatMap(serviceProvider -> serviceProvider.provide(call).stream())
                        .forEach(ms::collectAndRegister);

                    // register services in service registry
                    ServiceEndpoint endpoint = null;
                    if (!ms.serviceInfos.isEmpty()) {
                      String serviceHost = serviceAddress.getHostString();
                      int servicePort = serviceAddress.getPort();
                      endpoint =
                          ServiceScanner.scan(
                              ms.serviceInfos, ms.id, serviceHost, servicePort, ms.tags);
                      ms.serviceRegistry.registerService(endpoint);
                    }

                    // configure discovery and publish to the cluster
                    ServiceDiscoveryConfig discoveryConfig =
                        ServiceDiscoveryConfig.builder(ms.discoveryOptions)
                            .serviceRegistry(ms.serviceRegistry)
                            .endpoint(endpoint)
                            .build();
                    return ms.discovery
                        .start(discoveryConfig)
                        .then(Mono.defer(ms::doInjection))
                        .then(Mono.defer(() -> startGateway(call)))
                        .thenReturn(ms);
                  })
              .doOnSuccess(
                  v -> {
                    ms.shutdown
                        .then(ms.shutdown())
                        .doFinally(s -> ms.onShutdown.onComplete())
                        .subscribe(
                            null,
                            thread ->
                                LOGGER.warn("{} failed on shutdown(): {}", ms, thread.toString()),
                            () -> LOGGER.debug("Shutdown {}", ms));
                  })
              .onErrorResume(
                  ex -> {
                    // return original error then shutdown
                    return Mono.when(Mono.error(ex), ms.shutdown()).cast(Microservices.class);
                  });
        });
  }

  private Mono<GatewayBootstrap> startGateway(Call call) {
    return gatewayBootstrap.start(transportBootstrap.workerPool(), call, metrics);
  }

  private Mono<Microservices> doInjection() {
    List<Object> serviceInstances =
        serviceInfos.stream().map(ServiceInfo::serviceInstance).collect(Collectors.toList());
    return Mono.just(Reflect.inject(this, serviceInstances));
  }

  private void collectAndRegister(ServiceInfo serviceInfo) {
    // collect
    serviceInfos.add(serviceInfo);

    // register service
    methodRegistry.registerService(
        serviceInfo.serviceInstance(),
        Optional.ofNullable(serviceInfo.errorMapper()).orElse(errorMapper));
  }

  public InetSocketAddress serviceAddress() {
    return transportBootstrap.serviceAddress();
  }

  public Call call() {
    return new Call(transportBootstrap.clientTransport(), methodRegistry, serviceRegistry);
  }

  public InetSocketAddress gatewayAddress(String name, Class<? extends Gateway> gatewayClass) {
    return gatewayBootstrap.gatewayAddress(name, gatewayClass);
  }

  public Map<GatewayConfig, InetSocketAddress> gatewayAddresses() {
    return gatewayBootstrap.gatewayAddresses();
  }

  /**
   * Shutdown instance and clear resources.
   *
   * @return result of shutdown
   */
  public Mono<Void> shutdown() {
    return Mono.defer(
        () ->
            Mono.whenDelayError(
                Optional.ofNullable(discovery).map(ServiceDiscovery::shutdown).orElse(Mono.empty()),
                Optional.ofNullable(gatewayBootstrap)
                    .map(GatewayBootstrap::shutdown)
                    .orElse(Mono.empty()),
                Optional.ofNullable(transportBootstrap)
                    .map(ServiceTransportBootstrap::shutdown)
                    .orElse(Mono.empty())));
  }

  private static class GatewayBootstrap {

    private Set<GatewayConfig> gatewayConfigs = new HashSet<>(); // config
    private Map<GatewayConfig, Gateway> gatewayInstances = new HashMap<>(); // calculated

    private GatewayBootstrap addConfig(GatewayConfig config) {
      if (!gatewayConfigs.add(config)) {
        throw new IllegalArgumentException(
            "GatewayConfig with name: '"
                + config.name()
                + "' and gatewayClass: '"
                + config.gatewayClass().getName()
                + "' was already defined");
      }
      return this;
    }

    private Mono<GatewayBootstrap> start(Executor workerPool, Call call, Metrics metrics) {
      return Flux.fromIterable(gatewayConfigs)
          .flatMap(
              gatewayConfig ->
                  Gateway.getGateway(gatewayConfig.gatewayClass())
                      .start(gatewayConfig, workerPool, call, metrics)
                      .doOnSuccess(gw -> gatewayInstances.put(gatewayConfig, gw)))
          .then(Mono.just(this));
    }

    private Mono<Void> shutdown() {
      return Mono.defer(
          () ->
              gatewayInstances != null && !gatewayInstances.isEmpty()
                  ? Mono.when(
                      gatewayInstances.values().stream().map(Gateway::stop).toArray(Mono[]::new))
                  : Mono.empty());
    }

    private InetSocketAddress gatewayAddress(String name, Class<? extends Gateway> gatewayClass) {
      Optional<GatewayConfig> result =
          gatewayInstances.keySet().stream()
              .filter(config -> config.name().equals(name))
              .filter(config -> config.gatewayClass() == gatewayClass)
              .findFirst();

      if (!result.isPresent()) {
        throw new IllegalArgumentException(
            "Didn't find gateway address under name: '"
                + name
                + "' and gateway class: '"
                + gatewayClass.getName()
                + "'");
      }

      return gatewayInstances.get(result.get()).address();
    }

    private Map<GatewayConfig, InetSocketAddress> gatewayAddresses() {
      return Collections.unmodifiableMap(
          gatewayInstances.entrySet().stream()
              .collect(toMap(Entry::getKey, e -> e.getValue().address())));
    }
  }

  private static class ServiceTransportBootstrap {

    private static final int DEFAULT_NUM_OF_THREADS = Runtime.getRuntime().availableProcessors();

    private String serviceHost; // config
    private int servicePort; // config
    private ServiceTransport transport; // config or calculated
    private ClientTransport clientTransport; // calculated
    private ServerTransport serverTransport; // calculated
    private ServiceTransport.Resources transportResources; // calculated
    private InetSocketAddress serviceAddress; // calculated
    private int numOfThreads; // calculated

    ServiceTransportBootstrap(ServiceTransportConfig options) {
      this.serviceHost = options.host();
      this.servicePort = Optional.ofNullable(options.port()).orElse(0);
      this.numOfThreads =
          Optional.ofNullable(options.numOfThreads()).orElse(DEFAULT_NUM_OF_THREADS);
      this.transport = options.transport();
    }

    private ServiceTransport transport() {
      return transport;
    }

    private ClientTransport clientTransport() {
      return clientTransport;
    }

    private Executor workerPool() {
      return transportResources.workerPool().orElse(null);
    }

    private InetSocketAddress serviceAddress() {
      return serviceAddress;
    }

    private Mono<ServiceTransportBootstrap> start(ServiceMethodRegistry methodRegistry) {
      return Mono.defer(
          () -> {
            this.transport =
                Optional.ofNullable(this.transport).orElseGet(ServiceTransport::getTransport);

            this.transportResources = transport.resources(numOfThreads);
            this.clientTransport = transport.clientTransport(transportResources);
            this.serverTransport = transport.serverTransport(transportResources);

            // bind service serverTransport transport
            return serverTransport
                .bind(servicePort, methodRegistry)
                .map(
                    listenAddress -> {
                      // prepare service host:port for exposing
                      int port = listenAddress.getPort();
                      String host =
                          Optional.ofNullable(serviceHost)
                              .orElseGet(() -> Address.getLocalIpAddress().getHostAddress());
                      this.serviceAddress = InetSocketAddress.createUnresolved(host, port);
                      return this;
                    });
          });
    }

    private Mono<Void> shutdown() {
      return Mono.defer(
          () ->
              Mono.when(
                  Optional.ofNullable(serverTransport)
                      .map(ServerTransport::stop)
                      .orElse(Mono.empty()),
                  transportResources.shutdown()));
    }
  }
}
