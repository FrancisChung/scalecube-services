package io.scalecube.services.discovery;

import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream;
import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterConfig;
import io.scalecube.cluster.ClusterImpl;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.membership.MembershipEvent;
import io.scalecube.cluster.transport.api.Message;
import io.scalecube.cluster.transport.api.MessageCodec;
import io.scalecube.net.Address;
import io.scalecube.services.PlatformContext;
import io.scalecube.services.ServiceEndpoint;
import io.scalecube.services.ServiceGroup;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscoveryEvent;
import io.scalecube.services.monitor.ServiceMonitorModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

public final class ScalecubeServiceDiscovery implements ServiceDiscovery {

  private static final Logger LOGGER =
      LoggerFactory.getLogger("io.scalecube.services.discovery.ServiceDiscovery");
  private static final Logger LOGGER_GROUP =
      LoggerFactory.getLogger("io.scalecube.services.discovery.ServiceGroupDiscovery");

  private final ServiceEndpoint serviceEndpoint;
  private final ServiceMonitorModel.Builder monitorModelBuilder;

  private ClusterConfig clusterConfig;

  private Cluster cluster;

  private Map<ServiceGroup, Collection<ServiceEndpoint>> groups = new HashMap<>();
  private Map<ServiceGroup, Integer> addedGroups = new HashMap<>();

  private final DirectProcessor<ServiceDiscoveryEvent> subject = DirectProcessor.create();
  private final FluxSink<ServiceDiscoveryEvent> sink = subject.sink();

  /**
   * Constructor.
   *
   * @param serviceEndpoint service endpoint
   */
  public ScalecubeServiceDiscovery(ServiceEndpoint serviceEndpoint) {
    this.serviceEndpoint = serviceEndpoint;
    this.monitorModelBuilder = new ServiceMonitorModel.Builder(); // assign to be not null
    init();
  }

  public ScalecubeServiceDiscovery(PlatformContext platformContext) {
    this.serviceEndpoint = platformContext.serviceEndpoint();
    this.monitorModelBuilder = platformContext.monitorModelBuilder();
    init();
  }

  /**
   * Copy constructor.
   *
   * @param other other instance
   */
  private ScalecubeServiceDiscovery(ScalecubeServiceDiscovery other) {
    this.serviceEndpoint = other.serviceEndpoint;
    this.clusterConfig = other.clusterConfig;
    this.cluster = other.cluster;
    this.groups = other.groups;
    this.addedGroups = other.addedGroups;
    this.monitorModelBuilder = other.monitorModelBuilder;
  }

  private void init() {
    // Add myself to the group if 'groupness' is defined
    ServiceGroup serviceGroup = serviceEndpoint.serviceGroup();
    if (serviceGroup != null) {
      if (serviceGroup.size() == 0) {
        throw new IllegalArgumentException("serviceGroup is invalid, size can't be 0");
      }
      addToGroup(serviceGroup, serviceEndpoint);
    }

    clusterConfig =
        ClusterConfig.defaultLanConfig()
            .metadata(serviceEndpoint)
            .transport(config -> config.messageCodec(new MessageCodecImpl()))
            .metadataEncoder(this::encode)
            .metadataDecoder(this::decode);
  }

  /**
   * Setter for {@code ClusterConfig.Builder} options.
   *
   * @param opts ClusterConfig options builder
   * @return new instance of {@code ScalecubeServiceDiscovery}
   */
  public ScalecubeServiceDiscovery options(UnaryOperator<ClusterConfig> opts) {
    ScalecubeServiceDiscovery d = new ScalecubeServiceDiscovery(this);
    d.clusterConfig = opts.apply(clusterConfig);
    return d;
  }

  @Override
  public Address address() {
    return cluster.address();
  }

  @Override
  public ServiceEndpoint serviceEndpoint() {
    return serviceEndpoint;
  }

