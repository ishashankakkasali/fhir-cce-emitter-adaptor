package org.openphc.cce.emitter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * REST client configuration — provides standard and trust-all {@link RestTemplate} beans
 * for outbound HTTP communication to OpenHIM and FHIR servers.
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 60_000;

    /**
     * Standard {@link RestTemplate} with configured connect and read timeouts.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /**
     * Trust-all {@link RestTemplate} that accepts any SSL certificate.
     * <p>
     * Uses a per-connection SSL context — does NOT set
     * {@link HttpsURLConnection#setDefaultSSLSocketFactory} globally.
     * Falls back to a standard {@link RestTemplate} if SSL context creation fails.
     */
    @Bean("trustAllRestTemplate")
    @Qualifier("trustAllRestTemplate")
    public RestTemplate trustAllRestTemplate() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllTrustManager()}, new java.security.SecureRandom());

            SimpleClientHttpRequestFactory factory = new TrustAllClientHttpRequestFactory(sslContext);
            factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
            factory.setReadTimeout(READ_TIMEOUT_MS);

            log.info("Trust-all RestTemplate created — per-connection SSL context (not global)");
            return new RestTemplate(factory);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("Failed to create trust-all SSL context — falling back to standard RestTemplate: {}",
                    e.getMessage());
            return restTemplate();
        }
    }

    /**
     * Trust manager that accepts all certificates without validation.
     */
    private static X509TrustManager trustAllTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Trust all clients
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // Trust all servers
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Custom {@link SimpleClientHttpRequestFactory} that applies the trust-all SSL context
     * per-connection rather than globally via {@link HttpsURLConnection#setDefaultSSLSocketFactory}.
     */
    static class TrustAllClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

        private final SSLContext sslContext;

        TrustAllClientHttpRequestFactory(SSLContext sslContext) {
            this.sslContext = sslContext;
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            if (connection instanceof HttpsURLConnection httpsConnection) {
                httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                httpsConnection.setHostnameVerifier((hostname, session) -> true);
            }
            super.prepareConnection(connection, httpMethod);
        }
    }
}
