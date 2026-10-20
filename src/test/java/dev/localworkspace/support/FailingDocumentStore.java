package dev.localworkspace.support;

import dev.localworkspace.common.*;
import dev.localworkspace.storage.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

public class FailingDocumentStore implements DocumentStore {
  private final DocumentStore delegate;
  private final Predicate<Path> condition;
  private boolean armed = true;

  public FailingDocumentStore(DocumentStore delegate, Predicate<Path> condition) {
    this.delegate = delegate;
    this.condition = condition;
  }

  private void before(Path p) {
    if (armed && condition.test(p)) {
      armed = false;
      throw new WorkspaceException(ErrorCode.IO_ERROR, "Injected write failure");
    }
  }

  public byte[] read(Path p) {
    return delegate.read(p);
  }

  public boolean exists(Path p) {
    return delegate.exists(p);
  }

  public List<Path> list(Path p) {
    return delegate.list(p);
  }

  public void directories(Path p) {
    delegate.directories(p);
  }

  public void create(Path p, byte[] b) {
    before(p);
    delegate.create(p, b);
  }

  public void replace(Path p, byte[] b, String r) {
    before(p);
    delegate.replace(p, b, r);
  }

  public Path staging(Path p, String k) {
    return delegate.staging(p, k);
  }

  public void publishDirectory(Path s, Path t) {
    before(t);
    delegate.publishDirectory(s, t);
  }

  public void cleanupOwnedStaging(Path p) {
    delegate.cleanupOwnedStaging(p);
  }
}