  /**
   * Starts scalecube service discovery. Joins a cluster with local services as metadata.
   *
   * @return mono result
   */
  @Override
  public Mono<ServiceDiscovery> start() {
    return Mono.defer(
        () -> {
          // Start scalecube-cluster and listen membership events
          return new ClusterImpl()
              .config(options -> clusterConfig)
              .handler(
                  cluster -> {
                    return new ClusterMessageHandler() {
                      @Override
                      public void onMembershipEvent(MembershipEvent event) {
                        ScalecubeServiceDiscovery.this.onMembershipEvent(event, sink);
                      }
                    };
                  })
              .start()
              .doOnSuccess(
                  cluster -> {
                    this.cluster = cluster;
                    LOGGER.debug("Started {} with config -- {}", cluster, clusterConfig);
                  })
              .then(Mono.fromCallable(() -> JmxMonitorMBean.start(this)))
              .thenReturn(this);
        });
  }

  @Override
  public Flux<ServiceDiscoveryEvent> listenDiscovery() {
    return subject.onBackpressureBuffer();
  }

  @Override
  public Mono<Void> shutdown() {
    return Mono.defer(
        () -> {
          if (cluster == null) {
            sink.complete();
            return Mono.empty();
          }
          cluster.shutdown();
          return cluster.onShutdown().doFinally(s -> sink.complete());
        });
  }

  private void onMembershipEvent(
      MembershipEvent membershipEvent, FluxSink<ServiceDiscoveryEvent> sink) {

    if (membershipEvent.isAdded()) {
      LOGGER.debug("Member {} has joined the cluster", membershipEvent.member());
    }
    if (membershipEvent.isRemoved()) {
      LOGGER.debug("Member {} has left the cluster", membershipEvent.member());
    }

    ServiceEndpoint serviceEndpoint = getServiceEndpoint(membershipEvent);

    if (serviceEndpoint == null) {
      return;
    }

    if (membershipEvent.isAdded()) {
      LOGGER.info(
          "Service endpoint {} is about to be added, since member {} has joined the cluster",
          serviceEndpoint.id(),
          membershipEvent.member());
    }
    if (membershipEvent.isRemoved()) {
      LOGGER.info(
          "Service endpoint {} is about to be removed, since member {} have left the cluster",
          serviceEndpoint.id(),
          membershipEvent.member());
    }

    ServiceDiscoveryEvent discoveryEvent = null;

    if (membershipEvent.isAdded()) {
      discoveryEvent = ServiceDiscoveryEvent.newEndpointAdded(serviceEndpoint);
    }
    if (membershipEvent.isRemoved()) {
      discoveryEvent = ServiceDiscoveryEvent.newEndpointRemoved(serviceEndpoint);
    }

    if (discoveryEvent != null) {
      sink.next(discoveryEvent);

      if (discoveryEvent.serviceEndpoint().serviceGroup() != null) {
        onDiscoveryEvent(discoveryEvent, sink);
      }
    }
  }

  private void onDiscoveryEvent(
      ServiceDiscoveryEvent discoveryEvent, FluxSink<ServiceDiscoveryEvent> sink) {

    ServiceEndpoint serviceEndpoint = discoveryEvent.serviceEndpoint();
    ServiceGroup serviceGroup = serviceEndpoint.serviceGroup();

    ServiceDiscoveryEvent groupDiscoveryEvent = null;
    String groupId = serviceGroup.id();

    // handle add to group
    if (discoveryEvent.isEndpointAdded()) {
      boolean isGroupAdded = addToGroup(serviceGroup, serviceEndpoint);
      Collection<ServiceEndpoint> endpoints = getEndpointsFromGroup(serviceGroup);

      LOGGER_GROUP.debug(
          "Added service endpoint {} to group {} (size now {})",
          serviceEndpoint.id(),
          groupId,
          endpoints.size());

      // publish event regardless of isGroupAdded result
      sink.next(ServiceDiscoveryEvent.newEndpointAddedToGroup(groupId, serviceEndpoint, endpoints));

      if (isGroupAdded) {
        LOGGER_GROUP.info("Service group {} added to the cluster", serviceGroup);
        groupDiscoveryEvent = ServiceDiscoveryEvent.newGroupAdded(groupId, endpoints);
      }
    }

    // handle removal from group
    if (discoveryEvent.isEndpointRemoved()) {
      if (!removeFromGroup(serviceGroup, serviceEndpoint)) {
        LOGGER_GROUP.warn(
            "Failed to remove service endpoint {} from group {}, "
                + "there were no such group or service endpoint was never registered in group",
            serviceEndpoint.id(),
            groupId);
        return;
      }

      Collection<ServiceEndpoint> endpoints = getEndpointsFromGroup(serviceGroup);

      LOGGER_GROUP.debug(
          "Removed service endpoint {} from group {} (size now {})",
          serviceEndpoint.id(),
          groupId,
          endpoints.size());

      sink.next(
          ServiceDiscoveryEvent.newEndpointRemovedFromGroup(groupId, serviceEndpoint, endpoints));

      if (endpoints.isEmpty()) {
        LOGGER_GROUP.info("Service group {} removed from the cluster", serviceGroup);
        groupDiscoveryEvent = ServiceDiscoveryEvent.newGroupRemoved(groupId);
      }
    }

    // post group event
    if (groupDiscoveryEvent != null) {
      sink.next(groupDiscoveryEvent);
    }
  }

