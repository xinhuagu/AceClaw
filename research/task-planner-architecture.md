# Task Planner Architecture

## 1. Executive Summary

The Task Planner is a new component within `chelava-core` that enables Chelava to **autonomously decompose complex user requests into structured, dependency-aware task graphs** and orchestrate their execution across the Agent Loop, Subagents, and Agent Teams.

Unlike the current ReAct loop (which reasons one step at a time), the Task Planner provides **upfront strategic planning** — analyzing a complex goal, breaking it into sub-tasks with dependency ordering, resource estimates, and execution strategies — before any tool is invoked.

### Why This Matters

| Current (ReAct Only) | With Task Planner |
|----------------------|-------------------|
| Step-by-step reasoning, no global plan | Full task graph before execution begins |
| Cannot parallelize independent sub-goals | Identifies parallelizable branches automatically |
| No progress tracking for complex tasks | Structured progress with completion estimates |
| Re-plans reactively on failure | Proactive risk assessment and fallback paths |
| Agent Teams require manual task creation | Auto-generates TaskStore entries for teams |

### Design Philosophy

- **LLM-driven decomposition, type-safe execution**: The LLM creates the plan; Java enforces it
- **Sealed interface exhaustiveness**: All plan states, task states, and strategy types are compile-time verified
- **Virtual thread parallelism**: Independent branches execute on separate virtual threads
- **Memory-informed planning**: Auto-memory patterns and strategies feed into plan generation
- **Incremental replanning**: Plans adapt mid-execution without discarding completed work

---

## 2. Core Abstractions

### 2.1 Task Plan (DAG)

The central data structure is a **Directed Acyclic Graph (DAG)** of tasks:

```java
/**
 * A complete execution plan — a DAG of tasks with dependency edges.
 * Immutable once created; replanning produces a new TaskPlan.
 */
public record TaskPlan(
    String planId,
    Instant createdAt,
    String originalGoal,
    List<PlannedTask> tasks,
    List<TaskDependency> dependencies,
    PlanMetadata metadata,
    PlanStatus status
) {

    /**
     * Returns tasks with no unsatisfied dependencies (ready to execute).
     */
    public List<PlannedTask> readyTasks() {
        Set<String> completedIds = tasks.stream()
            .filter(t -> t.status() == TaskStatus.COMPLETED)
            .map(PlannedTask::taskId)
            .collect(Collectors.toSet());

        return tasks.stream()
            .filter(t -> t.status() == TaskStatus.PENDING)
            .filter(t -> dependencies.stream()
                .filter(d -> d.dependentId().equals(t.taskId()))
                .allMatch(d -> completedIds.contains(d.dependencyId())))
            .toList();
    }

    /**
     * Returns the critical path — the longest chain of dependent tasks.
     */
    public List<PlannedTask> criticalPath() { /* topological sort + longest path */ }
}
```

### 2.2 Planned Task

```java
/**
 * A single unit of work within a TaskPlan.
 */
public record PlannedTask(
    String taskId,
    String subject,
    String description,
    TaskCategory category,
    ExecutionStrategy strategy,
    TaskStatus status,
    TaskPriority priority,
    Duration estimatedDuration,
    List<String> requiredTools,
    List<String> affectedFiles,
    RiskLevel risk,
    String fallbackApproach,
    PlannedTaskResult result
) {}

/**
 * Task categories — sealed for exhaustive handling.
 */
public sealed interface TaskCategory permits
    TaskCategory.Research,
    TaskCategory.FileModification,
    TaskCategory.CodeGeneration,
    TaskCategory.Testing,
    TaskCategory.Configuration,
    TaskCategory.Review,
    TaskCategory.Deployment {

    record Research(String scope) implements TaskCategory {}
    record FileModification(List<String> targetFiles) implements TaskCategory {}
    record CodeGeneration(String language, String pattern) implements TaskCategory {}
    record Testing(TestType testType) implements TaskCategory {}
    record Configuration(String target) implements TaskCategory {}
    record Review(ReviewScope scope) implements TaskCategory {}
    record Deployment(String environment) implements TaskCategory {}
}

/**
 * How a task should be executed — sealed for compile-time safety.
 */
public sealed interface ExecutionStrategy permits
    ExecutionStrategy.AgentLoop,
    ExecutionStrategy.Subagent,
    ExecutionStrategy.AgentTeam,
    ExecutionStrategy.UserAction {

    /** Execute within the main agent's ReAct loop. */
    record AgentLoop(int estimatedTurns) implements ExecutionStrategy {}

    /** Delegate to a subagent with isolated context. */
    record Subagent(String agentType, String model) implements ExecutionStrategy {}

    /** Assign to an agent team member. */
    record AgentTeam(String role, boolean planApprovalRequired) implements ExecutionStrategy {}

    /** Requires user action (manual approval, external setup). */
    record UserAction(String instruction) implements ExecutionStrategy {}
}

/**
 * Task lifecycle — mirrors existing TaskStore statuses.
 */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED,
    BLOCKED
}

public enum TaskPriority { CRITICAL, HIGH, MEDIUM, LOW }

public enum RiskLevel { NONE, LOW, MEDIUM, HIGH }

public enum TestType { UNIT, INTEGRATION, E2E, MANUAL }

public enum ReviewScope { CODE, SECURITY, PERFORMANCE }
```

