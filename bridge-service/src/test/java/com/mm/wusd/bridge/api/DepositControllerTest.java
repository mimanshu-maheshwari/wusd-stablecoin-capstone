package com.mm.wusd.bridge.api;

import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.exception.DepositConflictException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DepositController.class)
class DepositControllerTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private DepositService service;

  private static final String VALID_JSON = """
      {
        "depositId": "DEP-1001",
        "userId": "USER-42",
        "walletAddress":
          "0x1111111111111111111111111111111111111111",
        "amount": 250.50,
        "currency": "USD"
      }
      """;

  @Test
  void returns201ForNewDeposit() throws Exception {
    Deposit saved = new Deposit(
        "DEP-1001",
        "USER-42",
        "0x1111111111111111111111111111111111111111",
        new BigInteger("250500000"),
        "USD",
        "RECEIVED");

    when(service.create(any(DepositRequest.class)))
        .thenReturn(
            new DepositService.CreateResult(
                saved,
                true));

    mvc.perform(
        post("/api/v1/deposits")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.depositId")
            .value("DEP-1001"))
        .andExpect(jsonPath("$.status")
            .value("RECEIVED"));
  }

  @Test
  void rejectsInvalidAmount() throws Exception {
    String invalidJson = VALID_JSON.replace(
        "250.50",
        "-10");

    mvc.perform(
        post("/api/v1/deposits")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code")
            .value("INVALID_REQUEST"));
  }

  @Test
  void returns409ForConflictingDeposit()
      throws Exception {

    when(service.create(any(DepositRequest.class)))
        .thenThrow(
            new DepositConflictException("DEP-1001"));

    mvc.perform(
        post("/api/v1/deposits")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code")
            .value("DEPOSIT_CONFLICT"));
  }
}
