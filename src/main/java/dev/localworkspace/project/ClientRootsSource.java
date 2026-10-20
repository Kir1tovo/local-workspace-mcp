package dev.localworkspace.project;

public interface ClientRootsSource {
  boolean supported();

  RootsSnapshot read();
}
