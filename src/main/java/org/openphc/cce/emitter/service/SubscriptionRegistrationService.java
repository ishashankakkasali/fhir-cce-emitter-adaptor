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
import org.openphc.cce.emitter.config.EmitterProperties.StartupSubscriptionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Counter subscriptionsDeletedCounter;
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
        this.subscriptionsDeletedCounter = Counter.builder("fhir.emitter.subscriptions.deleted")
                .description("Subscriptions successfully deleted from the FHIR server")
                .register(meterRegistry);

        meterRegistry.gauge("fhir.emitter.subscriptions.active", activeCount);
    }

    /**
     * Subscribes to all given resource types in a single pass: bulk-fetches existing
     * adaptor-owned subscriptions, skips duplicates, creates missing ones, and
     * deletes stale subscriptions whose resource type is no longer in the configured list.
     *
     * @param resourceTypeEntries list of entries, each {@code ["ResourceType", "criteriaFilter"]}
     * @return list of registration results (one per entry, plus one per deleted subscription)
     */
    public List<RegistrationResult> subscribeAll(List<String[]> resourceTypeEntries) {
        FhirServerConfig serverConfig = emitterProperties.getFhirServer();
        String serverName = serverConfig.getName();
        IGenericClient fhirClient = fhirClientFactory.createClient(serverConfig);

        StartupSubscriptionConfig subscriptionConfig = emitterProperties.getStartupSubscriptions();
        int fetchPageSize = subscriptionConfig.getFetchPageSize();
        int maxAttempts = subscriptionConfig.getFetchRetryMaxAttempts();
        long backoffMs = subscriptionConfig.getFetchRetryBackoffMs();

        // Bulk-fetch existing adaptor-owned subscriptions with retry.
        // If all retries fail, throw to abort startup (prevents duplicate subscriptions).
        Map<String, IIdType> existingSubscriptions;
        try {
            existingSubscriptions = loadExistingSubscriptionsWithRetry(fhirClient, fetchPageSize, maxAttempts, backoffMs, serverName);
        } catch (Exception e) {
            String reason = "Failed to load existing subscriptions after " + maxAttempts + " attempts: " + e.getMessage();
            log.error("Cannot load existing subscriptions from {} — aborting to prevent duplicates: {}",
                    serverName, reason);
            throw new IllegalStateException(
                    "Subscription reconciliation failed — cannot safely proceed without loading existing subscriptions from "
                            + serverName + ": " + reason, e);
        }

        // Pre-build criteria and desired keys from the configured entries
        List<SubscriptionEntry> entries = new ArrayList<>(resourceTypeEntries.size());
        Set<String> desiredKeys = new HashSet<>();
        for (String[] entry : resourceTypeEntries) {
            String resourceType = entry[0];
            String criteriaFilter = entry[1];
            String criteria = resourceType + "?" + (StringUtils.hasText(criteriaFilter) ? criteriaFilter : "");
            String key = buildSubscriptionLookupKey(resourceType, criteria);
            entries.add(new SubscriptionEntry(resourceType, criteria, key));
            desiredKeys.add(key);
        }

        List<RegistrationResult> registrationResults = new ArrayList<>();

        // Create missing subscriptions
        for (SubscriptionEntry entry : entries) {
            registrationResults.add(subscribeSingle(fhirClient, existingSubscriptions, entry.resourceType(), entry.criteria(), serverName));
        }

        // Delete stale subscriptions no longer in the configured list
        List<String> staleKeys = existingSubscriptions.keySet().stream()
                .filter(key -> !desiredKeys.contains(key))
                .toList();

        for (String staleKey : staleKeys) {
            registrationResults.add(deleteSingle(fhirClient, existingSubscriptions, staleKey, serverName));
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
                                                String criteria,
                                                String serverName) {
        String callbackUrl = emitterProperties.getSelfBaseUrl() + "/callback/" + resourceType.toLowerCase();
        String subscriptionKey = buildSubscriptionLookupKey(resourceType, criteria);

        try {
            if (existingSubscriptions.containsKey(subscriptionKey)) {
                log.info("Subscription already exists for {} on {} — skipping creation", resourceType, serverName);
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
     * Deletes a stale adaptor-owned subscription from the FHIR server.
     */
    private RegistrationResult deleteSingle(IGenericClient fhirClient,
                                             Map<String, IIdType> existingSubscriptions,
                                             String subscriptionKey,
                                             String serverName) {
        IIdType subscriptionId = existingSubscriptions.get(subscriptionKey);
        String resourceType = subscriptionKey.contains("|") ? subscriptionKey.substring(0, subscriptionKey.indexOf('|')) : subscriptionKey;

        try {
            log.info("Deleting stale subscription on {}: key={}, id={}",
                    serverName, subscriptionKey, subscriptionId.getValue());

            fhirClient.delete().resourceById(subscriptionId).execute();

            existingSubscriptions.remove(subscriptionKey);
            subscriptionsDeletedCounter.increment();

            log.info("Deleted stale subscription on {}: key={}, id={}",
                    serverName, subscriptionKey, subscriptionId.getValue());

            return new RegistrationResult(resourceType, serverName,
                    subscriptionId.getValue(), "deleted");

        } catch (Exception e) {
            log.warn("Failed to delete stale subscription on {}: key={}, id={}: {}",
                    serverName, subscriptionKey, subscriptionId.getValue(), e.getMessage(), e);
            return new RegistrationResult(resourceType, serverName,
                    subscriptionId.getValue(), "delete-failed: " + e.getMessage());
        }
    }

    /**
     * Fetches existing subscriptions with configurable retry and backoff.
     * Retries allow the FHIR server time to finish starting up (metadata endpoint readiness).
     *
     * @throws Exception if all retry attempts are exhausted
     */
    private Map<String, IIdType> loadExistingSubscriptionsWithRetry(IGenericClient fhirClient,
                                                                     int fetchPageSize,
                                                                     int maxAttempts,
                                                                     long backoffMs,
                                                                     String serverName) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return loadExistingSubscriptions(fhirClient, fetchPageSize);
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    // Exponential backoff: 1x, 2x, 4x, 8x... the base interval (e.g. 15s → 30s → 60s with default 15000ms)
                    long waitMs = backoffMs * (1L << (attempt - 1));
                    log.warn("Attempt {}/{} to load existing subscriptions from {} failed: {} — retrying in {}ms",
                            attempt, maxAttempts, serverName, e.getMessage(), waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Subscription fetch retry interrupted", ie);
                    }
                } else {
                    // Final attempt failed — propagate to caller for fail-fast startup abort
                    log.error("All {} attempts to load existing subscriptions from {} failed: {}",
                            maxAttempts, serverName, e.getMessage());
                    throw new RuntimeException("Failed after " + maxAttempts + " attempts: " + e.getMessage(), e);
                }
            }
        }
        // Logically unreachable: the loop always either returns or throws on the last iteration.
        // Required by the compiler since it cannot prove the loop body will execute (maxAttempts could be 0).
        throw new IllegalStateException("Retry loop exited unexpectedly");
    }

    /**
     * Bulk-fetches all adaptor-owned subscriptions from the FHIR server (by tag).
     * <p>
     * Query: {@code GET /Subscription?_tag=<OWNER_TAG_SYSTEM>|<OWNER_TAG_CODE>}
     *
     * @param fetchPageSize maximum number of subscriptions to fetch per query
     * @return mutable map of key → subscription ID (empty if server has none)
     * @throws RuntimeException if the FHIR server is unreachable or returns an error
     */
    private Map<String, IIdType> loadExistingSubscriptions(IGenericClient fhirClient, int fetchPageSize) {
        Map<String, IIdType> subscriptionsByKey = new HashMap<>();

        Bundle searchResultBundle = fhirClient.search()
                .forResource(Subscription.class)
                .withTag(OWNER_TAG_SYSTEM, OWNER_TAG_CODE)
                .count(fetchPageSize)
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
        return subscriptionsByKey;
    }

    /**
     * Builds a subscription lookup key from resource type and criteria for deduplication.
     */
    String buildSubscriptionLookupKey(String resourceType, String criteria) {
        return resourceType.toLowerCase() + "|" + criteria;
    }

    /** Pre-built subscription entry holding resource type, criteria, and lookup key. */
    private record SubscriptionEntry(String resourceType, String criteria, String key) {}
}
