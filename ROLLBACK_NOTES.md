# Rollback Notes — Operator Layer M2.6 + M3 (Issues #11–#16)

Per-issue rollback procedures for the feature set landed on `feat/operator-layer-m2-phase2`.

## #11 Proactive Notification Gate (M2.6)
- Location: `gemini/GeminiSessionViewModel.kt` (heartbeat/cron gating during `pendingConfirmation != null`).
- Rollback: revert commit `db6f233 feat(operator): gate proactive notifications during confirmations`. Prior forwarding of `eventClient.onNotification` → `geminiService.sendTextMessage` is restored.

## #12 `send_message` (Tiered)
- Location: `openclaw/routing/handlers/SendMessageHandler.kt` + declaration in `openclaw/ToolCallModels.kt` + policy mapping in `ConfirmationPolicy.kt`.
- Rollback: remove `SendMessageHandler` registration from `ToolRegistry`. Fallback path gracefully returns `unknown_tool`.

## #13 `set_reminder` (Tier 3)
- Location: `openclaw/routing/handlers/SetReminderHandler.kt`.
- Rollback: unregister in `ToolRegistry`.

## #14 `capture_task` (Tier 3)
- Location: `openclaw/routing/handlers/CaptureTaskHandler.kt`.
- Rollback: unregister in `ToolRegistry`.

## #15 Wearable Response Modes
- Locations: `settings/OperatorSettings.kt` (ResponseMode enum), `gemini/GeminiSessionViewModel.kt` (effectiveResponseMode flow), `gemini/GeminiLiveService.kt::restartForModeChange`, `gemini/GeminiConfig.kt` (system prompt).
- Rollback: force `ResponseMode.NORMAL` by setting the StateFlow default and skipping inference tightening; leaves the restart seam harmless.

## #16 Undo Window (Tier 3)
- Location: `operator/SessionStateManager.kt::undoableAction` StateFlow + 30s timer; router hook in `ToolCallRouter.kt`.
- Rollback: remove undo tracking from `SessionStateManager` and the router hook. Tier-3 dispatch behavior unchanged.

## Kill Switch
All three structured tools are gated behind `structuredIntentsEnabledProvider` in `IntentRouter`. Setting it to `false` disables the new intents at routing time without touching registrations.

## Known Tech Debt (Deferred)
- **ScheduledCancellation shared timer helper** — the `scope.launch { delay() }` + cancel idiom is duplicated between `ToolCallRouter.pendingTimeouts` and `SessionStateManager.undoableAction`. Extract to a single helper in a follow-up.
- **Prompt-composition helper extraction** — `buildSystemInstruction()` grew with mode directives and tool declarations inline; factor into small composable functions in a follow-up. `sessionContextBlock()` ≤800-char invariant was preserved.
- **UI UndoBanner in `GeminiOverlayView`** — `SessionStateManager.undoableAction` StateFlow exists and is stable; the Compose banner surface was not merged in this PR (original Worker 2 changes were uncommitted at integration time). UX currently relies on audio confirmation. Follow-up: pick up W2's uncommitted UI delta or reimplement as a `PendingConfirmationCard` sibling.
- **`GeminiLiveServiceTest` session-restart test** — scaffold not added in this PR; notification-gate coverage is green. Follow-up test should assert `restartForModeChange()` triggers `stopSession()` + reconnect + `pendingReconnectVoiceNote`.
