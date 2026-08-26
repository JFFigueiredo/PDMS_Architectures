package ipl.pdms.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.security.PublicKey;
import java.util.Base64;

/**
 * Test configuration that provides a test public key resource.
 */
@TestConfiguration
@Profile({"test", "integration-test"})
public class TestSecurityConfiguration {

    @Bean(name = "testPublicKeyResource")
    @Primary
    public Resource testPublicKeyResource() {
        PublicKey testPublicKey = TestJwtTokenGenerator.getTestPublicKey();
        byte[] keyBytes = testPublicKey.getEncoded();
        String keyPem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyBytes) +
                "\n-----END PUBLIC KEY-----";
        ByteArrayResource resource = new ByteArrayResource(keyPem.getBytes(), "test-public-key");
        return resource;
    }
}

