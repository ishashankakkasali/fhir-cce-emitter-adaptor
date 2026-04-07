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
    public List<RegistrationResult> subscribeAll(List<String[]> resourceTypeEntries) {
        FhirServerConfig serverConfig = emitterProperties.getFhirServer();
        String serverName = serverConfig.getName();
        IGenericClient fhirClient = fhirClientFactory.createClient(serverConfig);

        // Bulk-fetch existing adaptor-owned subscriptions into a local map
        Map<String, IIdType> existingSubscriptions = loadExistingSubscriptions(fhirClient);

        List<RegistrationResult> registrationResults = new ArrayList<>();

        for (String[] resourceTypeEntry : resourceTypeEntries) {
            String resourceType = resourceTypeEntry[0];
            String criteriaFilter = resourceTypeEntry[1];
            registrationResults.add(subscribeSingle(fhirClient, existingSubscriptions, resourceType, criteriaFilter, serverName));
        }

        activeCount.set(existingSubscriptions.size());
        return registrationResults;
    }

    /**
     * Subscribes to a single resource type, checking the provided existing-subscriptions map.
     */
    private RegistrationResult subscribeSingle(IGenericClient fhirClient,
                                                Map<String, IIdType> existingSubscriptions,
                                                String resourceType,
                                                String criteriaFilter,
                                                String serverName) {
        String callbackUrl = emitterProperties.getSelfBaseUrl() + "/callback/" + resourceType.toLowerCase();
        String criteria = resourceType + "?" + (StringUtils.hasText(criteriaFilter) ? criteriaFilter : "");
        String subscriptionKey = buildSubscriptionLookupKey(resourceType, criteria);

        try {
            if (existingSubscriptions.containsKey(subscriptionKey)) {
                log.info("Subscription already exists for {} on {} — skipping creation", resourceType, serverName);
                subscriptionsCreatedCounter.increment();
                return new RegistrationResult(resourceType, serverName,
                        existingSubscriptions.get(subscriptionKey).getValue(), "already-exists");
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

            MethodOutcome outcome = fhirClient.create().resource(subscription).execute();
            IIdType createdSubscriptionId = outcome.getId();

            existingSubscriptions.put(subscriptionKey, createdSubscriptionId);

            subscriptionsCreatedCounter.increment();
            log.info("Subscription created for {} on {}: id={}",
                    resourceType, serverName, createdSubscriptionId.getValue());

            return new RegistrationResult(resourceType, serverName,
                    createdSubscriptionId.getValue(), "registered");

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
    private Map<String, IIdType> loadExistingSubscriptions(IGenericClient fhirClient) {
        Map<String, IIdType> subscriptionsByKey = new HashMap<>();
        try {
            Bundle searchResultBundle = fhirClient.search()
                    .forResource(Subscription.class)
                    .withTag(OWNER_TAG_SYSTEM, OWNER_TAG_CODE)
                    .returnBundle(Bundle.class)
                    .execute();

            if (searchResultBundle.getEntry() == null || searchResultBundle.getEntry().isEmpty()) {
                log.info("No existing adaptor-owned subscriptions found on server");
                return subscriptionsByKey;
            }

            for (Bundle.BundleEntryComponent bundleEntry : searchResultBundle.getEntry()) {
                Subscription existingSubscription = (Subscription) bundleEntry.getResource();
                if (existingSubscription.getCriteria() != null) {
                    String subscriptionCriteria = existingSubscription.getCriteria();
                    String resourceType = subscriptionCriteria.contains("?")
                            ? subscriptionCriteria.substring(0, subscriptionCriteria.indexOf('?'))
                            : subscriptionCriteria;
                    String subscriptionKey = buildSubscriptionLookupKey(resourceType, subscriptionCriteria);
                    subscriptionsByKey.put(subscriptionKey, existingSubscription.getIdElement());
                    log.debug("Loaded existing subscription: {} → {}", subscriptionKey, existingSubscription.getIdElement().getValue());
                }
            }

            log.info("Loaded {} existing adaptor-owned subscriptions from server", subscriptionsByKey.size());

        } catch (Exception e) {
            log.warn("Could not load existing subscriptions (will create new ones): {}", e.getMessage());
        }
        return subscriptionsByKey;
    }

    /**
     * Builds a subscription lookup key from resource type and criteria for deduplication.
     */
    String buildSubscriptionLookupKey(String resourceType, String criteria) {
        return resourceType.toLowerCase() + "|" + criteria;
    }

    /**
     * Returns the current active subscription count (for testing/observability).
     */
    int getActiveSubscriptionCount() {
        return activeCount.get();
    }
}
