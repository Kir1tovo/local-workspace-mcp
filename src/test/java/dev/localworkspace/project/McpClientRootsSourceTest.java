package dev.localworkspace.project;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.localworkspace.common.ErrorCode;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.context.McpSyncRequestContext;

class McpClientRootsSourceTest {
  @Test
  void readsOnePageWithoutSdkAutomaticPagination() {
    var context = mock(McpSyncRequestContext.class);
    var exchange = mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
    when(context.exchange()).thenReturn(exchange);
    when(exchange.listRoots((String) null))
        .thenReturn(
            new McpSchema.ListRootsResult(
                List.of(new McpSchema.Root("file:///project", "project")), "more"));
    assertThat(new McpClientRootsSource(context).read())
        .isEqualTo(new RootsSnapshot(List.of("file:///project"), "more"));
    verify(context, never()).roots();
    verify(exchange).listRoots((String) null);
  }

  @Test
  void convertsCapabilityAndRoots() {
    var context = mock(McpSyncRequestContext.class);
    var exchange = mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
    when(context.exchange()).thenReturn(exchange);
    when(context.rootsEnabled()).thenReturn(true);
    when(exchange.listRoots((String) null))
        .thenReturn(
            new McpSchema.ListRootsResult(
                List.of(new McpSchema.Root("file:///project", "project")), "next"));
    var source = new McpClientRootsSource(context);
    assertThat(source.supported()).isTrue();
    assertThat(source.read()).isEqualTo(new RootsSnapshot(List.of("file:///project"), "next"));
    when(exchange.listRoots((String) null)).thenReturn(new McpSchema.ListRootsResult(List.of()));
    assertThat(source.read().uris()).isEmpty();
  }

  @Test
  void handlesNoCapabilityAndMissingContext() {
    var context = mock(McpSyncRequestContext.class);
    assertThat(new McpClientRootsSource(context).supported()).isFalse();
    verify(context, never()).roots();
    assertThat(new McpClientRootsSource(null).supported()).isFalse();
  }

  @Test
  void mapsFailureAndInvalidStructures() {
    var context = mock(McpSyncRequestContext.class);
    var exchange = mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
    when(context.exchange()).thenReturn(exchange);
    var source = new McpClientRootsSource(context);
    when(exchange.listRoots((String) null)).thenThrow(new IllegalStateException("private failure"));
    ProjectResolverTest.error(source::read, ErrorCode.ROOTS_UNAVAILABLE);
    reset(exchange);
    ProjectResolverTest.error(source::read, ErrorCode.ROOTS_UNAVAILABLE);
    when(exchange.listRoots((String) null)).thenReturn(new McpSchema.ListRootsResult(null));
    ProjectResolverTest.error(source::read, ErrorCode.ROOTS_UNAVAILABLE);
  }
}