### 2.3 Task Dependency

```java
/**
 * An edge in the task DAG — "dependent" cannot start until "dependency" completes.
 */
public record TaskDependency(
    String dependencyId,    // must complete first
    String dependentId,     // waits for dependency
    DependencyType type
) {}

public enum DependencyType {
    BLOCKS,           // Hard dependency: B cannot start until A finishes
    INFORMS,          // Soft dependency: B benefits from A's output but can start without it
    VALIDATES         // C validates A's output (testing relationship)
}
```

### 2.4 Plan Status

```java
/**
 * Overall plan lifecycle — sealed for exhaustive handling.
 */
public sealed interface PlanStatus permits
    PlanStatus.Draft,
    PlanStatus.AwaitingApproval,
    PlanStatus.Approved,
    PlanStatus.Executing,
    PlanStatus.Replanning,
    PlanStatus.Completed,
    PlanStatus.Failed,
    PlanStatus.Cancelled {

    record Draft(Instant createdAt) implements PlanStatus {}
    record AwaitingApproval(Instant submittedAt) implements PlanStatus {}
    record Approved(Instant approvedAt, String approver) implements PlanStatus {}
    record Executing(int completedTasks, int totalTasks) implements PlanStatus {}
    record Replanning(String reason, String failedTaskId) implements PlanStatus {}
    record Completed(Instant completedAt, Duration totalDuration) implements PlanStatus {}
    record Failed(String reason, List<String> failedTaskIds) implements PlanStatus {}
    record Cancelled(Instant cancelledAt, String reason) implements PlanStatus {}
}
```

---

## 3. Task Planner Engine

### 3.1 Planner Interface

```java
/**
 * The Task Planner — decomposes a user goal into an executable task graph.
 * Lives in chelava-core alongside AgentLoop.
 */
public interface TaskPlanner {

    /**
     * Phase 1: Analyze the user's goal and generate a task plan.
     * Uses LLM reasoning + memory context to produce the DAG.
     *
     * @param goal the user's original request
     * @param context current conversation and codebase context
     * @param memory relevant auto-memory entries (patterns, strategies)
     * @return a structured TaskPlan ready for review or execution
     */
    TaskPlan plan(String goal, PlanningContext context);

    /**
     * Phase 2: Validate the plan for feasibility.
     * Checks: circular dependencies, missing tools, permission issues,
     * file conflicts between parallel tasks, estimated token budget.
     */
    PlanValidation validate(TaskPlan plan);

    /**
     * Phase 3: Replan after a task failure or new information.
     * Preserves completed work, adjusts remaining tasks.
     *
     * @param currentPlan the plan with some tasks completed/failed
     * @param failureContext what went wrong and why
     * @return an updated plan with adjusted remaining tasks
     */
    TaskPlan replan(TaskPlan currentPlan, ReplanContext failureContext);
}

/**
 * Context provided to the planner for informed decision-making.
 */
public record PlanningContext(
    ConversationContext conversation,
    MemoryInjections memory,
    CodebaseSnapshot codebase,       // file tree, languages, frameworks
    List<ToolDefinition> availableTools,
    PermissionPolicy permissions,
    AgentCapabilities capabilities   // available subagents, team members
) {}

/**
 * Validation result with actionable feedback.
 */
public record PlanValidation(
    boolean isValid,
    List<PlanIssue> issues,
    List<PlanSuggestion> suggestions,
    TokenEstimate totalTokenBudget
) {}

public sealed interface PlanIssue permits
    PlanIssue.CircularDependency,
    PlanIssue.MissingTool,
    PlanIssue.PermissionConflict,
    PlanIssue.FileConflict,
    PlanIssue.TokenBudgetExceeded {

    record CircularDependency(List<String> cycle) implements PlanIssue {}
    record MissingTool(String taskId, String toolName) implements PlanIssue {}
    record PermissionConflict(String taskId, ToolPermission required) implements PlanIssue {}
    record FileConflict(String taskA, String taskB, String filePath) implements PlanIssue {}
    record TokenBudgetExceeded(int estimated, int available) implements PlanIssue {}
}
```

