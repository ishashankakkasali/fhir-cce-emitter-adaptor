package org.openphc.cce.emitter.service;

/**
 * Result of a FHIR subscription registration attempt.
 *
 * @param resourceType the FHIR resource type that was subscribed to
 * @param status       one of: {@code "registered"}, {@code "already-exists"},
 *                     or {@code "failed: <message>"}
 */
public record RegistrationResult(String resourceType, String status) {

    /** Returns {@code true} if the subscription was successfully registered or already exists. */
    public boolean isSuccess() {
        return "registered".equals(status) || "already-exists".equals(status);
    }
}
