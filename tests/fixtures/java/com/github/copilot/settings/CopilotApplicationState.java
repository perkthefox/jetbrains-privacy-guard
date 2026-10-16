package com.github.copilot.settings;

public class CopilotApplicationState {
    public boolean enableTelemetry;
    public com.github.copilot.lang.agent.commands.FetchStrategy fetchStrategy;

    public CopilotApplicationState() {
        enableTelemetry = true;
        fetchStrategy = com.github.copilot.lang.agent.commands.FetchStrategy.Native;
    }
}