### 3.2 LLM-Driven Decomposition

The planner uses a **structured output prompt** to have the LLM generate the task graph:

```java
public class LLMTaskPlanner implements TaskPlanner {

    private final LLMClient llm;
    private final AutoMemory autoMemory;
    private final CodebaseAnalyzer codebaseAnalyzer;

    @Override
    public TaskPlan plan(String goal, PlanningContext context) {
        // 1. Gather relevant memory (patterns, strategies, past mistakes)
        List<MemoryEntry> relevantMemory;
        CodebaseSnapshot snapshot;
        try (var scope = StructuredTaskScope.open()) {
            var memoryTask = scope.fork(() ->
                autoMemory.retrieve(goal, 10));
            var codebaseTask = scope.fork(() ->
                codebaseAnalyzer.analyze(context.conversation()));
            scope.join();
            relevantMemory = memoryTask.get();
            snapshot = codebaseTask.get();
        }

        // 2. Build the planning prompt
        String planningPrompt = PlanningPromptBuilder.build(
            goal, snapshot, relevantMemory, context.availableTools());

        // 3. Call LLM with structured output (JSON schema for TaskPlan)
        LLMRequest request = new LLMRequest(
            context.capabilities().plannerModel(),
            List.of(new UserMessage(UUID.randomUUID().toString(),
                Instant.now(), planningPrompt)),
            List.of(), // no tools during planning phase
            PlanningPromptBuilder.systemPrompt(),
            8192,
            0.3, // lower temperature for structured planning
            new ThinkingConfig(true, 4096)
        );

        AssistantMessage response = llm.complete(request);

        // 4. Parse structured output into TaskPlan
        TaskPlan plan = TaskPlanParser.parse(response, goal);

        // 5. Validate and return
        PlanValidation validation = validate(plan);
        if (!validation.isValid()) {
            // Auto-fix simple issues (reorder dependencies, etc.)
            plan = autoFix(plan, validation);
        }

        return plan;
    }
}
```

### 3.3 Memory-Informed Planning

Auto-memory entries directly influence plan generation:

| Memory Category | Planning Impact |
|----------------|-----------------|
| `MISTAKE` | Avoid known-failing approaches; add validation tasks |
| `PATTERN` | Reuse successful decomposition patterns for similar goals |
| `PREFERENCE` | Respect user's preferred tools, frameworks, code style |
| `CODEBASE_INSIGHT` | Understand module boundaries, dependencies, conventions |
| `STRATEGY` | Apply proven strategies (e.g., "for refactoring, test first") |

```java
// Example: Memory informs the planning prompt
public class PlanningPromptBuilder {
    public static String build(String goal, CodebaseSnapshot codebase,
                                List<MemoryEntry> memories,
                                List<ToolDefinition> tools) {
        var sb = new StringBuilder();
        sb.append("## Goal\n").append(goal).append("\n\n");

        sb.append("## Codebase Context\n");
        sb.append("Languages: ").append(codebase.languages()).append("\n");
        sb.append("Frameworks: ").append(codebase.frameworks()).append("\n");
        sb.append("Key modules: ").append(codebase.modules()).append("\n\n");

        // Inject relevant memories as planning constraints
        var mistakes = memories.stream()
            .filter(m -> m.category() == MemoryCategory.MISTAKE).toList();
        if (!mistakes.isEmpty()) {
            sb.append("## Known Pitfalls (AVOID these)\n");
            mistakes.forEach(m -> sb.append("- ").append(m.content()).append("\n"));
        }

        var strategies = memories.stream()
            .filter(m -> m.category() == MemoryCategory.STRATEGY).toList();
        if (!strategies.isEmpty()) {
            sb.append("## Proven Strategies (PREFER these)\n");
            strategies.forEach(m -> sb.append("- ").append(m.content()).append("\n"));
        }

        sb.append("\n## Available Execution Strategies\n");
        sb.append("- AgentLoop: For tasks needing <5 tool calls\n");
        sb.append("- Subagent(Explore): For read-only research\n");
        sb.append("- Subagent(General): For complex multi-step subtasks\n");
        sb.append("- AgentTeam: For truly parallel independent workstreams\n");
        sb.append("- UserAction: For tasks requiring human judgment\n");

        sb.append("\n## Output Format\n");
        sb.append("Return a JSON object matching the TaskPlan schema...\n");

        return sb.toString();
    }
}
```

