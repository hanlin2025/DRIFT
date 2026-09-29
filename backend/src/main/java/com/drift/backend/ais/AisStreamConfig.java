package com.drift.backend.ais;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AisStreamProperties.class)
public class AisStreamConfig {
}
