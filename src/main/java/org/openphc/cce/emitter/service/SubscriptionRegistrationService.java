package org.openphc.cce.emitter.service;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages FHIR R4 REST-hook Subscription resources on the configured FHIR server.
 * <p>
 * Creates Subscription resources via the HAPI FHIR client library. Tracks active
 * subscriptions in-memory for duplicate detection with server-side reconciliation.
 * <p>
 * Called by {@link org.openphc.cce.emitter.config.StartupSubscriptionRunner} on startup
 * to auto-subscribe to configured resource types.
 * <p>
 * <strong>Stub implementation</strong> — full implementation in sub-task F7.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistrationService.class);

    private final EmitterProperties emitterProperties;

    /**
     * Subscribe to changes for a given FHIR resource type on the configured FHIR server.
     *
     * @param resourceType FHIR resource type (e.g., "Patient", "Observation")
     * @param criteria     optional FHIR search criteria; {@code null} for all resources of the type
     * @return registration result with status: "registered", "already-exists", or "failed: &lt;message&gt;"
     */
    public RegistrationResult subscribe(String resourceType, String criteria) {
        // TODO: F7 — implement FHIR Subscription creation via HAPI FHIR client
        log.warn("subscribe() not yet implemented (F7) — returning stub result for {}", resourceType);
        return new RegistrationResult(resourceType, "failed: Not yet implemented — see sub-task F7");
    }
}
