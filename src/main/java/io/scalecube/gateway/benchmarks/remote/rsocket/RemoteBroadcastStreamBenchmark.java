package io.scalecube.gateway.benchmarks.remote.rsocket;

import static io.scalecube.gateway.benchmarks.remote.RemoteBenchmarkState.RS_PORT;

import io.scalecube.gateway.benchmarks.BroadcastStreamBenchmark;
import io.scalecube.gateway.benchmarks.remote.RemoteBenchmarkState;
import io.scalecube.gateway.clientsdk.Client;
import io.scalecube.gateway.clientsdk.ClientSettings;

public class RemoteBroadcastStreamBenchmark {

  /**
   * Main runner.
   *
   * @param args program arguments
   */
  public static void main(String[] args) {
    BroadcastStreamBenchmark.runWith(
        args,
        benchmarkSettings ->
            new RemoteBenchmarkState(
                benchmarkSettings,
                RS_PORT,
                (address, loopResources) ->
                    Client.onRSocket(
                        ClientSettings.builder()
                            .address(address)
                            .loopResources(loopResources)
                            .build())));
  }
}
