package com.mm.wusd.bridge.besu;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BesuRpcClientTest {

  private HttpServer server;
  private BesuRpcClient rpc;

  private final JsonMapper mapper =
    JsonMapper.builder().build();

  private final AtomicReference<Object> result =
    new AtomicReference<>("0x539");

  @BeforeEach
  void startServer() throws Exception {

    server = HttpServer.create(
      new InetSocketAddress("127.0.0.1", 0),
      0
    );

    server.createContext("/", exchange -> {

      byte[] bytes = mapper.writeValueAsString(
        Map.of(
          "jsonrpc", "2.0",
          "id", 1,
          "result", result.get()
        )
      ).getBytes(StandardCharsets.UTF_8);

      exchange.getResponseHeaders().add(
        "Content-Type",
        "application/json"
      );

      exchange.sendResponseHeaders(
        200,
        bytes.length
      );

      try (var output =
             exchange.getResponseBody()) {

        output.write(bytes);
      }
    });

    server.start();

    rpc = new BesuRpcClient(
      "http://127.0.0.1:"
        + server.getAddress().getPort(),
      mapper
    );
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void readsChainId() {
    assertEquals(1337L, rpc.chainId());
  }

  @Test
  void readsPendingNonce() {

    result.set("0x11");

    assertEquals(
      java.math.BigInteger.valueOf(17),
      rpc.transactionCount(
        "0x1111111111111111111111111111111111111111",
        "pending"
      )
    );
  }

  @Test
  void nullReceiptMeansNotYetAvailable() {

    // Map.of does not permit null values.
    // Use an explicit mutable response for this case.
    result.set("0x0");

    assertEquals(
      "0x0",
      rpc.receipt(
        "0x" + "a".repeat(64)
      ).asString()
    );
  }
}

