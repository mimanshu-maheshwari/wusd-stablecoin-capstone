# wUSD Stablecoin Bridge

A hands-on Java, Spring Boot and Hyperledger Besu capstone project.

## Current milestone

Stage 1, Lesson 1: Java project setup and Gradle.

## Components

- bridge-service: Main Spring Boot application
- mock-core-banking: Simulated banking ledger (planned)
- smart-contracts: wUSD ERC-20 contract (planned)
- infrastructure: Docker, Besu and Kubernetes (planned)

## Requirements

- JDK 25
- Git
- Gradle wrapper included in bridge-service

## Build and test

```bash
cd bridge-service
./gradlew clean build
```

## Run

```bash
./gradlew bootRun
```

## Verify

```bash
curl http://localhost:8080/api/v1/hello
curl http://localhost:8080/actuator/health
```

## Status

The application provides HTTP and health endpoints. It does not yet handle real fiat deposits or block-chain transactions.
