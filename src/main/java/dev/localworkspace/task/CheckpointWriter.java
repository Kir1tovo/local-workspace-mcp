package dev.localworkspace.task;

import dev.localworkspace.task.TaskModels.TaskSnapshot;

public interface CheckpointWriter {
  String save(TaskSnapshot snapshot, String reason);
}
