package org.openphc.cce.emitter.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.service.ForwardResult;
import org.openphc.cce.emitter.service.ForwardingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller that receives FHIR Subscription REST-hook notifications
 * from the FHIR server and triggers synchronous forwarding to OpenHIM.
 * <p>
 * The FHIR server sends PUT/POST callbacks to {@code /callback/{resourceType}/...}
 * when subscribed resources change. This controller receives those callbacks,
 * delegates to {@link ForwardingEngine} for synchronous forwarding, and returns
 * structured responses following the CCE platform convention.
 * <p>
 * GET/HEAD requests are treated as pings — FHIR servers verify endpoint
 * reachability before activating subscriptions.
 */
@RestController
@RequestMapping("/callback")
@RequiredArgsConstructor
public class SubscriptionCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionCallbackController.class);

    private final ForwardingEngine forwardingEngine;

    /**
     * Handles REST-hook callbacks from the FHIR server (PUT/POST).
     * Forwards the FHIR resource JSON synchronously to OpenHIM.
     *
     * @param resourceType FHIR resource type from the URL path (e.g., "patient", "encounter")
     * @param resourceJson raw FHIR JSON payload
     * @param headers      HTTP request headers
     * @param request      the servlet request (for URI logging)
     * @return structured response following CCE platform convention
     */
    @RequestMapping(
            value = {"/{resourceType}", "/{resourceType}/**"},
            method = {RequestMethod.POST, RequestMethod.PUT},
            consumes = {"application/json", "application/fhir+json"})
    public ResponseEntity<String> handleCallback(
            @PathVariable String resourceType,
            @RequestBody String resourceJson,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {

        log.info("Received {} callback [resourceType={}] uri={} payload={}B",
                request.getMethod(), resourceType, request.getRequestURI(),
                resourceJson != null ? resourceJson.length() : 0);

        ForwardResult result = forwardingEngine.forward(resourceType, resourceJson);

        // Always return 200 OK with empty body regardless of forwarding outcome.
        //
        // HAPI FHIR uses its own FHIR client internally to make REST-hook callbacks.
        // Any non-2xx response OR a 2xx response with a non-FHIR body causes the FHIR
        // client to throw an exception, which RetryingMessageHandlerWrapper catches and
        // retries indefinitely — producing an infinite redelivery loop for the same resource.
        //
        // The FHIR server's responsibility ends at delivering the notification. Forwarding
        // outcomes (failures, unreachable OpenHIM) are already logged and metered in
        // ForwardingEngine — no need to propagate them back to the FHIR server.
        if (!result.isSuccess()) {
            if ("skipped".equals(result.status())) {
                log.info("Forward skipped for {} callback — no Patient subject or RelatedPerson reference",
                        resourceType);
            } else {
                log.warn("Forwarding failed for {} callback but acknowledging to FHIR server to prevent redelivery loop: status={}",
                        resourceType, result.status());
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Handles ping requests from the FHIR server (GET/HEAD).
     * FHIR servers verify endpoint reachability before activating subscriptions.
     *
     * @param resourceType FHIR resource type from the URL path
     * @return 200 OK with plain text "OK"
     */
    @RequestMapping({"/{resourceType}", "/{resourceType}/**"})
    public ResponseEntity<String> ping(@PathVariable String resourceType) {
        log.debug("Ping received [resourceType={}]", resourceType);
        return ResponseEntity.ok("OK");
    }
}
