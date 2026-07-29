package com.docstruct.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docstruct.upload")
public record UploadProperties(long maxFileSizeBytes) {
}
