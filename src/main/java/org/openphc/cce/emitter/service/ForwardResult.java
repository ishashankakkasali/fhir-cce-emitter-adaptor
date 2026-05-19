package org.openphc.cce.emitter.service;

/**
 * Result of forwarding a FHIR resource to OpenHIM.
 * Captures success, failure (with HTTP details), or unreachable outcomes.
 *
 * @param status     outcome status: "success", "failure", or "unreachable"
 * @param statusCode HTTP status code from OpenHIM (0 if unreachable)
 * @param body       response body from OpenHIM (null if unreachable)
 * @param attempts   number of attempts made
 */
public record ForwardResult(String status, int statusCode, String body, int attempts) {

    /** Forward succeeded on first or retry attempt. */
    public static ForwardResult success() {
        return new ForwardResult("success", 200, null, 1);
    }

    /** Forward failed after retries — OpenHIM returned an error. */
    public static ForwardResult failure(int statusCode, String responseBody) {
        return new ForwardResult("failure", statusCode, responseBody, 0);
    }

    /** OpenHIM was unreachable after all attempts. */
    public static ForwardResult unreachable(int attempts) {
        return new ForwardResult("unreachable", 0, null, attempts);
    }

    /** Forward skipped — no Patient subject or RelatedPerson reference found. */
    public static ForwardResult skipped() {
        return new ForwardResult("skipped", 0, null, 0);
    }

    /** Whether the forward was successful. */
    public boolean isSuccess() {
        return "success".equals(status);
    }
}
