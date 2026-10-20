package dev.localworkspace.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.*;
import java.util.List;
import reactor.core.publisher.Mono;

/** Serializes the SDK 0.18.3 synchronous outbound sink subscription, not tool execution. */
public final class SerializedStdioTransportProvider extends StdioServerTransportProvider {
  public SerializedStdioTransportProvider(McpJsonMapper mapper) {
    // Every tool re-reads Roots. Ignore the optional change notification so the SDK's
    // default auto-paginating notification handler cannot query in FIXED or close the session.
    super(mapper, new RootsNotificationInputStream(System.in, mapper), System.out);
  }

  @Override
  public void setSessionFactory(McpServerSession.Factory factory) {
    super.setSessionFactory(transport -> factory.create(new SerializedTransport(transport)));
  }

  private static final class SerializedTransport implements McpServerTransport {
    private final McpServerTransport delegate;
    private final Object sendLock = new Object();

    SerializedTransport(McpServerTransport delegate) {
      this.delegate = delegate;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      // The SDK enqueues inside Mono.defer with tryEmitNext. Locking Mono construction alone
      // would release too early; subscribe under the lock so concurrent emissions cannot be lost.
      // No response waiting, tool work, or filesystem access takes place under this lock.
      return Mono.create(
          sink -> {
            synchronized (sendLock) {
              var subscription =
                  delegate
                      .sendMessage(message)
                      .subscribe(ignored -> {}, sink::error, sink::success);
              sink.onCancel(subscription::dispose);
            }
          });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> type) {
      return delegate.unmarshalFrom(data, type);
    }

    @Override
    public List<String> protocolVersions() {
      return delegate.protocolVersions();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return delegate.closeGracefully();
    }
  }
}
