
package com.mm.wusd.bridge.api;

import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.domain.Deposit;
import com.mm.wusd.bridge.exception.DepositNotFoundException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigInteger;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DepositController.class)
class DepositControllerBoundaryTest {

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private DepositService service;

  @Test
  void getExistingDepositReturns200() throws Exception {

    Deposit deposit = new Deposit(
      "DEP-1001",
      "USER-42",
      "0x1111111111111111111111111111111111111111",
      new BigInteger("250500000"),
      "USD",
      "VALIDATED"
    );

    when(service.findById("DEP-1001"))
      .thenReturn(deposit);

    mvc.perform(get("/api/v1/deposits/DEP-1001"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status")
        .value("VALIDATED"))
      .andExpect(jsonPath("$.amountUnits")
        .value(250500000));
  }

  @Test
  void missingDepositReturns404() throws Exception {

    when(service.findById("UNKNOWN"))
      .thenThrow(
        new DepositNotFoundException("UNKNOWN")
      );

    mvc.perform(get("/api/v1/deposits/UNKNOWN"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code")
        .value("DEPOSIT_NOT_FOUND"));
  }

  @Test
  void invalidWalletReturns400() throws Exception {

    String json = """
            {
              "depositId": "DEP-2001",
              "userId": "USER-42",
              "walletAddress": "invalid-wallet",
              "amount": 250.50,
              "currency": "USD"
            }
            """;

    mvc.perform(
        post("/api/v1/deposits")
          .contentType(MediaType.APPLICATION_JSON)
          .content(json)
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code")
        .value("INVALID_REQUEST"));

    verifyNoInteractions(service);
  }

  @Test
  void missingAmountReturns400() throws Exception {

    String json = """
            {
              "depositId": "DEP-2002",
              "userId": "USER-42",
              "walletAddress":
                "0x1111111111111111111111111111111111111111",
              "currency": "USD"
            }
            """;

    mvc.perform(
        post("/api/v1/deposits")
          .contentType(MediaType.APPLICATION_JSON)
          .content(json)
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code")
        .value("INVALID_REQUEST"));

    verifyNoInteractions(service);
  }
}
