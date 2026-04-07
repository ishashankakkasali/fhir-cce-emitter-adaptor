package org.openphc.cce.emitter.service;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Subscription;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages FHIR R4 REST-hook Subscription resources on the configured FHIR server.
 * <p>
 * Creates Subscription resources via the HAPI FHIR client library, tagging each
 * with the service name ({@value #OWNER_TAG_CODE}) so they can be identified as
 * adaptor-owned. On startup, performs a single bulk fetch by tag to reconcile
 * existing subscriptions — only missing ones are created.
 * <p>
 * Called by {@link org.openphc.cce.emitter.config.StartupSubscriptionRunner} on startup
 * to auto-subscribe to configured resource types.
 */
@Service
public class SubscriptionRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistrationService.class);

    /** Tag system URI identifying subscriptions owned by this adaptor. */
    static final String OWNER_TAG_SYSTEM = "https://openphc.org/cce/fhir-emitter";

    /** Tag code — matches spring.application.name, identifies this service. */
    static final String OWNER_TAG_CODE = "fhir-cce-emitter-adaptor";

    private final FhirClientFactory fhirClientFactory;
    private final EmitterProperties emitterProperties;

    // Metrics
    private final Counter subscriptionsCreatedCounter;
    private final Counter subscriptionsFailedCounter;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public SubscriptionRegistrationService(FhirClientFactory fhirClientFactory,
                                            EmitterProperties emitterProperties,
                                            MeterRegistry meterRegistry) {
        this.fhirClientFactory = fhirClientFactory;
        this.emitterProperties = emitterProperties;

        this.subscriptionsCreatedCounter = Counter.builder("fhir.emitter.subscriptions.created")
                .description("Subscriptions successfully created on the FHIR server")
                .register(meterRegistry);
        this.subscriptionsFailedCounter = Counter.builder("fhir.emitter.subscriptions.failed")
                .description("Subscription creation failures")
                .register(meterRegistry);

        meterRegistry.gauge("fhir.emitter.subscriptions.active", activeCount);
    }

    /**
     * Subscribes to all given resource types in a single pass: bulk-fetches existing
     * adaptor-owned subscriptions, skips duplicates, and creates missing ones.
     *
     * @param entries list of entries, each {@code "ResourceType"} or {@code "ResourceType?filter"}
     * @return list of registration results (one per entry)
     */
    public List<RegistrationResult> subscribeAll(List<String[]> entries) {
        FhirServerConfig serverConfig = emitterProperties.getFhirServer();
        String serverName = serverConfig.getName();
        IGenericClient client = fhirClientFactory.createClient(serverConfig);

        // Bulk-fetch existing adaptor-owned subscriptions into a local map
        Map<String, IIdType> existing = loadExistingSubscriptions(client);

        List<RegistrationResult> results = new ArrayList<>();

        for (String[] parsed : entries) {
            String resourceType = parsed[0];
            String criteriaFilter = parsed[1];
            results.add(subscribeSingle(client, existing, resourceType, criteriaFilter, serverName));
        }

        activeCount.set(existing.size());
        return results;
    }

    /**
     * Subscribes to a single resource type, checking the provided existing-subscriptions map.
     */
    private RegistrationResult subscribeSingle(IGenericClient client,
                                                Map<String, IIdType> existing,
                                                String resourceType,
                                                String criteriaFilter,
                                                String serverName) {
        String callbackUrl = emitterProperties.getSelfBaseUrl() + "/callback/" + resourceType.toLowerCase();
        String criteria = resourceType + "?" + (StringUtils.hasText(criteriaFilter) ? criteriaFilter : "");
        String key = buildKey(resourceType, criteria);

        try {
            if (existing.containsKey(key)) {
                log.info("Subscription already exists for {} on {} — skipping creation", resourceType, serverName);
                subscriptionsCreatedCounter.increment();
                return new RegistrationResult(resourceType, serverName,
                        existing.get(key).getValue(), "already-exists");
            }

            Subscription subscription = new Subscription();
            subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            subscription.setCriteria(criteria);
            subscription.setReason("FHIR CCE Emitter Adaptor — auto-subscribe for " + resourceType);
            subscription.getMeta().addTag(new Coding(OWNER_TAG_SYSTEM, OWNER_TAG_CODE,
                    "Owned by " + OWNER_TAG_CODE));

            Subscription.SubscriptionChannelComponent channel = new Subscription.SubscriptionChannelComponent();
            channel.setType(Subscription.SubscriptionChannelType.RESTHOOK);
            channel.setEndpoint(callbackUrl);
            channel.setPayload("application/fhir+json");
            subscription.setChannel(channel);

            log.info("Creating subscription for {} on {}: criteria={}, callback={}",
                    resourceType, serverName, criteria, callbackUrl);

            MethodOutcome outcome = client.create().resource(subscription).execute();
            IIdType subscriptionId = outcome.getId();

            existing.put(key, subscriptionId);

            subscriptionsCreatedCounter.increment();
            log.info("Subscription created for {} on {}: id={}",
                    resourceType, serverName, subscriptionId.getValue());

            return new RegistrationResult(resourceType, serverName,
                    subscriptionId.getValue(), "registered");

        } catch (Exception e) {
            subscriptionsFailedCounter.increment();
            log.error("Failed to create subscription for {} on {}: {}",
                    resourceType, serverName, e.getMessage(), e);
            return new RegistrationResult(resourceType, serverName, null,
                    "failed: " + e.getMessage());
        }
    }

    /**
     * Bulk-fetches all adaptor-owned subscriptions from the FHIR server (by tag).
     * <p>
     * Query: {@code GET /Subscription?_tag=<OWNER_TAG_SYSTEM>|<OWNER_TAG_CODE>}
     *
     * @return mutable map of key → subscription ID (empty on error or no results)
     */
    private Map<String, IIdType> loadExistingSubscriptions(IGenericClient client) {
        Map<String, IIdType> map = new HashMap<>();
        try {
            Bundle bundle = client.search()
                    .forResource(Subscription.class)
                    .withTag(OWNER_TAG_SYSTEM, OWNER_TAG_CODE)
                    .returnBundle(Bundle.class)
                    .execute();

            if (bundle.getEntry() == null || bundle.getEntry().isEmpty()) {
                log.info("No existing adaptor-owned subscriptions found on server");
                return map;
            }

            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Subscription sub = (Subscription) entry.getResource();
                if (sub.getCriteria() != null) {
                    String subCriteria = sub.getCriteria();
                    String resourceType = subCriteria.contains("?")
                            ? subCriteria.substring(0, subCriteria.indexOf('?'))
                            : subCriteria;
                    String key = buildKey(resourceType, subCriteria);
                    map.put(key, sub.getIdElement());
                    log.debug("Loaded existing subscription: {} → {}", key, sub.getIdElement().getValue());
                }
            }

            log.info("Loaded {} existing adaptor-owned subscriptions from server", map.size());

        } catch (Exception e) {
            log.warn("Could not load existing subscriptions (will create new ones): {}", e.getMessage());
        }
        return map;
    }

    /**
     * Builds a subscription tracking key from resource type and criteria.
     */
    String buildKey(String resourceType, String criteria) {
        return resourceType.toLowerCase() + "|" + criteria;
    }

    /**
     * Returns the current active subscription count (for testing/observability).
     */
    int getActiveSubscriptionCount() {
        return activeCount.get();
    }
}
