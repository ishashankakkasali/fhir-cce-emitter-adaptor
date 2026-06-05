package org.openphc.cce.emitter.config;

import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.service.RegistrationResult;
import org.openphc.cce.emitter.service.SubscriptionRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Auto-subscribes to configured FHIR resource types on startup.
 * <p>
 * Activated only when {@code emitter.startup-subscriptions.enabled=true}.
 * Waits {@code delay-seconds} before subscribing to allow the FHIR server
 * to become ready. Subscription failures are logged but do not prevent
 * remaining subscriptions or application startup.
 * <p>
 * Each entry in {@code resource-types} may optionally include FHIR search
 * criteria filters after a {@code ?} separator. Multiple criteria are
 * supported using {@code &} (standard FHIR query parameter syntax). Examples:
 * <ul>
 *   <li>{@code Patient} — subscribe to all Patient changes</li>
 *   <li>{@code Observation?code=1234} — single criteria filter</li>
 *   <li>{@code Encounter?status=finished&class=AMB} — multiple criteria filters</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "emitter.startup-subscriptions", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class StartupSubscriptionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSubscriptionRunner.class);

    private final EmitterProperties emitterProperties;
    private final SubscriptionRegistrationService registrationService;

    @Override
    public void run(ApplicationArguments args) {
        EmitterProperties.StartupSubscriptionConfig subscriptionConfig =
                emitterProperties.getStartupSubscriptions();

        List<String> resourceTypes = subscriptionConfig.getResourceTypes();

        if (resourceTypes == null || resourceTypes.isEmpty()) {
            log.info("Startup subscriptions enabled but no resource types configured — skipping");
            return;
        }

        int delaySeconds = subscriptionConfig.getDelaySeconds();
        log.info("Startup subscriptions: waiting {}s before subscribing to {} resource types",
                delaySeconds, resourceTypes.size());

        if (delaySeconds > 0) {
            try {
                Thread.sleep(delaySeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Startup subscription delay interrupted — proceeding with subscriptions");
            }
        }

        // Parse entries and delegate to service
        List<String[]> resourceTypeEntries = resourceTypes.stream()
                .map(this::parseCriteria)
                .toList();

        List<RegistrationResult> registrationResults;
        try {
            registrationResults = registrationService.subscribeAll(resourceTypeEntries);
        } catch (IllegalStateException e) {
            // Fatal: FHIR server unreachable after all retries — abort startup.
            // Docker restart policy will retry the container.
            log.error("FATAL: Subscription reconciliation failed — shutting down. "
                    + "Docker restart policy will retry. Reason: {}", e.getMessage());
            throw e;
        }

        long succeeded = registrationResults.stream()
                .filter(r -> "registered".equals(r.status()) || "already-exists".equals(r.status()))
                .count();
        long deleted = registrationResults.stream()
                .filter(r -> "deleted".equals(r.status()))
                .count();
        long failed = registrationResults.size() - succeeded - deleted;

        log.info("Startup subscriptions complete: {} succeeded, {} deleted, {} failed (total: {})",
                succeeded, deleted, failed, registrationResults.size());
    }

    /**
     * Parses a resource-type configuration entry into {@code [resourceType, criteriaFilter]}.
     *
     * <p>Splits on the first {@code ?} character. Everything before it is the FHIR resource type;
     * everything after is the criteria filter string (which may contain {@code &} for multiple
     * criteria). If no {@code ?} is present, the criteria filter defaults to an empty string.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "Patient"} → {@code ["Patient", ""]}</li>
     *   <li>{@code "Observation?code=1234"} → {@code ["Observation", "code=1234"]}</li>
     *   <li>{@code "Encounter?status=finished&class=AMB"} → {@code ["Encounter", "status=finished&class=AMB"]}</li>
     * </ul>
     *
     * @param entry a resource-type configuration entry, optionally containing criteria after {@code ?}
     * @return a two-element array: {@code [resourceType, criteriaFilter]}
     */
    String[] parseCriteria(String entry) {
        int queryIdx = entry.indexOf('?');
        if (queryIdx >= 0) {
            return new String[]{
                    entry.substring(0, queryIdx).trim(),
                    entry.substring(queryIdx + 1).trim()
            };
        }
        return new String[]{entry.trim(), ""};
    }
}
