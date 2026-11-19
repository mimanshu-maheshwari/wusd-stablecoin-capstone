package com.mm.wusd.bridge.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping()
public final class HelloController {

  @GetMapping("/api/v1/hello")
  public Map<String, String> hello() {
    return Map.of(
        "application", "wusd-bridge",
        "message", "Hello from wUSD Bridge!",
        "status", "RUNNING");
  }
}
