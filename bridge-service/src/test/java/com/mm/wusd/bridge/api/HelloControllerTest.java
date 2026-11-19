package com.mm.wusd.bridge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@WebMvcTest(HelloController.class)
class HelloControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void returnApplicationStatus() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/hello"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.application").value("wusd-bridge"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("RUNNING"));
  }

}
