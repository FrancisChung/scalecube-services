package io.scalecube.services;

import io.scalecube.services.monitor.ServiceMonitorModel;

/** Microservices platform context. */
public final class PlatformContext {

  private final ServiceEndpoint serviceEndpoint;
  private final ServiceMonitorModel.Builder monitorModelBuilder;

  public PlatformContext(
      ServiceEndpoint serviceEndpoint, ServiceMonitorModel.Builder monitorModelBuilder) {
    this.serviceEndpoint = serviceEndpoint;
    this.monitorModelBuilder = monitorModelBuilder;
  }

  public ServiceEndpoint serviceEndpoint() {
    return serviceEndpoint;
  }

  public ServiceMonitorModel.Builder monitorModelBuilder() {
    return monitorModelBuilder;
  }
}
