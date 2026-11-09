package com.drift.backend.shipment;

public record ControlledScenarioApiError(String code, String message, boolean retryable) { }
