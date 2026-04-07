package org.openphc.cce.emitter.service;

/**
 * Result of a FHIR subscription registration attempt.
 *
 * @param resourceType   the FHIR resource type that was subscribed to
 * @param serverName     the display name of the FHIR server
 * @param subscriptionId the server-assigned subscription ID (null if not created)
 * @param status         one of: {@code "registered"}, {@code "already-exists"},
 *                       or {@code "failed: <message>"}
 */
public record RegistrationResult(String resourceType, String serverName,
                                  String subscriptionId, String status) {

    /** Returns {@code true} if the subscription was successfully registered or already exists. */
    public boolean isSuccess() {
        return "registered".equals(status) || "already-exists".equals(status);
    }
}
