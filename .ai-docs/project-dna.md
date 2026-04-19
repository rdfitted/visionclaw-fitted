# Project DNA

How we do things in this project. Updated by AI sessions.

## Patterns That Work

### Evaluator Self-Grade via `git show <branch>:<path>`
When hive QA workers are misrouted or stuck, the Evaluator can render a contract-based verdict by reading the PR branch directly: `git show feat/<branch>:samples/CameraAccessAndroid/app/src/...`. Quote source lines + test function names against each numbered criterion — this maps cleanly to the `CRITERION N: PASS|FAIL - <evidence>` output format. Learned from 0947b987 Phase 2 verdict (QA worker-1 stuck, workers 2/3 tested wrong surface).
-> global: practices/multi-agent-workflows.md

## Patterns That Failed

### Generic `--chrome` UI QA worker on native Android milestones
`hive-manager` auth-bypass URL (`localhost:18800/api/sessions/.../auth/dev-login`) only exposes a JSON API — it is **not** a browser-reachable VisionClaw surface. Dispatching `ui` (chrome) and `a11y` (axe-core) QA workers on M2.x-style Compose/Kotlin milestones wastes cycles and produces evidence against the hive-manager Svelte frontend. Replace with a `./gradlew :app:testDebugUnitTest` worker.
-> global: practices/multi-agent-workflows.md

### `claude` CLI QA worker (ui) spontaneously stalls at STANDBY
Twice in a row on session 0947b987, a `claude` CLI QA worker spawned with `--chrome` never updated its task file from the STANDBY template. Respawn once; if the second attempt also stalls, self-grade — do not keep respawning.

## Code Conventions

### Test secrets
Unit tests (`:app:testDebugUnitTest`) require `samples/CameraAccessAndroid/app/src/main/java/.../Secrets.kt`. The file is gitignored; `Secrets.kt.example` is the placeholder. Locally and in CI, copy the example if the real file is absent. Worker-8 confirmed the suite only goes green after this copy.

## Architecture Notes
<!-- Key architectural patterns in this project -->

- VisionClaw = wearable-first multimodal frontend (Android/Kotlin, Meta Oakley HSTN glasses) for OpenClaw backend action engine.
- OpenClaw integration via WebSocket (protocol v3 handshake, glass channel header).
- Gemini Live provides voice/multimodal; OpenClaw provides tools/memory/actions.
- **No browser-reachable UI surface** — all user-facing code is Jetpack Compose. QA must target `:app:testDebugUnitTest` + source review, not web tooling.

### Operator Layer (Phase 2, PR #19)
- `SessionStateManager` (operator/) owns four StateFlows (objective, recentEntities, pendingConfirmation, recentToolResults) and emits a deterministic `sessionContextBlock()` ≤800 chars with priority objective > pending > entities > toolResults. 10-min entity inactivity prune.
- `OpenClawBridge.conversationHistory: StateFlow<List<ConversationEntry>>` is the single source of truth; `MAX_HISTORY_TURNS=10`.
- `ConfirmationPolicy` has 3 tiers (AlwaysConfirm / ConditionalConfirm(prompt) / Implicit). `confirm_pending` meta-tool registered in `ToolRegistry`, `PENDING_CONFIRMATION_TIMEOUT_MS=30_000L` in `ToolCallRouter`, supersede via `invalidatePendingActionLocked("superseded_by_new_proposal")`.
- `PendingConfirmationCard` composable in `ui/GeminiOverlayView.kt`, populated via `combine(toolCallStates, openClawConnectionState, pendingConfirmation)` in `GeminiSessionViewModel` — the 100ms polling loop is gone.
- On disconnect (onClose/onClosing/disconnect/goAway), `GeminiLiveService.invalidatePendingConfirmationsForDisconnect()` calls `manager.invalidatePendingConfirmations("disconnect")` and buffers a `pendingReconnectVoiceNote`, flushed only after the next setup completes.

## Known Test Gaps
- "Send it to him" across ≥3 turns has no dedicated end-to-end behavioral test; coverage inferred from entity-prune + SessionContextRefreshTracker unit tests. Add a multi-turn integration test in a follow-up.

## Model Performance Notes
- `claude` CLI (ui QA, `--chrome`): stalled at STANDBY twice on 0947b987. Prefer `codex` for QA workers until root cause understood.
- `codex` QA workers: started within seconds and reported structured evidence, even when graded surface was wrong.

## Global Knowledge Cross-References

Applicable global wiki pages for this project's stack (Android/Kotlin wearable, WebSocket, AI agent frontend):

-> global: patterns/error-handling.md — API errors, state cleanup, failure recovery
-> global: patterns/environment-tooling.md — Cross-platform, PowerShell/Bash
-> global: patterns/integration-sync.md — Webhook design, transport flags, bidirectional sync (relevant for WebSocket event stream)
-> global: practices/multi-agent-workflows.md — Thread types, wave architecture
-> global: practices/model-selection.md — Role-based assignment, speed/quality trade-offs

---
*Curated from learnings.jsonl by /curate-learnings skill*
*Last updated: 2026-04-18 (curation #1 — 6 entries from session 0947b987)*