  public Collection<ServiceEndpoint> getEndpointsFromGroup(ServiceGroup group) {
    return groups.getOrDefault(group, Collections.emptyList());
  }

  /**
   * Adds service endpoint to the group and returns indication whether group is fully formed.
   *
   * @param group service group
   * @param endpoint service ednpoint
   * @return {@code true} if group is fully formed; {@code false} otherwise, for example when
   *     there's not enough members yet or group was already formed and just keep updating
   */
  private boolean addToGroup(ServiceGroup group, ServiceEndpoint endpoint) {
    Collection<ServiceEndpoint> endpoints =
        groups.computeIfAbsent(group, group1 -> new ArrayList<>());
    endpoints.add(endpoint);

    int size = group.size();
    if (size == 1) {
      return addedGroups.putIfAbsent(group, 1) == null;
    }

    if (addedGroups.computeIfAbsent(group, group1 -> 0) == size) {
      return false;
    }

    int countAfter = addedGroups.compute(group, (group1, count) -> count + 1);
    return countAfter == size;
  }

  /**
   * Removes service endpoint from group.
   *
   * @param group service group
   * @param endpoint service endpoint
   * @return {@code true} if endpoint was removed from group; {@code false} if group didn't exist or
   *     endpoint wasn't contained in the group
   */
  private boolean removeFromGroup(ServiceGroup group, ServiceEndpoint endpoint) {
    if (!groups.containsKey(group)) {
      return false;
    }
    Collection<ServiceEndpoint> endpoints = getEndpointsFromGroup(group);
    boolean removed = endpoints.removeIf(input -> input.id().equals(endpoint.id()));
    if (removed && endpoints.isEmpty()) {
      groups.remove(group); // cleanup
      addedGroups.remove(group); // cleanup
    }
    return removed;
  }

  private ServiceEndpoint getServiceEndpoint(MembershipEvent membershipEvent) {
    ServiceEndpoint metadata = null;
    if (membershipEvent.isAdded()) {
      metadata = (ServiceEndpoint) decode(membershipEvent.newMetadata());
    }
    if (membershipEvent.isRemoved()) {
      metadata = (ServiceEndpoint) decode(membershipEvent.oldMetadata());
    }
    return metadata;
  }

  private Object decode(ByteBuffer byteBuffer) {
    try {
      return DefaultObjectMapper.OBJECT_MAPPER.readValue(
          new ByteBufferBackedInputStream(byteBuffer), ServiceEndpoint.class);
    } catch (IOException e) {
      LOGGER.error("Failed to read metadata: " + e);
      return null;
    }
  }