---

## 4. Plan Executor

### 4.1 Executor Interface

```java
/**
 * Executes a validated TaskPlan, managing the lifecycle of all tasks.
 * Uses virtual threads for parallel branch execution.
 */
public interface PlanExecutor {

    /**
     * Execute the plan. Emits events via EventBus for progress tracking.
     * Automatically parallelizes independent branches.
     */
    PlanExecutionResult execute(TaskPlan plan, ExecutionConfig config);

    /**
     * Pause execution (e.g., for user review at a checkpoint).
     */
    void pause();

    /**
     * Resume execution after pause.
     */
    void resume();

    /**
     * Cancel all remaining tasks.
     */
    void cancel(String reason);
}

public record ExecutionConfig(
    boolean requireApprovalBeforeExecution,  // Plan Mode integration
    boolean autoReplanOnFailure,             // Automatic replanning
    int maxReplanAttempts,                   // Prevent infinite replan loops
    Set<TaskCategory> checkpointBefore,      // Pause before risky categories
    ProgressReporter progressReporter        // UI progress updates
) {}
```

### 4.2 Virtual Thread Parallel Execution

```java
public class VirtualThreadPlanExecutor implements PlanExecutor {

    private final AgentLoop agentLoop;
    private final SubAgentOrchestrator subAgentOrchestrator;
    private final TaskPlanner planner;
    private final EventBus eventBus;

    @Override
    public PlanExecutionResult execute(TaskPlan plan, ExecutionConfig config) {
        var mutablePlan = new AtomicReference<>(plan);
        var results = new ConcurrentHashMap<String, PlannedTaskResult>();

        while (hasRemainingTasks(mutablePlan.get())) {
            List<PlannedTask> readyTasks = mutablePlan.get().readyTasks();

            if (readyTasks.isEmpty()) {
                // All remaining tasks are blocked — deadlock or failure
                break;
            }

            // Execute all ready tasks in parallel on virtual threads
            try (var scope = StructuredTaskScope.open()) {
                List<StructuredTaskScope.Subtask<TaskExecutionResult>> subtasks =
                    readyTasks.stream()
                        .map(task -> scope.fork(() -> executeTask(task, config)))
                        .toList();

                scope.join();

                // Process results
                for (int i = 0; i < readyTasks.size(); i++) {
                    PlannedTask task = readyTasks.get(i);
                    TaskExecutionResult result = subtasks.get(i).get();

                    results.put(task.taskId(), result.result());
                    eventBus.publish(new TaskCompletedEvent(task.taskId(), result));

                    if (result.failed() && config.autoReplanOnFailure()) {
                        // Trigger replanning for remaining tasks
                        TaskPlan replanned = planner.replan(
                            mutablePlan.get(),
                            new ReplanContext(task.taskId(), result.errorMessage())
                        );
                        mutablePlan.set(replanned);
                        eventBus.publish(new PlanReplannedEvent(replanned));
                        break; // Re-evaluate ready tasks after replan
                    }
                }
            }
        }

        return new PlanExecutionResult(mutablePlan.get(), results);
    }

    /**
     * Execute a single task using its designated strategy.
     * Pattern matching on sealed ExecutionStrategy.
     */
    private TaskExecutionResult executeTask(PlannedTask task, ExecutionConfig config) {
        eventBus.publish(new TaskStartedEvent(task.taskId()));

        return switch (task.strategy()) {
            case ExecutionStrategy.AgentLoop s ->
                executeInAgentLoop(task, s);

            case ExecutionStrategy.Subagent s ->
                executeAsSubagent(task, s);

            case ExecutionStrategy.AgentTeam s ->
                delegateToTeam(task, s);

            case ExecutionStrategy.UserAction s ->
                requestUserAction(task, s);
        };
    }

    private TaskExecutionResult executeInAgentLoop(
            PlannedTask task, ExecutionStrategy.AgentLoop strategy) {
        // Convert task to a focused prompt and run through the main agent loop
        String taskPrompt = TaskPromptBuilder.build(task);
        Turn turn = agentLoop.runTurn(
            conversationContext, new UserMessage(/*...*/ taskPrompt));
        return TaskExecutionResult.fromTurn(turn);
    }

    private TaskExecutionResult executeAsSubagent(
            PlannedTask task, ExecutionStrategy.Subagent strategy) {
        AgentConfig subConfig = AgentConfig.builder()
            .model(strategy.model())
            .systemPrompt(TaskPromptBuilder.buildSystemPrompt(task))
            .tools(filterTools(task.requiredTools()))
            .build();
        SubAgentResult result = subAgentOrchestrator.delegate(
            subConfig, task.description());
        return TaskExecutionResult.fromSubAgent(result);
    }
}
```

