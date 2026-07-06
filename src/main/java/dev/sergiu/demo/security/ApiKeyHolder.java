package dev.sergiu.demo.security;

/**
 * Holds the resolved MCP API key. The value comes either from AWS Secrets
 * Manager (in production) or from a local property (dev). See
 * {@link SecretsManagerConfig}.
 */
public record ApiKeyHolder(String value) {}
