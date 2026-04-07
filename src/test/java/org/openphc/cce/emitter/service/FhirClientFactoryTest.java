package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FhirClientFactory}.
 * Verifies authenticated FHIR client creation for all 5 auth types.
 */
@ExtendWith(MockitoExtension.class)
class FhirClientFactoryTest {

    @Mock
    private FhirContext fhirContext;

    @Mock
    private TokenEndpointAuthService tokenEndpointAuthService;

    @Mock
    private IGenericClient client;

    private FhirClientFactory factory;

    @BeforeEach
    void setUp() {
        lenient().when(fhirContext.newRestfulGenericClient(anyString())).thenReturn(client);
        factory = new FhirClientFactory(fhirContext, tokenEndpointAuthService);
    }

    private FhirServerConfig buildServerConfig(String authType) {
        FhirServerConfig config = new FhirServerConfig();
        config.setName("test-fhir");
        config.setUrl("http://fhir-server:8090/fhir");
        FhirServerAuthConfig auth = new FhirServerAuthConfig();
        auth.setType(authType);
        config.setAuth(auth);
        return config;
    }

    @Nested
    @DisplayName("Auth Client Creation")
    class AuthClientCreation {

        @Test
        void authNone_noInterceptor() {
            FhirServerConfig config = buildServerConfig("none");

            IGenericClient result = factory.createClient(config);

            assertNotNull(result);
            verify(client, never()).registerInterceptor(any());
        }

        @Test
        void authBasic_basicAuthHeader() {
            FhirServerConfig config = buildServerConfig("basic");
            config.getAuth().setUsername("user");
            config.getAuth().setPassword("pass");

            factory.createClient(config);

            ArgumentCaptor<IClientInterceptor> captor = ArgumentCaptor.forClass(IClientInterceptor.class);
            verify(client).registerInterceptor(captor.capture());

            IHttpRequest mockRequest = mock(IHttpRequest.class);
            captor.getValue().interceptRequest(mockRequest);

            String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("user:pass".getBytes());
            verify(mockRequest).addHeader("Authorization", expectedAuth);
        }

        @Test
        void authBearer_bearerAuthHeader() {
            FhirServerConfig config = buildServerConfig("bearer");
            config.getAuth().setToken("static-bearer-token");

            factory.createClient(config);

            ArgumentCaptor<IClientInterceptor> captor = ArgumentCaptor.forClass(IClientInterceptor.class);
            verify(client).registerInterceptor(captor.capture());

            IHttpRequest mockRequest = mock(IHttpRequest.class);
            captor.getValue().interceptRequest(mockRequest);
            verify(mockRequest).addHeader("Authorization", "Bearer static-bearer-token");
        }

        @Test
        void authTokenEndpoint_getTokenCalled_interceptorRegistered() {
            FhirServerConfig config = buildServerConfig("token-endpoint");
            config.getAuth().setTokenUrl("http://auth:8089/authenticate");
            config.getAuth().setClient("web");
            when(tokenEndpointAuthService.getToken(config.getAuth())).thenReturn("Bearer fetched-token");

            factory.createClient(config);

            verify(tokenEndpointAuthService).getToken(config.getAuth());
            // 2 interceptors: Authorization + client header
            verify(client, times(2)).registerInterceptor(any(IClientInterceptor.class));
        }

        @Test
        void authOauth2_getTokenCalled_bearerInterceptor() {
            FhirServerConfig config = buildServerConfig("oauth2");
            config.getAuth().setTokenUrl("http://keycloak/token");
            when(tokenEndpointAuthService.getToken(config.getAuth())).thenReturn("Bearer oauth2-token");

            factory.createClient(config);

            verify(tokenEndpointAuthService).getToken(config.getAuth());
            verify(client, times(1)).registerInterceptor(any(IClientInterceptor.class));
        }

        @Test
        void authUnknown_logWarning_noInterceptor() {
            FhirServerConfig config = buildServerConfig("ldap");

            IGenericClient result = factory.createClient(config);

            assertNotNull(result);
            verify(client, never()).registerInterceptor(any());
        }
    }
}