---

## 5. Integration Points

### 5.1 Integration with Agent Loop

The Task Planner is **invoked by the Agent Loop** when it detects a complex task:

```java
public class EnhancedAgentLoop implements AgentLoop {

    private final TaskPlanner planner;
    private final PlanExecutor executor;
    private final ComplexityEstimator complexityEstimator;

    @Override
    public Turn runTurn(ConversationContext context, UserMessage input) {
        // Estimate task complexity
        ComplexityScore score = complexityEstimator.estimate(input.content(), context);

        if (score.shouldPlan()) {
            // Complex task -> generate and execute a plan
            TaskPlan plan = planner.plan(input.content(), buildPlanningContext(context));
            PlanValidation validation = planner.validate(plan);

            if (validation.isValid()) {
                // Present plan to user for approval (if in normal mode)
                // or auto-execute (if in autonomous mode)
                return executePlan(plan, context);
            }
        }

        // Simple task -> standard ReAct loop
        return standardReActTurn(context, input);
    }
}
```

### 5.2 Complexity Estimator

```java
/**
 * Determines whether a task warrants upfront planning.
 * Uses heuristics + optional LLM classification.
 */
public class ComplexityEstimator {

    public ComplexityScore estimate(String goal, ConversationContext context) {
        int score = 0;

        // Heuristic signals
        if (containsMultipleActions(goal))      score += 3;
        if (mentionsMultipleFiles(goal))         score += 2;
        if (requiresResearchFirst(goal))         score += 2;
        if (involvesTestingOrDeployment(goal))   score += 2;
        if (mentionsRefactoring(goal))           score += 3;
        if (isAmbiguous(goal))                   score += 1;

        // Memory-informed: has this type of task needed planning before?
        List<MemoryEntry> strategies = autoMemory.retrieve(
            "planning strategy for: " + goal, 3);
        if (!strategies.isEmpty())               score += 2;

        return new ComplexityScore(score, score >= PLANNING_THRESHOLD);
    }

    private static final int PLANNING_THRESHOLD = 5;
}
```

### 5.3 Integration with Agent Teams

When the plan includes `ExecutionStrategy.AgentTeam` tasks, the planner auto-generates entries in the shared `TaskStore`:

```java
/**
 * Bridge between TaskPlan and Agent Teams' TaskStore.
 */
public class PlanTeamBridge {

    private final TeamManager teamManager;
    private final TaskStore taskStore;

    /**
     * Materialize plan tasks into the team's shared TaskStore.
     * Maps PlannedTask dependencies to TaskStore blockedBy relationships.
     */
    public void materialize(TaskPlan plan, String teamName) {
        // Create team if not exists
        if (!teamManager.teamExists(teamName)) {
            teamManager.createTeam(teamName, plan.originalGoal());
        }

        // Convert PlannedTasks to TaskStore entries
        Map<String, String> planIdToStoreId = new HashMap<>();
        for (PlannedTask task : plan.tasks()) {
            if (task.strategy() instanceof ExecutionStrategy.AgentTeam teamStrategy) {
                String storeId = taskStore.create(new TaskEntry(
                    task.subject(),
                    task.description(),
                    teamStrategy.role()  // assign to role
                ));
                planIdToStoreId.put(task.taskId(), storeId);
            }
        }

        // Set up dependency relationships
        for (TaskDependency dep : plan.dependencies()) {
            String storeDependent = planIdToStoreId.get(dep.dependentId());
            String storeDependency = planIdToStoreId.get(dep.dependencyId());
            if (storeDependent != null && storeDependency != null) {
                taskStore.addBlockedBy(storeDependent, storeDependency);
            }
        }
    }
}
```

