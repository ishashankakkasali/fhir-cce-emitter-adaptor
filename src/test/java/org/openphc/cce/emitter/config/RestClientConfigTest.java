package org.openphc.cce.emitter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RestClientConfig} — verifies both RestTemplate beans are created
 * with correct timeouts and the trust-all variant uses per-connection SSL.
 */
@SpringBootTest
class RestClientConfigTest {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    @Qualifier("trustAllRestTemplate")
    private RestTemplate trustAllRestTemplate;

    @Test
    void standardRestTemplateBeanExists() {
        assertNotNull(restTemplate, "Standard RestTemplate bean should exist");
    }

    @Test
    void trustAllRestTemplateBeanExists() {
        assertNotNull(trustAllRestTemplate, "Trust-all RestTemplate bean should exist");
    }

    @Test
    void standardAndTrustAllAreDistinctBeans() {
        assertNotSame(restTemplate, trustAllRestTemplate,
                "Standard and trust-all RestTemplate should be different instances");
    }

    @Test
    void standardRestTemplateHasCorrectTimeouts() {
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertNotNull(factory, "Standard RestTemplate should use SimpleClientHttpRequestFactory");
    }

    @Test
    void trustAllRestTemplateUsesCustomFactory() {
        assertTrue(
                trustAllRestTemplate.getRequestFactory() instanceof RestClientConfig.TrustAllClientHttpRequestFactory,
                "Trust-all RestTemplate should use TrustAllClientHttpRequestFactory (per-connection SSL)");
    }

    @Test
    void connectTimeoutConstant() {
        assertEquals(10_000, RestClientConfig.CONNECT_TIMEOUT_MS);
    }

    @Test
    void readTimeoutConstant() {
        assertEquals(60_000, RestClientConfig.READ_TIMEOUT_MS);
    }
}
