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
        EmitterProperties.StartupSubscriptionConfig config =
                emitterProperties.getStartupSubscriptions();

        List<String> resourceTypes = config.getResourceTypes();

        if (resourceTypes == null || resourceTypes.isEmpty()) {
            log.info("Startup subscriptions enabled but no resource types configured — skipping");
            return;
        }

        int delaySeconds = config.getDelaySeconds();
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

        int succeeded = 0;
        int failed = 0;

        for (String resourceType : resourceTypes) {
            try {
                RegistrationResult result = registrationService.subscribe(resourceType, null);

                if (result.isSuccess()) {
                    succeeded++;
                    log.info("Startup subscription [{}]: {}", resourceType, result.status());
                } else {
                    failed++;
                    log.warn("Startup subscription [{}]: {}", resourceType, result.status());
                }
            } catch (Exception e) {
                failed++;
                log.error("Startup subscription [{}] failed with exception: {}",
                        resourceType, e.getMessage(), e);
            }
        }

        log.info("Startup subscriptions complete: {} succeeded, {} failed (total: {})",
                succeeded, failed, resourceTypes.size());
    }
}
