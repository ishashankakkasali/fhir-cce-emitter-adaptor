package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates with configurable token endpoints to obtain Bearer tokens
 * for FHIR server communication.
 * <p>
 * Supports two token-based auth types:
 * <ul>
 *   <li>{@code token-endpoint} — custom endpoint (POSTs username/password,
 *       extracts token from cookie/header/body — used by SPICE)</li>
 *   <li>{@code oauth2} — standard OAuth2 Client Credentials grant</li>
 * </ul>
 * Tokens are cached with configurable TTL. Thread-safe via {@link ConcurrentHashMap}.
 */
@Service
public class TokenEndpointAuthService {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpointAuthService.class);
    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    /**
     * Cached token with fetch timestamp for TTL-based expiry.
     */
    private record CachedToken(String token, Instant fetchedAt, long ttlSeconds) {

        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plusSeconds(ttlSeconds));
        }
    }

    public TokenEndpointAuthService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a cached Bearer token if still valid, otherwise fetches a new one.
     *
     * @param authConfig FHIR server auth configuration
     * @return Bearer token string (with "Bearer " prefix)
     */
    public String getToken(FhirServerAuthConfig authConfig) {
        String cacheKey = authConfig.getTokenUrl();
        CachedToken cached = tokenCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            log.debug("Token cache hit for {}", cacheKey);
            return cached.token();
        }

        log.debug("Token cache miss for {} — fetching new token", cacheKey);
        return fetchAndCache(authConfig);
    }

    /**
     * Evicts the cached token and fetches a fresh one.
     *
     * @param authConfig FHIR server auth configuration
     * @return fresh Bearer token string (with "Bearer " prefix)
     */
    public String refreshToken(FhirServerAuthConfig authConfig) {
        String cacheKey = authConfig.getTokenUrl();
        tokenCache.remove(cacheKey);
        log.info("Token cache evicted for {} — fetching fresh token", cacheKey);
        return fetchAndCache(authConfig);
    }

    /**
     * Bundles a fetched token with an optional TTL override from the auth server.
     * <p>
     * Introduced to eliminate shared mutable state ({@code volatile long lastOAuth2Ttl})
     * that created a thread-safety race condition: concurrent {@code fetchAndCache()} calls
     * could read a stale TTL written by a different thread's fetch. By returning the TTL
     * as part of the result, each call's TTL flows through its own return value with no
     * shared state between threads.
     * <p>
     * A {@code ttlOverride} of {@code 0} means "no server-provided TTL" — the caller
     * falls back to the configured {@code tokenTtlSeconds} default.
     *
     * @param token       the Bearer token string
     * @param ttlOverride server-provided TTL in seconds (e.g., OAuth2 {@code expires_in}),
     *                    or {@code 0} to use the configured default
     */
    private record TokenFetchResult(String token, long ttlOverride) {}

    private String fetchAndCache(FhirServerAuthConfig authConfig) {
        TokenFetchResult result = fetchToken(authConfig);
        long ttl = result.ttlOverride() > 0 ? result.ttlOverride() : authConfig.getTokenTtlSeconds();

        tokenCache.put(authConfig.getTokenUrl(), new CachedToken(result.token(), Instant.now(), ttl));
        return result.token();
    }

    /**
     * Delegates to the appropriate fetch method based on auth type.
     */
    private TokenFetchResult fetchToken(FhirServerAuthConfig authConfig) {
        return switch (authConfig.getType().toLowerCase()) {
            case "token-endpoint" -> new TokenFetchResult(fetchTokenEndpoint(authConfig), 0);
            case "oauth2" -> fetchOAuth2Token(authConfig);
            default -> throw new RuntimeException(
                    "Unsupported auth type for token fetch: " + authConfig.getType());
        };
    }

    /**
     * Custom token-endpoint auth: POSTs username/password, extracts token from
     * cookie, header, body field, or raw body (in priority order).
     */
    private String fetchTokenEndpoint(FhirServerAuthConfig authConfig) {
        String tokenUrl = authConfig.getTokenUrl();
        if (!StringUtils.hasText(tokenUrl)) {
            throw new RuntimeException("token-url is required for token-endpoint auth");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (StringUtils.hasText(authConfig.getClient())) {
            headers.set("client", authConfig.getClient());
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("username", authConfig.getUsername());
        body.add("password", authConfig.getPassword());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        log.debug("Fetching token from token-endpoint: {}", tokenUrl);
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, String.class);
        } catch (Exception e) {
            throw new RuntimeException("Token endpoint request failed for " + tokenUrl + ": " + e.getMessage(), e);
        }

        if (response.getBody() == null && response.getHeaders().isEmpty()) {
            throw new RuntimeException("Empty response from token endpoint: " + tokenUrl);
        }

        // Extraction priority: 1. Cookie → 2. Authorization header → 3. JSON body field → 4. Raw body
        String token = extractTokenFromCookie(
                response.getHeaders(),
                authConfig.getTokenCookieName(),
                authConfig.isTokenCookieBase64());

        if (token == null) {
            token = extractTokenFromAuthorizationHeader(response.getHeaders());
        }

        if (token == null && StringUtils.hasText(authConfig.getTokenBodyField())) {
            token = extractFieldFromJson(response.getBody(), authConfig.getTokenBodyField());
        }

        if (token == null && StringUtils.hasText(response.getBody())) {
            token = response.getBody().trim();
            log.debug("Token extracted from raw response body (fallback)");
        }

        if (token == null || token.isBlank()) {
            throw new RuntimeException("No token found in response from " + tokenUrl
                    + " (checked cookie, header, body field, raw body)");
        }

        return ensureBearer(token);
    }

    /**
     * OAuth2 Client Credentials grant: POSTs grant_type, client_id, client_secret,
     * extracts access_token from JSON response.
     */
    private TokenFetchResult fetchOAuth2Token(FhirServerAuthConfig authConfig) {
        String tokenUrl = authConfig.getTokenUrl();
        if (!StringUtils.hasText(tokenUrl)) {
            throw new RuntimeException("token-url is required for oauth2 auth");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", GRANT_TYPE_CLIENT_CREDENTIALS);
        body.add("client_id", authConfig.getClientId());
        body.add("client_secret", authConfig.getClientSecret());
        if (StringUtils.hasText(authConfig.getScope())) {
            body.add("scope", authConfig.getScope());
        }

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        log.debug("Fetching OAuth2 token from: {}", tokenUrl);
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, String.class);
        } catch (Exception e) {
            throw new RuntimeException("OAuth2 token request failed for " + tokenUrl + ": " + e.getMessage(), e);
        }

        String responseBody = response.getBody();
        if (!StringUtils.hasText(responseBody)) {
            throw new RuntimeException("Empty response from OAuth2 token endpoint: " + tokenUrl);
        }

        String accessToken = extractFieldFromJson(responseBody, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new RuntimeException("No access_token in OAuth2 response from " + tokenUrl
                    + ": " + responseBody);
        }

        // Extract expires_in for TTL if present
        long ttlOverride = 0;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode expiresIn = root.get("expires_in");
            if (expiresIn != null && expiresIn.isNumber()) {
                ttlOverride = expiresIn.asLong();
                log.debug("OAuth2 token expires_in: {}s", ttlOverride);
            }
        } catch (Exception e) {
            log.debug("Could not parse expires_in from OAuth2 response: {}", e.getMessage());
        }

        return new TokenFetchResult(ensureBearer(accessToken), ttlOverride);
    }

    /**
     * Extracts a token from Set-Cookie headers matching the configured cookie name.
     *
     * @param headers    HTTP response headers
     * @param cookieName cookie name to look for (e.g., "AuthCookie")
     * @param base64     whether the cookie value is base64-encoded
     * @return extracted token, or {@code null} if cookie not found
     */
    String extractTokenFromCookie(HttpHeaders headers, String cookieName, boolean base64) {
        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        for (String cookie : cookies) {
            if (cookie.startsWith(cookieName + "=")) {
                String value = cookie.substring(cookieName.length() + 1);

                // Strip cookie attributes (Path, HttpOnly, etc.)
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    value = value.substring(0, semicolonIndex);
                }

                if (base64) {
                    try {
                        value = new String(Base64.getDecoder().decode(value));
                    } catch (IllegalArgumentException e) {
                        log.warn("Failed to base64-decode cookie '{}': {}", cookieName, e.getMessage());
                    }
                }

                log.debug("Token extracted from Set-Cookie: {}", cookieName);
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Extracts a token from the Authorization response header.
     */
    private String extractTokenFromAuthorizationHeader(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader)) {
            log.debug("Token extracted from Authorization response header");
            return authHeader.trim();
        }
        return null;
    }

    /**
     * Extracts a field value from a JSON string using Jackson.
     *
     * @param json      JSON string
     * @param fieldName top-level field name (e.g., "access_token", "token")
     * @return field value, or {@code null} if not found or parse error
     */
    String extractFieldFromJson(String json, String fieldName) {
        if (!StringUtils.hasText(json) || !StringUtils.hasText(fieldName)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode field = root.get(fieldName);
            if (field != null && !field.isNull()) {
                log.debug("Token extracted from JSON body field: {}", fieldName);
                return field.asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON for field '{}': {}", fieldName, e.getMessage());
        }
        return null;
    }

    /**
     * Ensures the token has the "Bearer " prefix.
     */
    String ensureBearer(String token) {
        if (token == null) {
            return null;
        }
        token = token.trim();
        if (token.startsWith("Bearer ")) {
            return token;
        }
        return "Bearer " + token;
    }
}
