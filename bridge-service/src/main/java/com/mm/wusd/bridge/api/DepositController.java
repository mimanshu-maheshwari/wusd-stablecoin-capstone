package com.mm.wusd.bridge.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mm.wusd.bridge.application.DepositService;
import com.mm.wusd.bridge.exception.DepositConflictException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

  private final DepositService depositService;

  public DepositController(DepositService depositService) {
    this.depositService = depositService;
  }

  @PostMapping
  public ResponseEntity<DepositResponse> create(@Valid @RequestBody DepositRequest request) {
    DepositService.CreateResult result = depositService.create(request);
    DepositResponse response = DepositResponse.from(result.deposit());
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
  }

  @GetMapping("/{depositId}")
  public DepositResponse get(@PathVariable String depositId) {
    return DepositResponse.from(depositService.findById(depositId));
  }
}
