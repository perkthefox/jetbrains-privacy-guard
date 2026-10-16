package com.github.copilot.lang.agent.commands;

public record TelemetrySettings(TelemetryLevel telemetryLevel) {
    public enum TelemetryLevel {
        ALL,
        OFF
    }
}
