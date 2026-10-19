package io.mateu.ecdemo1.iaagent.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness follows the configuration, not the process.
 *
 * <p>A pod that started but has never reached the control plane has no model, no credential and no
 * tools, for any agent; it would answer every prompt with an error. Reporting DOWN keeps it out of the Service's
 * endpoints, so the gateway's route has nowhere to send a prompt and the panel fails at the door
 * rather than after a round trip — and, in a deployment with more than one replica, sends the
 * prompt to a pod that can serve it.
 *
 * <p>Running on a stale copy is deliberately still UP. It is degraded and says so in {@code
 * details}, but a configuration from a minute ago is almost certainly still right, and taking the
 * panel down because a catalogue is briefly unreachable is worse than serving it.
 *
 * <p>Registered under {@code readiness} only — see application.yaml. Liveness must never depend on
 * another service: an unreachable control plane would restart this pod in a loop, which fixes
 * nothing and loses the cache that was keeping it working.
 *
 * <p>It is also the pod's refresh loop, which is not obvious from the name. See {@link #health()}.
 */
@Component("agentConfiguration")
@org.springframework.context.annotation.Profile("!workflow")
public class AgentConfigHealthIndicator implements HealthIndicator {

    private final AgentConfigClient client;

    public AgentConfigHealthIndicator(AgentConfigClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        // This call is what actually reaches the control plane, and it has to be here rather than
        // only on the prompt path: without it a pod that starts before the control plane is up
        // would never become ready. The client's TTL keeps it to one request per 30s.
        var defaultAgent = client.defaultAgentId();

        var failure = client.lastFetchFailed();
        if (!client.hasEverResolved()) {
            return Health.down()
                    .withDetail("reason", failure == null ? "control plane not reached yet" : failure)
                    .build();
        }
        var health = Health.up().withDetail("defaultAgent", String.valueOf(defaultAgent));
        if (failure != null) {
            health.withDetail("degraded", "serving the last good configurations; last call failed")
                    .withDetail("lastFailure", failure);
        }
        return health.build();
    }
}