### 5.4 Integration with Memory System

After plan execution, results feed back into auto-memory:

```java
/**
 * Post-execution learning — records planning outcomes into auto-memory.
 */
public class PlanLearner {

    private final AutoMemory autoMemory;

    public void learn(TaskPlan plan, PlanExecutionResult result) {
        // Record successful strategies
        if (result.isSuccess()) {
            autoMemory.recordPattern(
                "plan:" + categorize(plan.originalGoal()),
                "For goal type '%s', decomposition into %d tasks with strategy %s succeeded"
                    .formatted(
                        categorize(plan.originalGoal()),
                        plan.tasks().size(),
                        summarizeStrategies(plan)
                    )
            );
        }

        // Record failures as mistakes to avoid
        for (var failedTask : result.failedTasks()) {
            autoMemory.recordMistake(
                "plan:" + plan.originalGoal(),
                "Task '%s' failed: %s".formatted(
                    failedTask.subject(), failedTask.result().errorMessage()),
                "Consider alternative approach: %s".formatted(
                    failedTask.fallbackApproach())
            );
        }

        // Record the decomposition pattern as a reusable strategy
        autoMemory.recordPattern(
            "decomposition:" + categorize(plan.originalGoal()),
            serializeDecompositionPattern(plan)
        );
    }
}
```

### 5.5 Integration with Event Bus

```java
/**
 * Plan-related events — integrated into chelava-infra's EventBus.
 */
public sealed interface PlanEvent extends ChelavaEvent permits
    PlanEvent.PlanCreated,
    PlanEvent.PlanApproved,
    PlanEvent.TaskStarted,
    PlanEvent.TaskCompleted,
    PlanEvent.TaskFailed,
    PlanEvent.PlanReplanned,
    PlanEvent.PlanCompleted,
    PlanEvent.PlanFailed {

    record PlanCreated(String planId, int taskCount) implements PlanEvent {}
    record PlanApproved(String planId, String approver) implements PlanEvent {}
    record TaskStarted(String planId, String taskId) implements PlanEvent {}
    record TaskCompleted(String planId, String taskId, Duration duration) implements PlanEvent {}
    record TaskFailed(String planId, String taskId, String reason) implements PlanEvent {}
    record PlanReplanned(String planId, String reason) implements PlanEvent {}
    record PlanCompleted(String planId, Duration totalDuration) implements PlanEvent {}
    record PlanFailed(String planId, String reason) implements PlanEvent {}
}
```

### 5.6 Integration with Hook System

```java
// New hook events for the Task Planner
// Added to existing hook configuration in settings.json

{
    "hooks": {
        "PlanCreated": [{
            "type": "command",
            "command": "echo 'Plan created with $CHELAVA_PLAN_TASK_COUNT tasks'"
        }],
        "PrePlanExecute": [{
            "type": "prompt",
            "prompt": "Review this plan before execution. Approve or suggest changes."
        }],
        "PlanCompleted": [{
            "type": "command",
            "command": "notify-send 'Chelava: Plan completed successfully'"
        }],
        "PlanFailed": [{
            "type": "agent",
            "agent": "diagnostics",
            "prompt": "Analyze why the plan failed and suggest fixes"
        }]
    }
}
```

---

## 6. User Interaction Flow

### 6.1 Autonomy Levels

The Task Planner respects the existing permission system's autonomy levels:

| Autonomy Level | Plan Behavior |
|---------------|---------------|
| **Conservative** | Always show plan and wait for explicit approval before execution |
| **Balanced** (default) | Show plan, auto-execute low-risk tasks, prompt for high-risk |
| **Autonomous** | Auto-generate and execute plans; only pause for `UserAction` tasks |

### 6.2 Plan Presentation

When a plan requires approval, the CLI presents it as a structured task list:

