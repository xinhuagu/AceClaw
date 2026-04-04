# Changelog

## [0.3.6] - 2026-04-04

### Bug Fixes

- Improve retry logic for Anthropic API rate limits (#391)
- Print plan summary and step progress in CLI console (#394)
## [0.3.5] - 2026-03-31

### Bug Fixes

- Register MCP tools incrementally per server
## [0.3.4] - 2026-03-31

### Documentation

- Document all CLI commands, provider switching, and daemon management (#385)
## [0.3.3] - 2026-03-31

### Bug Fixes

- Replan test consumed by stream retry loop (#384)
## [0.3.2] - 2026-03-31

### Bug Fixes

- Retry on Anthropic SSE overloaded/rate-limit errors instead of failing immediately
- Auto-bump to SNAPSHOT after release, fix stale version cache (#383)
## [0.3.1] - 2026-03-31

### Bug Fixes

- Remove invalid OAuth scope from token refresh request (#382)
## [0.3.0] - 2026-03-29

### Bug Fixes

- HealthMonitorTest timing tolerance for slow CI, document Windows daemon skip (#380)

### Documentation

- Windows UDS spike — AF_UNIX works on Windows 10 1803+, no transport abstraction needed (#372)
- Add philosophy article link to README header
- Add design philosophy document and link from README
- Add platform support matrix, clarify Windows as experimental (#377)
- Platform-smoke is now a required check for all PRs (#378)

### Features

- Make scheduled jobs workspace-scoped instead of daemon-global (#367)
- Windows runtime bring-up — charset detection and platform audit (#374)
- Fix Windows .cmd wrappers — proper path expansion, add restart, fix update (#376)
## [0.2.2] - 2026-03-28

### Bug Fixes

- AutoReleaseControllerTest uses fixed clock to prevent rolling window expiry (#364)
## [0.2.1] - 2026-03-28

### Bug Fixes

- Add canaryDwellHours env var + update operations runbook defaults (#297)
- Race condition in RuntimeMetricsExporter + StatisticalTest input validation (#300)
- KeychainCredentialReader tests + null guards + warn logging (#301)
- Synchronize InjectionAuditLog.appendRecord to prevent JSONL corruption (#304)
- Close 3 metrics wiring gaps in StreamingAgentHandler (#307)
- Review findings from PRs 312-315 (#316)
- Address Greptile review findings from PRs 313-315 (#317)
- 401 token refresh silently skipped when refreshToken is null (#325)
- Use actual per-category count from prompts file for significance (#310)
- Move per-category sample_size computation into replay report (#310) (#328)
- Lifecycle metrics report no_data when no transitions exist (#329)
- Copilot ignores unsupported global model and falls back to default
- Banner shows actual model from client, not raw config value
- Change Copilot default model from gpt-5.2-codex to claude-sonnet-4.5
- Agent handler uses resolved model from client, not raw config
- Translate Anthropic model names to Copilot format automatically
- Copilot ignores Anthropic global model, uses own default
- Anthropic uses config model (opus), other providers use client default
- Context1m config now sets 1M context window in capabilities (#335)
- Wrap permission modal descriptions (#342)
- Resolve symlinks in update.sh, tui.sh, restart.sh (#360)

### Documentation

- Add Plan-Execute-Replan as key differentiator in README
- Clarify that ReAct remains the single-step execution foundation
- Upgrade architecture diagram with Plan/Execute/Replan pipeline
- Restore clean architecture diagram, keep detailed version separate
- Add Plan/Execute/Replan row to original architecture diagram
- Regenerate architecture diagram PNG with white background and Plan/Replan row
- Add CHI 2025 citation link to Plan → Execute → Replan section
- Trim README — remove redundant Architecture, Security Details, Security Roadmap, and Roadmap sections
- Soften competitor comparisons in README — state structural facts without naming names
- Remove Inspired-by line; add competitor links to Plan/Replan section
- Close context engineering documentation-implementation gaps (#280)
- Codify benchmark-driven rollout policy (#290) (#305)
- Align learning_hit_rate definition to Java implementation (#308)
- Document both Anthropic auth modes — API key and OAuth
- Update Copilot model list, name format, and default behavior (#334)
- Define session/workspace/global memory ownership model (#348)

### Features

- Adopt OpenClaw Anthropic connection strategy (#283)
- Harden auto-release guardrails (#289) (#296)
- Add StatisticalTest utility and RuntimeMetricsExporter (#285) (#298)
- Replay A/B fairness + benchmark pack validation (#287) (#299)
- Add InjectionAuditLog for per-turn learning attribution (#286) (#302)
- Add BenchmarkScorecard for self-learning CI gate (#288) (#303)
- Wire RuntimeMetricsExporter + InjectionAuditLog into production path (#284) (#306)
- Enforce fresh replay artifacts in preMergeCheck (#311) (#312)
- Unify replay taxonomy to canonical benchmark categories (#310) (#313)
- Wire BenchmarkScorecard into CI via Gradle task (#309) (#314)
- Baseline collector auto-reads runtime-latest.json (#308) (#315)
- Derive version from gradle.properties at build time (#318)
- Align coverage rules across script, Gradle, CI, and Java (#310) (#319)
- Complete scorecard metric coverage for all 8 metrics (#320)
- Scorecard as CI verdict + lifecycle formula fixes (partial #309) (#322)
- Auto-read replay, lifecycle, and injection artifacts in baseline collector (#326)
- Unify to two-layer threshold model (#327)
- Proactive OAuth token refresh before expiry (#332)
- Auto-benchmark mode in dev.sh and token estimation tuning (#343)
- Explicit multi-session support for multiple CLI windows on one daemon (#346)
- Workspace-first retrieval priority in AutoMemoryStore (#352)
- One-line install script for cross-platform setup (#358)
- Add aceclaw-update command for easy pull + rebuild (#359)
- Binary distribution via GitHub Releases (#362)
## [0.1.0] - 2026-03-05

### Bug Fixes

- Fall back to /bin/sh on Alpine/BusyBox, harden test assertions
- Move path_instructions under reviews section in CodeRabbit config
- Add public unsubscribe() to prevent subscription leaks
- Drop-oldest queue policy, validate capacity, deterministic tests
- Resolve tech debt — permission modes, read-before-write, slash commands, tests (#4)
- Add missing onSubAgentStart/onSubAgentEnd to StreamEventHandler
- Resolve Java version mismatch and provider profile auto-selection
- Change copilot default model from gpt5.2 to claude-sonnet-4.5
- Fix Copilot runtime model switching and Responses API format
- Resolve sub-agent tech debt TD-1/2/5/6 (#28) (#37)
- Redraw prompt status panel after async output
- Prevent status line wrapping from stealing prompt
- Keep cursor on prompt after async status redraw
- Redisplay prompt after async status render
- Close tool status on early exits and clean panel redraw
- Avoid default profile override when ACECLAW_PROVIDER is set
- Omit unsupported Responses parameters
- Scope store=false to codex provider only
- Avoid null text in user-facing LLM errors
- Use backend-api endpoint paths in factory
- Address review feedback for planned outcomes and maintenance persistence
- Harden null guards and constructor validation per review
- Harden detectors and structured parsing
- Guard null toolName in intermediate-step detector
- Harden candidate store atomicity and anti-pattern gates (#82)
- Close Files.walk stream in validation engine
- Address review hardening in validation gate
- Serialize release state updates with controller lock
- Harden release RPC/config/state handling
- Harden observability panel parsing and null guards
- Require non-null paths in status parsers
- Resolve review feedback on bridge tests and docs link
- Fallback candidates status to ~/.aceclaw memory
- Address review feedback on baseline and anti-pattern metrics
- Harden cron tool input handling and tests
- Null-guard cron status job list
- Address phase2 review findings
- Persist and surface last cron output
- Show full completion output in foreground notifications
- Refresh prompt clock every second while idle
- Address CodeRabbit review — removeOnCancelPolicy, cumulative turns, budget break
- Route permission responses to correct thread via per-request futures (#151)
- Grep exit code 1 no longer triggers false circuit-breaker (#152) (#154)
- Remove status line refresh on every token delta (#156)
- Per-step and total-plan watchdog budget for multi-step plans (#158)
- Update OAuth user-agent to claude-cli/2.1.50 (#160)
- Rename .release-please-config.json to release-please-config.json
- Use java release-type for release-please
- Workaround release-please component undefined bug
- Switch to simple release-type with version.txt
- Add explicit package-name to fix componentNoSpace undefined
- Minimal release-please config with per-package release-type
- Set include-component-in-tag at package level
- Use node release-type with package.json for release-please
- Use v-prefixed initial_tag in cliff.toml to match tag_pattern
- Use PAT for release workflow to bypass branch protection

### Documentation

- Update BashExecTool Javadoc to mention /bin/sh fallback
- Add provider configuration guide with Copilot, Claude, and Ollama setup
- Rewrite README — security-first task agent positioning
- Emphasize Java 21 + enterprise positioning
- Add continuous learning baseline plan and collector
- Close issue-52 baseline metric and replay definition gaps
- Clarify openai-codex request semantics
- Refresh README positioning copy
- Add demo GIF to README
- Replace demo GIF with 2x speed version
- Reposition AceClaw as enterprise agent harness

### Features

- Add Windows shell support to BashExecTool
- Add type-safe event bus with sealed event hierarchy
- Integrate EventBus, permissions, and auto-memory into agent loop (#5)
- Add SSE/HTTP transport, resource bridge, health checks, and tests (#6)
- Add provider selection to dev.sh (#7)
- Add skill system, dynamic prompt budget, and Ollama fixes
- Add OpenAI Responses API client for Copilot Codex models (#11)
- Add /model command for runtime model switching (#12)
- Add Insight type hierarchy, DetectedPattern model, and ToolMetricsCollector (#13) (#19)
- Add ErrorDetector for error-correction pattern detection (#14) (#20)
- Add PatternDetector for recurring tool sequences and workflows (#15) (#21)
- Add SelfImprovementEngine for post-turn learning (#16) (#22)
- Add StrategyRefiner for insight consolidation (#17) (#23)
- Self-learning gaps P0-P3 (#25) (#26)
- Provider-aware context window auto-detection (#30) (#38)
- Add HealthMonitor + CircuitBreaker foundation (#31) (#40)
- Phase 2 gaps — background sub-agents, transcripts, tool descriptions (#27) (#39)
- Implement command hook system (#32) (#41)
- Implement BOOT.md execution at daemon startup (#33) (#43)
- Implement persistent cron scheduler (#34) (#44)
- Add web tools DDG fallback + dynamic tool guidance in system prompt (#42) (#45)
- Implement HEARTBEAT.md runner on scheduler (#35) (#46)
- Implement task planner with complexity estimation and sequential execution (#36) (#47)
- Implement dual-channel architecture for concurrent task streaming (#48) (#49)
- Add real-time streaming progress status
- Show running task list under prompt status
- Surface pending permission requests for bg/sub-agent tasks
- Popup permission prompt immediately while typing
- Show dynamic runtime status for background tasks
- Wire ToolMetrics into self-learning engine (#53) (#71)
- Add openai-codex oauth provider and auth CLI
- Align openai-codex provider with Codex backend endpoint
- Normalize failure signals into continuous learning (#75)
- Add standalone pyautogui+opencv vision click CLI
- Issue-78 unified candidate pipeline with replay quality gates (#79)
- Close outcome enforcement loop with writeback, clocked gates, and maintenance
- Generate skill drafts from promoted candidates (#84)
- Add autonomous draft validation gate engine
- Add automated skill release controller with guardrails
- Add continuous-learning status panel in CLI
- Close governance protocol and permission interaction tests
- Add compact tool trace logs with single-row status reuse
- Add hard quality gates and baseline-driven metrics
- Add cron tool and scheduling self-awareness
- Make agent max turns configurable in runtime
- Add adaptive continuation segments (phase 2)
- Default adaptive continuation and expose ops metrics
- Stream scheduler completion notices to active cli
- Add defer_check tool with scheduler and CLI notifications (#141)
- Add adaptive replanning on step failure (#121)
- Add WatchdogTimer for turn budget and time budget enforcement (#120)
- Add pre-flight context budget check (#161)
- Doom loop defense — argument-aware dedup and progress detection (#164)
- Budget warning + progress-gated auto-extension (#165)
- Parallel execution — remove turn lock dependency (#166)
- Configurable sub-agent auto-approve tool whitelist (#167) (#168)
- Cross-session preference boosting for self-learning (#169) (#170)
# Changelog