  private ByteBuffer encode(Object input) {
    ServiceEndpoint serviceEndpoint = (ServiceEndpoint) input;
    try {
      return ByteBuffer.wrap(
          DefaultObjectMapper.OBJECT_MAPPER
              .writeValueAsString(serviceEndpoint)
              .getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      LOGGER.error("Failed to write metadata: " + e);
      throw Exceptions.propagate(e);
    }
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", ScalecubeServiceDiscovery.class.getSimpleName() + "[", "]")
        .add("cluster=" + cluster)
        .add("clusterConfig=" + clusterConfig)
        .toString();
  }

  private static class MessageCodecImpl implements MessageCodec {

    @Override
    public Message deserialize(InputStream stream) throws Exception {
      return DefaultObjectMapper.OBJECT_MAPPER.readValue(stream, Message.class);
    }

    @Override
    public void serialize(Message message, OutputStream stream) throws Exception {
      DefaultObjectMapper.OBJECT_MAPPER.writeValue(stream, message);
    }
  }

  private static class JmxMonitorMBean {

    public static final int MAX_CACHE_SIZE = 128;

    private final ScalecubeServiceDiscovery discovery;
    private final List<ServiceDiscoveryEvent> recentDiscoveryEvents;

    private JmxMonitorMBean(ScalecubeServiceDiscovery discovery) {
      this.discovery = discovery;
      this.recentDiscoveryEvents = new CopyOnWriteArrayList<>();
      discovery
          .listenDiscovery()
          .subscribe(
              event -> {
                recentDiscoveryEvents.add(event);
                if (recentDiscoveryEvents.size() > MAX_CACHE_SIZE) {
                  recentDiscoveryEvents.remove(0);
                }
              });
    }

    //    private static JmxMonitorMBean start(ScalecubeServiceDiscovery instance) throws Exception
    // {
    //      MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
    //      JmxMonitorMBean jmxMBean = new JmxMonitorMBean(instance);
    //      String id = instance.serviceEndpoint.id();
    //      ObjectName objectName =
    //          new ObjectName("io.scalecube.services:name=ScalecubeServiceDiscovery@" + id);
    //      StandardMBean standardMBean = new StandardMBean(jmxMBean, MonitorMBean.class);
    //      mbeanServer.registerMBean(standardMBean, objectName);
    //      return jmxMBean;
    //    }

    @Override
    public Collection<String> getServiceGroup() {
      return Collections.singletonList(String.valueOf(discovery.serviceEndpoint.serviceGroup()));
    }

    @Override
    public String getServiceGroupAsString() {
      return getServiceGroup().iterator().next();
    }

    @Override
    public Collection<String> getAddedGroups() {
      return discovery.addedGroups.entrySet().stream()
          .map(
              entry -> {
                ServiceGroup serviceGroup = entry.getKey();
                int count = entry.getValue();
                String id = serviceGroup.id();
                int size = serviceGroup.size();
                return id + ":" + size + "/count=" + count;
              })
          .collect(Collectors.toList());
    }

    @Override
    public String getAddedGroupsAsString() {
      return getAddedGroups().stream().collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public Collection<String> getServiceGroups() {
      return discovery.groups.entrySet().stream()
          .map(
              entry -> {
                ServiceGroup serviceGroup = entry.getKey();
                Collection<ServiceEndpoint> endpoints = entry.getValue();
                String id = serviceGroup.id();
                int size = serviceGroup.size();
                return id + ":" + size + "/endpoints=" + endpoints.size();
              })
          .collect(Collectors.toList());
    }

    @Override
    public String getServiceGroupsAsString() {
      return getServiceGroups().stream().collect(Collectors.joining(",", "[", "]"));
    }

    public Collection<String> getRecentDiscoveryEvents() {
      return recentDiscoveryEvents.stream()
          .map(ServiceDiscoveryEvent::toString)
          .collect(Collectors.toList());
    }

    @Override
    public String getRecentServiceDiscoveryEventsAsString() {
      return getRecentDiscoveryEvents().stream().collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public Collection<String> getServiceDiscovery() {
      return Collections.singletonList(String.valueOf(discovery));
    }

    @Override
    public String getServiceDiscoveryAsString() {
      return getServiceDiscovery().iterator().next();
    }
  }
}
