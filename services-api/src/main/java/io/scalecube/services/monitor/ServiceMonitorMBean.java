package io.scalecube.services.monitor;

public interface ServiceMonitorMBean {

  String getInstanceId();

  String getDiscoveryAddress();

  String getServiceDiscovery();

  String getGatewayAddresses();

  String getServiceTransport();

  String getServiceEndpoint();

  String getServiceEndpoints();

  String getServiceMethodInvokers();

  String getServiceGroups();

  String getServiceGroup();

  String getAddedGroups();

  String getRecentDiscoveryEvents();
}
