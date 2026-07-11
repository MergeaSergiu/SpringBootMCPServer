package dev.sergiu.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Resolves the MCP API key at startup.
 *
 * <p>If {@code mcp.security.secret-name} is set (e.g. on EC2 via the
 * {@code MCP_SECRET_NAME} env var) the value is fetched from AWS Secrets
 * Manager, using the instance's IAM role — no static AWS credentials required.
 * Otherwise it falls back to {@code mcp.security.api-key} so local dev keeps
 * working with no AWS access.
 */
@Configuration
public class SecretsManagerConfig {

    @Value("${mcp.security.secret-name:}")
    private String secretName;

    @Value("${mcp.security.api-key:dev-local-key}")
    private String fallbackApiKey;

    // Optional. If blank, the SDK resolves the region from the default chain
    // (AWS_REGION env var, ~/.aws/config, or EC2 instance metadata).
    @Value("${mcp.security.secret-region:}")
    private String secretRegion;

    @Bean
    public ApiKeyHolder apiKeyHolder() {
        if (secretName == null || secretName.isBlank()) {
            return new ApiKeyHolder(fallbackApiKey);
        }
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder();
        if (secretRegion != null && !secretRegion.isBlank()) {
            builder.region(Region.of(secretRegion));
        }
        try (SecretsManagerClient client = builder.build()) {
            String secret = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretName).build()
            ).secretString();
            return new ApiKeyHolder(secret);
        }
    }
}
