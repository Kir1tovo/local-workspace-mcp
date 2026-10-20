package dev.localworkspace.config;

import com.fasterxml.jackson.databind.*;
import dev.localworkspace.common.ResponseEncoder;
import dev.localworkspace.markdown.MarkdownCodec;
import dev.localworkspace.project.*;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceConfiguration {
  @Bean
  Clock workspaceClock() {
    return Clock.systemUTC();
  }

  @Bean
  ObjectMapper workspaceJson() {
    return new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  MarkdownCodec markdownCodec() {
    return new MarkdownCodec();
  }

  @Bean
  ResponseEncoder responseEncoder(ObjectMapper json) {
    return new ResponseEncoder(json);
  }

  @Bean
  ProjectResolver projectResolver(WorkspaceProperties properties, Environment environment) {
    String raw = environment.getProperty("local-workspace.project-root");
    if (raw != null && raw.isBlank())
      throw new IllegalArgumentException(
          "local-workspace.project-root must not be blank; omit it for AUTO mode");
    return new ProjectResolver(properties.projectRoot());
  }

  @Bean
  WorkspaceRuntimeFactory workspaceRuntimeFactory(
      WorkspaceProperties properties,
      ObjectMapper json,
      Clock clock,
      MarkdownCodec codec,
      ResponseEncoder encoder) {
    return new WorkspaceRuntimeFactory(properties, json, clock, codec, encoder);
  }

  @Bean
  WorkspaceRuntimeProvider workspaceRuntimeProvider(
      ProjectResolver resolver, WorkspaceRuntimeFactory factory) {
    return new WorkspaceRuntimeProvider(resolver, factory);
  }
}
