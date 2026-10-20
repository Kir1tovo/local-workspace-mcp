package dev.localworkspace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.localworkspace.mcp.SerializedStdioTransportProvider;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class StdioTransportConfiguration {
  @Bean
  McpServerTransportProviderBase serializedStdioTransport(ObjectMapper json) {
    return new SerializedStdioTransportProvider(new JacksonMcpJsonMapper(json));
  }
}
