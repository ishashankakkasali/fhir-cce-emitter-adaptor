package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Base64;

/**
 * Factory for creating authenticated HAPI FHIR {@link IGenericClient} instances.
 * <p>
 * Supports all FHIR server auth types: {@code none}, {@code basic}, {@code bearer},
 * {@code token-endpoint}, and {@code oauth2}. This is independent of subscription
 * logic and can be reused by any component that needs to communicate with a FHIR server.
 */
@Component
public class FhirClientFactory {

    private static final Logger log = LoggerFactory.getLogger(FhirClientFactory.class);

    private final FhirContext fhirContext;
    private final TokenEndpointAuthService tokenEndpointAuthService;

    public FhirClientFactory(FhirContext fhirContext,
                             TokenEndpointAuthService tokenEndpointAuthService) {
        this.fhirContext = fhirContext;
        this.tokenEndpointAuthService = tokenEndpointAuthService;
    }

    /**
     * Creates a HAPI FHIR {@link IGenericClient} authenticated per the server's auth config.
     *
     * @param serverConfig the FHIR server configuration including auth settings
     * @return an authenticated FHIR client ready for API calls
     */
    public IGenericClient createClient(FhirServerConfig serverConfig) {
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
}