```
Chelava generated a plan for: "Add user authentication with JWT"

  Plan (6 tasks, ~15 min estimated):

  1. [Research] Analyze existing auth patterns in codebase
     Strategy: Subagent(Explore) | Risk: None
  2. [CodeGen] Create JWT utility module (src/auth/jwt.ts)
     Strategy: AgentLoop | Risk: Low | Depends: #1
  3. [CodeGen] Add auth middleware (src/middleware/auth.ts)
     Strategy: AgentLoop | Risk: Low | Depends: #1
  4. [FileEdit] Update API routes with auth guards
     Strategy: AgentLoop | Risk: Medium | Depends: #2, #3
  5. [Testing] Write auth unit tests
     Strategy: Subagent(General) | Risk: None | Depends: #2, #3
  6. [Testing] Run integration tests
     Strategy: AgentLoop | Risk: None | Depends: #4, #5

  Parallel branches: [#2, #3] can run simultaneously
  Critical path: #1 -> #2 -> #4 -> #6

  [Approve] [Modify] [Reject]
```

---

## 7. Replanning Strategy

### 7.1 When to Replan

```java
public sealed interface ReplanTrigger permits
    ReplanTrigger.TaskFailure,
    ReplanTrigger.NewInformation,
    ReplanTrigger.UserFeedback,
    ReplanTrigger.ResourceExhausted {

    /** A task failed and its fallback approach should be tried. */
    record TaskFailure(String taskId, String error, int attemptCount)
        implements ReplanTrigger {}

    /** New information discovered during execution changes the plan. */
    record NewInformation(String taskId, String discovery)
        implements ReplanTrigger {}

    /** User provides feedback that requires plan adjustment. */
    record UserFeedback(String feedback)
        implements ReplanTrigger {}

    /** Token budget or time budget is running low. */
    record ResourceExhausted(String resourceType, int remaining)
        implements ReplanTrigger {}
}
```

### 7.2 Replan Rules

1. **Preserve completed work**: Never redo tasks that succeeded
2. **Minimize disruption**: Adjust only affected branches, not the whole plan
3. **Max 3 replans**: Prevent infinite replan loops; escalate to user after 3 attempts
4. **Record learnings**: Every replan feeds back into auto-memory as a STRATEGY entry

---

## 8. Module Placement

The Task Planner lives in `chelava-core` alongside the existing Agent Loop:

```
chelava-core/
  src/main/java/com/chelava/core/
    agent/
      AgentLoop.java           (existing)
      EnhancedAgentLoop.java   (modified - adds planning trigger)
    planner/
      TaskPlanner.java         (new)
      LLMTaskPlanner.java      (new)
      PlanExecutor.java        (new)
      VirtualThreadPlanExecutor.java (new)
      ComplexityEstimator.java (new)
      PlanLearner.java         (new)
      PlanTeamBridge.java      (new)
      PlanningPromptBuilder.java (new)
      TaskPlanParser.java      (new)
    planner/model/
      TaskPlan.java            (new)
      PlannedTask.java         (new)
      TaskDependency.java      (new)
      TaskCategory.java        (new)
      ExecutionStrategy.java   (new)
      PlanStatus.java          (new)
      PlanEvent.java           (new)
      ReplanTrigger.java       (new)
      PlanValidation.java      (new)
```

### Module Dependencies

```
TaskPlanner --> LLMClient (for plan generation)
            --> AutoMemory (for memory-informed planning)
            --> CodebaseAnalyzer (for codebase context)
            --> TaskStore (for Agent Teams integration)
            --> EventBus (for plan lifecycle events)
            --> PermissionPolicy (for risk assessment)
```

---

## 9. Roadmap Integration

| Phase | Task Planner Scope |
|-------|-------------------|
| **Phase 2 (Weeks 5-8)** | `ComplexityEstimator` + basic `LLMTaskPlanner` (single-branch plans, no parallelism) |
| **Phase 3 (Weeks 9-12)** | Full DAG support, parallel execution, replanning, memory integration |
| **Phase 4 (Weeks 13-18)** | Agent Teams bridge, hook integration, adaptive plan templates from auto-memory |

---

## 10. Success Metrics

| Metric | Target |
|--------|--------|
| Plan generation latency | < 3 seconds (single LLM call) |
| Plan accuracy (no replan needed) | > 70% |
| Parallel speedup (2+ branch plans) | > 1.5x vs sequential |
| Auto-memory plan reuse rate | > 30% after 50 sessions |
| User plan approval rate | > 80% (plans are useful) |
| Replan success rate | > 60% (recovery from failures) |
