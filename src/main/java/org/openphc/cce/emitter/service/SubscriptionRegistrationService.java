package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Subscription;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final FhirContext fhirContext;
    private final EmitterProperties emitterProperties;
    private final TokenEndpointAuthService tokenEndpointAuthService;

    // In-memory tracking: key = "resourceType|criteria" → subscription server ID
    private final ConcurrentHashMap<String, IIdType> activeSubscriptions = new ConcurrentHashMap<>();

    // One-time bulk fetch flag
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // Metrics
    private final Counter subscriptionsCreatedCounter;
    private final Counter subscriptionsFailedCounter;

    public SubscriptionRegistrationService(FhirContext fhirContext,
                                            EmitterProperties emitterProperties,
                                            TokenEndpointAuthService tokenEndpointAuthService,
                                            MeterRegistry meterRegistry) {
        this.fhirContext = fhirContext;
        this.emitterProperties = emitterProperties;
        this.tokenEndpointAuthService = tokenEndpointAuthService;

        this.subscriptionsCreatedCounter = Counter.builder("fhir.emitter.subscriptions.created")
                .description("Subscriptions successfully created on the FHIR server")
                .register(meterRegistry);
        this.subscriptionsFailedCounter = Counter.builder("fhir.emitter.subscriptions.failed")
                .description("Subscription creation failures")
                .register(meterRegistry);

        // Gauge tracks the current size of the active subscriptions map
        meterRegistry.gauge("fhir.emitter.subscriptions.active", activeSubscriptions, ConcurrentHashMap::size);
    }

    /**
     * Subscribe to changes for a given FHIR resource type on the configured FHIR server.
     * <p>
     * On the first call, performs a single bulk fetch of all adaptor-owned subscriptions
     * (tagged with {@value #OWNER_TAG_SYSTEM}|{@value #OWNER_TAG_CODE}) and populates
     * the in-memory map. Subsequent calls check the map without additional server calls.
     *
     * @param resourceType   FHIR resource type (e.g., "Patient", "Observation")
     * @param criteriaFilter optional FHIR search criteria filter; {@code null} for all resources of the type
     * @return registration result with status: "registered", "already-exists", or "failed: &lt;message&gt;"
     */
    public RegistrationResult subscribe(String resourceType, String criteriaFilter) {
        FhirServerConfig serverConfig = emitterProperties.getFhirServer();
        String serverName = serverConfig.getName();

        String callbackUrl = emitterProperties.getSelfBaseUrl() + "/callback/" + resourceType.toLowerCase();
        String criteria = resourceType + "?" + (StringUtils.hasText(criteriaFilter) ? criteriaFilter : "");
        String key = buildKey(resourceType, criteria);

        try {
            IGenericClient client = createAuthenticatedClient(serverConfig);

            // One-time bulk fetch of all adaptor-owned subscriptions
            if (initialized.compareAndSet(false, true)) {
                loadExistingSubscriptions(client);
            }

            // Check in-memory map (populated by bulk fetch)
            if (activeSubscriptions.containsKey(key)) {
                log.info("Subscription already exists for {} on {} — skipping creation", resourceType, serverName);
                subscriptionsCreatedCounter.increment();
                return new RegistrationResult(resourceType, serverName,
                        activeSubscriptions.get(key).getValue(), "already-exists");
            }

            // Create new Subscription resource with owner tag
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

            // Track in-memory
            activeSubscriptions.put(key, subscriptionId);

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
     * Bulk-fetches all adaptor-owned subscriptions from the FHIR server (by tag)
     * and populates the in-memory tracking map. Called once on first subscribe().
     * <p>
     * Query: {@code GET /Subscription?_tag=<OWNER_TAG_SYSTEM>|<OWNER_TAG_CODE>}
     * <p>
     * This replaces per-resource-type existence checks with a single API call.
     */
    void loadExistingSubscriptions(IGenericClient client) {
        try {
            Bundle bundle = client.search()
                    .forResource(Subscription.class)
                    .withTag(OWNER_TAG_SYSTEM, OWNER_TAG_CODE)
                    .returnBundle(Bundle.class)
                    .execute();

            if (bundle.getEntry() == null || bundle.getEntry().isEmpty()) {
                log.info("No existing adaptor-owned subscriptions found on server");
                return;
            }

            int count = 0;
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                Subscription sub = (Subscription) entry.getResource();
                if (sub.getCriteria() != null) {
                    // Extract resourceType from criteria (e.g., "Patient?" → "Patient")
                    String subCriteria = sub.getCriteria();
                    String resourceType = subCriteria.contains("?")
                            ? subCriteria.substring(0, subCriteria.indexOf('?'))
                            : subCriteria;
                    String key = buildKey(resourceType, subCriteria);
                    activeSubscriptions.put(key, sub.getIdElement());
                    count++;
                    log.debug("Loaded existing subscription: {} → {}", key, sub.getIdElement().getValue());
                }
            }

            log.info("Loaded {} existing adaptor-owned subscriptions from server", count);

        } catch (Exception e) {
            log.warn("Could not load existing subscriptions (will create new ones): {}", e.getMessage());
        }
    }

    /**
     * Creates a HAPI FHIR IGenericClient authenticated per the server's auth config.
     */
    IGenericClient createAuthenticatedClient(FhirServerConfig serverConfig) {
        IGenericClient client = fhirContext.newRestfulGenericClient(serverConfig.getUrl());
        FhirServerAuthConfig authConfig = serverConfig.getAuth();
        String authType = authConfig.getType() != null ? authConfig.getType().toLowerCase() : "none";

        switch (authType) {
            case "basic" -> {
                String credentials = authConfig.getUsername() + ":" + authConfig.getPassword();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
                client.registerInterceptor(createHeaderInterceptor("Authorization", "Basic " + encoded));
            }
            case "bearer" -> client.registerInterceptor(
                    createHeaderInterceptor("Authorization", "Bearer " + authConfig.getToken()));
            case "token-endpoint" -> {
                String token = tokenEndpointAuthService.getToken(authConfig);
                client.registerInterceptor(createHeaderInterceptor("Authorization", token));
                if (StringUtils.hasText(authConfig.getClient())) {
                    client.registerInterceptor(createHeaderInterceptor("client", authConfig.getClient()));
                }
            }
            case "oauth2" -> {
                String token = tokenEndpointAuthService.getToken(authConfig);
                client.registerInterceptor(createHeaderInterceptor("Authorization", token));
            }
            case "none" -> { /* No auth */ }
            default -> log.warn("Unknown FHIR server auth type: {} — no auth applied", authType);
        }

        return client;
    }

    /**
     * Creates a HAPI FHIR client interceptor that adds a custom header to every request.
     */
    IClientInterceptor createHeaderInterceptor(String headerName, String headerValue) {
        return new IClientInterceptor() {
            @Override
            public void interceptRequest(IHttpRequest theRequest) {
                theRequest.addHeader(headerName, headerValue);
            }

            @Override
            public void interceptResponse(IHttpResponse theResponse) throws IOException {
                // No-op — only request headers needed
            }
        };
    }

    /**
     * Builds a subscription tracking key from resource type and criteria.
     */
    String buildKey(String resourceType, String criteria) {
        return resourceType.toLowerCase() + "|" + criteria;
    }

    /**
     * Returns the current number of tracked active subscriptions (for testing).
     */
    int getActiveSubscriptionCount() {
        return activeSubscriptions.size();
    }
}
