package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenEndpointAuthService}.
 * Covers all 4 token-endpoint extraction paths, OAuth2 Client Credentials grant,
 * caching, TTL, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class TokenEndpointAuthServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private TokenEndpointAuthService authService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        authService = new TokenEndpointAuthService(restTemplate, objectMapper);
    }

    private FhirServerAuthConfig tokenEndpointConfig() {
        FhirServerAuthConfig config = new FhirServerAuthConfig();
        config.setType("token-endpoint");
        config.setTokenUrl("http://auth-service:8089/authenticate");
        config.setUsername("testuser");
        config.setPassword("testpass");
        config.setClient("web");
        config.setTokenCookieName("AuthCookie");
        config.setTokenCookieBase64(true);
        config.setTokenTtlSeconds(3600);
        return config;
    }

    private FhirServerAuthConfig oauth2Config() {
        FhirServerAuthConfig config = new FhirServerAuthConfig();
        config.setType("oauth2");
        config.setTokenUrl("http://keycloak:8080/realms/test/protocol/openid-connect/token");
        config.setClientId("my-client");
        config.setClientSecret("my-secret");
        config.setTokenTtlSeconds(3600);
        return config;
    }

    private ResponseEntity<String> responseWithCookie(String cookieName, String cookieValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookieName + "=" + cookieValue + "; Path=/; HttpOnly");
        return new ResponseEntity<>("", headers, HttpStatus.OK);
    }

    private ResponseEntity<String> responseWithAuthHeader(String authValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, authValue);
        return new ResponseEntity<>("", headers, HttpStatus.OK);
    }

    private ResponseEntity<String> responseWithBody(String body) {
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatus.OK);
    }

    // ── a. Token extraction — Set-Cookie ────────────────────────────────

    @Nested
    class SetCookieExtraction {

        @Test
        void cookieFoundWithConfiguredName_tokenExtracted() {
            String base64Token = Base64.getEncoder().encodeToString("jwt-token-123".getBytes());
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithCookie("AuthCookie", base64Token));

            String token = authService.getToken(tokenEndpointConfig());

            assertEquals("Bearer jwt-token-123", token);
        }

        @Test
        void cookieValueIsBase64Encoded_decoded() {
            String originalToken = "eyJhbGciOiJIUzI1NiJ9.test.signature";
            String base64Token = Base64.getEncoder().encodeToString(originalToken.getBytes());
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithCookie("AuthCookie", base64Token));

            String token = authService.getToken(tokenEndpointConfig());

            assertEquals("Bearer " + originalToken, token);
        }

        @Test
        void cookieHasAttributes_stripped() {
            String base64Token = Base64.getEncoder().encodeToString("my-token".getBytes());
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.SET_COOKIE, "AuthCookie=" + base64Token + "; Path=/; HttpOnly; Secure; SameSite=Strict");
            ResponseEntity<String> response = new ResponseEntity<>("", headers, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(response);

            String token = authService.getToken(tokenEndpointConfig());

            assertEquals("Bearer my-token", token);
        }

        @Test
        void cookieNotFound_fallsThrough() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.SET_COOKIE, "OtherCookie=value; Path=/");
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer fallback-token");
            ResponseEntity<String> response = new ResponseEntity<>("", headers, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(response);

            String token = authService.getToken(tokenEndpointConfig());

            assertEquals("Bearer fallback-token", token);
        }

        @Test
        void tokenCookieBase64False_rawCookieValueUsed() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieBase64(false);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithCookie("AuthCookie", "raw-token-value"));

            String token = authService.getToken(config);

            assertEquals("Bearer raw-token-value", token);
        }
    }

    // ── b. Token extraction — Authorization header ──────────────────────

    @Nested
    class AuthorizationHeaderExtraction {

        @Test
        void authorizationHeaderPresent_tokenExtracted() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithAuthHeader("Bearer header-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistentCookie");
            String token = authService.getToken(config);

            assertEquals("Bearer header-token", token);
        }

        @Test
        void hasBearerPrefix_preserved() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithAuthHeader("Bearer already-prefixed"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            String token = authService.getToken(config);

            assertEquals("Bearer already-prefixed", token);
        }

        @Test
        void noBearerPrefix_prepended() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithAuthHeader("just-a-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            String token = authService.getToken(config);

            assertEquals("Bearer just-a-token", token);
        }
    }

    // ── c. Token extraction — JSON body field ───────────────────────────

    @Nested
    class JsonBodyFieldExtraction {

        @Test
        void tokenBodyFieldConfigured_extractsFromJsonBody() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setTokenBodyField("token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("{\"token\": \"json-body-token\"}"));

            String token = authService.getToken(config);

            assertEquals("Bearer json-body-token", token);
        }

        @Test
        void fieldNameToken_extracted() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setTokenBodyField("access_token");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("{\"access_token\": \"at-12345\"}"));

            String token = authService.getToken(config);

            assertEquals("Bearer at-12345", token);
        }

        @Test
        void fieldNotFound_fallsThroughToRawBody() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setTokenBodyField("nonexistent_field");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("raw-body-fallback-token"));

            String token = authService.getToken(config);

            assertEquals("Bearer raw-body-fallback-token", token);
        }
    }

    // ── d. Token extraction — raw body fallback ─────────────────────────

    @Nested
    class RawBodyFallback {

        @Test
        void noCookieNoHeaderNoField_rawBodyUsed() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("plain-token-string"));

            String token = authService.getToken(config);

            assertEquals("Bearer plain-token-string", token);
        }

        @Test
        void rawBodyTrimmedOfWhitespace() {
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("  whitespace-token  \n"));

            String token = authService.getToken(config);

            assertEquals("Bearer whitespace-token", token);
        }
    }

    // ── e. Token caching ────────────────────────────────────────────────

    @Nested
    class TokenCaching {

        @Test
        void firstCall_fetchesAndCaches() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("cached-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            authService.getToken(config);

            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }

        @Test
        void secondCallSameUrl_cached() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("cached-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            authService.getToken(config);
            authService.getToken(config);

            // Only 1 HTTP call — second was cached
            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }

        @Test
        void refreshToken_evictsCacheAndFetchesNew() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("first-token"))
                    .thenReturn(responseWithBody("refreshed-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            String first = authService.getToken(config);
            String refreshed = authService.refreshToken(config);

            assertEquals("Bearer first-token", first);
            assertEquals("Bearer refreshed-token", refreshed);
            verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }
    }

    // ── f. Token TTL ────────────────────────────────────────────────────

    @Nested
    class TokenTtl {

        @Test
        void tokenWithinTtl_returnedFromCache() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("valid-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setTokenTtlSeconds(3600); // 1 hour TTL
            authService.getToken(config);
            authService.getToken(config);

            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }

        @Test
        void tokenExpired_reFetched() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("expired-token"))
                    .thenReturn(responseWithBody("new-token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setTokenTtlSeconds(0); // immediate expiry

            authService.getToken(config);
            String second = authService.getToken(config);

            assertEquals("Bearer new-token", second);
            verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }
    }

    // ── g. Error handling ───────────────────────────────────────────────

    @Nested
    class ErrorHandling {

        @Test
        void restTemplateThrows_runtimeExceptionPropagated() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenThrow(new RestClientException("Connection refused"));

            FhirServerAuthConfig config = tokenEndpointConfig();

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> authService.getToken(config));
            assertTrue(ex.getMessage().contains("Connection refused"));
        }

        @Test
        void emptyResponseBody_runtimeExceptionThrown() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(null, new HttpHeaders(), HttpStatus.OK));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");

            assertThrows(RuntimeException.class, () -> authService.getToken(config));
        }
    }

    // ── h. Request construction — token-endpoint ────────────────────────

    @Nested
    class RequestConstruction {

        @SuppressWarnings("unchecked")
        @Test
        void contentTypeFormUrlEncoded_bodyHasUsernamePassword() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("token"));

            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            authService.getToken(config);

            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(config.getTokenUrl()), eq(HttpMethod.POST),
                    captor.capture(), eq(String.class));

            HttpEntity<?> request = captor.getValue();
            assertEquals(MediaType.APPLICATION_FORM_URLENCODED, request.getHeaders().getContentType());

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) request.getBody();
            assertNotNull(body);
            assertEquals("testuser", body.getFirst("username"));
            assertEquals("testpass", body.getFirst("password"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void clientHeaderSetWhenNonBlank_omittedWhenNull() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("token"));

            // With client header
            FhirServerAuthConfig config = tokenEndpointConfig();
            config.setTokenCookieName("NonExistent");
            config.setClient("web");
            authService.getToken(config);

            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
            assertEquals("web", captor.getValue().getHeaders().getFirst("client"));

            // Reset and test without client header
            reset(restTemplate);
            authService = new TokenEndpointAuthService(restTemplate, objectMapper);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody("token2"));

            FhirServerAuthConfig config2 = tokenEndpointConfig();
            config2.setTokenCookieName("NonExistent");
            config2.setClient(null);
            authService.getToken(config2);

            ArgumentCaptor<HttpEntity<?>> captor2 = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor2.capture(), eq(String.class));
            assertNull(captor2.getValue().getHeaders().getFirst("client"));
        }
    }

    // ── i. OAuth2 Client Credentials grant ──────────────────────────────

    @Nested
    class OAuth2ClientCredentials {

        @SuppressWarnings("unchecked")
        @Test
        void standardGrant_accessTokenExtracted() {
            String jsonResponse = "{\"access_token\": \"oauth2-token-abc\", \"token_type\": \"Bearer\"}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody(jsonResponse));

            String token = authService.getToken(oauth2Config());

            assertEquals("Bearer oauth2-token-abc", token);

            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) captor.getValue().getBody();
            assertNotNull(body);
            assertEquals("client_credentials", body.getFirst("grant_type"));
            assertEquals("my-client", body.getFirst("client_id"));
            assertEquals("my-secret", body.getFirst("client_secret"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void withScope_scopeIncluded() {
            FhirServerAuthConfig config = oauth2Config();
            config.setScope("openid profile");
            String jsonResponse = "{\"access_token\": \"scoped-token\"}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody(jsonResponse));

            authService.getToken(config);

            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) captor.getValue().getBody();
            assertEquals("openid profile", body.getFirst("scope"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void withoutScope_scopeOmitted() {
            FhirServerAuthConfig config = oauth2Config();
            config.setScope(null);
            String jsonResponse = "{\"access_token\": \"no-scope-token\"}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody(jsonResponse));

            authService.getToken(config);

            ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));

            MultiValueMap<String, String> body = (MultiValueMap<String, String>) captor.getValue().getBody();
            assertNull(body.getFirst("scope"));
        }

        @Test
        void expiresInInResponse_ttlSetFromResponse() {
            String jsonResponse = "{\"access_token\": \"expiry-token\", \"expires_in\": 1800}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody(jsonResponse))
                    .thenReturn(responseWithBody("{\"access_token\": \"new-token\", \"expires_in\": 1800}"));

            FhirServerAuthConfig config = oauth2Config();
            config.setTokenTtlSeconds(7200); // configured TTL is 2h, but response says 30min

            authService.getToken(config);
            // Token should be cached — second call within TTL
            authService.getToken(config);

            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }

        @Test
        void noExpiresIn_fallsBackToConfiguredTtl() {
            String jsonResponse = "{\"access_token\": \"no-expiry-token\"}";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(responseWithBody(jsonResponse));

            FhirServerAuthConfig config = oauth2Config();
            config.setTokenTtlSeconds(3600);

            String token = authService.getToken(config);
            assertEquals("Bearer no-expiry-token", token);

            // Second call within TTL — should be cached
            authService.getToken(config);
            verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        }

        @Test
        void tokenEndpointError_runtimeExceptionWithMessage() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenThrow(new RestClientException("401 Unauthorized"));

            FhirServerAuthConfig config = oauth2Config();

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> authService.getToken(config));
            assertTrue(ex.getMessage().contains("401 Unauthorized"));
        }
    }

    // ── Utility method tests ────────────────────────────────────────────

    @Test
    void ensureBearer_addsPrefix() {
        assertEquals("Bearer test", authService.ensureBearer("test"));
    }

    @Test
    void ensureBearer_preservesExisting() {
        assertEquals("Bearer existing", authService.ensureBearer("Bearer existing"));
    }

    @Test
    void extractFieldFromJson_validField() {
        String json = "{\"access_token\": \"my-token\", \"expires_in\": 300}";
        assertEquals("my-token", authService.extractFieldFromJson(json, "access_token"));
    }

    @Test
    void extractFieldFromJson_missingField() {
        String json = "{\"other\": \"value\"}";
        assertNull(authService.extractFieldFromJson(json, "access_token"));
    }
}
