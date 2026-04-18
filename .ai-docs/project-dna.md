# Project DNA

How we do things in this project. Updated by AI sessions.

## Patterns That Work
<!-- Successful approaches discovered by sessions -->

## Patterns That Failed
<!-- Approaches that didn't work - avoid repeating -->

## Code Conventions
<!-- Project-specific conventions learned from codebase -->

## Architecture Notes
<!-- Key architectural patterns in this project -->

- VisionClaw = wearable-first multimodal frontend (Android/Kotlin, Meta Oakley HSTN glasses) for OpenClaw backend action engine.
- OpenClaw integration via WebSocket (protocol v3 handshake, glass channel header).
- Gemini Live provides voice/multimodal; OpenClaw provides tools/memory/actions.

## Model Performance Notes
<!-- Which models worked best for this project's tasks -->

## Global Knowledge Cross-References

Applicable global wiki pages for this project's stack (Android/Kotlin wearable, WebSocket, AI agent frontend):

-> global: patterns/error-handling.md — API errors, state cleanup, failure recovery
-> global: patterns/environment-tooling.md — Cross-platform, PowerShell/Bash
-> global: patterns/integration-sync.md — Webhook design, transport flags, bidirectional sync (relevant for WebSocket event stream)
-> global: practices/multi-agent-workflows.md — Thread types, wave architecture
-> global: practices/model-selection.md — Role-based assignment, speed/quality trade-offs

---
*Curated from learnings.jsonl by /curate-learnings skill*
*Last updated: (never)*
