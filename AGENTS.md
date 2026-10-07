# Repository rules

- Native Android, Kotlin, Compose. Keep the architecture small and dependencies pinned.
- Use clear names and short functions. Explain only non-obvious decisions. No em dashes in new prose, comments, commits, or PRs.
- Keep an honest completed, pending, and blocked checklist in `docs/IMPLEMENTATION.md`.
- Work on feature branches. Make small coherent commits after relevant checks and staged-diff review. Commit messages are one short line without bodies or trailers.
- Confirm `Jay-Parmar/ThatsMyFace` and the intended base before pushing. Use existing author and authentication configuration. Never print credentials or change global Git settings.
- Open draft milestone PRs. Stack dependent branches, explain dependencies, inspect CI, and fix regressions. Do not merge, force-push shared history, or change visibility without authorization.
- Do not add attribution notices or co-author trailers. Preserve required third-party license notices.
- Process only explicitly selected event photos. Never modify or delete source media. Originals need owner approval and verified event peers.
- A match never authenticates anyone or grants access. Keep event permissions isolated and validate untrusted messages and file metadata.
- Keep face data, private media, credentials, local configuration, and build output out of Git, logs, feedback, and backups. Provide local deletion and future-access revocation.
- Use Android storage and security facilities. Preserve original transfer bytes and verify integrity before completion. Persist retry and duplicate-prevention state.
- No backend, cloud photo storage, analytics, accounts, or hosted recognition. Document third-party diagnostics accurately.
- Recognition must execute a real licensed model. Verify weights licensing separately, record provenance and checksums, and test preprocessing and inference with licensed fixtures.
- Run relevant build, lint, and behavioral tests. Distinguish automated, emulator, and physical-phone results. Never claim unperformed verification.
- Continue unblocked work when hardware or external services are unavailable. Do not leave mock success paths or present incomplete recognition/sharing as completed v1.
