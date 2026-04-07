package org.openphc.cce.emitter.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.openphc.cce.emitter.service.ForwardResult;
import org.openphc.cce.emitter.service.ForwardingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

        if (result.isSuccess()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"data": {"status": "ok"}}""");
        }

        if ("unreachable".equals(result.status())) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"error": {"code": "TARGET_UNREACHABLE", "message": "OpenHIM unreachable after %d attempts"}}"""
                            .formatted(result.attempts()));
        }

        // Failure — propagate OpenHIM's status code and response body
        int statusCode = result.statusCode() > 0 ? result.statusCode() : HttpStatus.BAD_GATEWAY.value();
        String errorMessage = result.body() != null ? result.body() : "Unknown error";

        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"error": {"code": "FORWARDING_ERROR", "message": "Forwarding to OpenHIM failed: %s"}}"""
                        .formatted(errorMessage));
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
