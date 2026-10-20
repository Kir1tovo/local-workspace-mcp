package dev.localworkspace.config;

import static org.assertj.core.api.Assertions.*;

import dev.localworkspace.project.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkspaceBindingTest {
  @TempDir Path temp;
  final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(WorkspaceConfiguration.class);

  @Test
  void absentRootUsesAuto() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(WorkspaceRuntimeProvider.class);
          assertThat(context.getBean(WorkspaceProperties.class).projectRoot()).isNull();
          assertThat(Files.exists(temp.resolve("LocalWorkspace"))).isFalse();
        });
  }

  @Test
  void explicitEmptyOrWhitespaceRootFails() {
    for (String blank : new String[] {"", "   "})
      runner
          .withPropertyValues("local-workspace.project-root=" + blank)
          .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void invalidFixedRootFails() {
    runner
        .withPropertyValues("local-workspace.project-root=" + temp.resolve("missing"))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void partialLimitsKeepDefaults() {
    runner
        .withPropertyValues("local-workspace.limits.document-chars=9000")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var limits = context.getBean(WorkspaceProperties.class).limits();
              assertThat(limits.documentChars()).isEqualTo(9000);
              assertThat(limits.guideChars()).isEqualTo(8192);
              assertThat(limits.knowledgeChars()).isEqualTo(16384);
              assertThat(limits.contextChars()).isEqualTo(24000);
            });
  }
}
