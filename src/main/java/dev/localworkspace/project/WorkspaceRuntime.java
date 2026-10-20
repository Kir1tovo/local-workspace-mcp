package dev.localworkspace.project;

import dev.localworkspace.context.ContextService;
import dev.localworkspace.history.HistoryService;
import dev.localworkspace.knowledge.KnowledgeService;
import dev.localworkspace.task.TaskService;
import dev.localworkspace.workspace.WorkspaceService;

public record WorkspaceRuntime(
    ProjectResolution resolution,
    WorkspaceService workspace,
    TaskService tasks,
    ContextService context,
    HistoryService history,
    KnowledgeService knowledge) {}
