# 11 — Licensing and clean-room rules

This is engineering guidance, not legal advice. Get a lawyer's opinion before any code reuse.

## What AFFiNE's license says (Observed, `LICENSE` and `packages/backend/server/LICENSE`)

- Root `LICENSE`: content under `packages/backend` and `packages/common/native` is under the license in `packages/backend/server/LICENSE`. Everything else is MIT (`LICENSE-MIT`). Third-party parts keep their own licenses. GitHub reports the repo license as `NOASSERTION` because it is mixed.
- `packages/backend/server/LICENSE` is the **AFFiNE Enterprise Edition license**: use in production requires agreeing to AFFiNE's subscription terms and a valid EE subscription for the number of seats; modifications are owned by AFFiNE; copying, publishing, distributing or selling is forbidden; copying and modifying for development and testing is allowed. It also states that parts distributed as client-side files are under MPL 2.0.
- The root licence text and the EE text overlap in places on client-side code (MIT vs MPL 2.0). Unclear which applies to which file without a lawyer.

## Rules for Vellune

1. **Do not copy or translate AFFiNE server code**, including `packages/backend/**` and the backend native addon. Behaviour (event names, table shapes seen in public schema) informs design; implementations are written from scratch.
2. **Do not copy BlockSuite/AFFiNE client code** into Vellune. Even where MIT would allow it, the stack differs (Rust + native Kotlin) and mixed-license ambiguity is not worth it. If MIT code is ever vendored, record file, commit and licence in `THIRD_PARTY.md` first.
3. **Do not use AFFiNE names, logos, icons or UI assets.** "Vellune" is original.
4. Wire compatibility with AFFiNE (reading its workspaces) is **not a goal**. Import via exported Markdown/files only.
5. Keep reverse-engineering clones outside the repo (scratchpad). Never commit them.
6. Prefer permissive dependencies (MIT/Apache-2.0). Run `cargo deny` in CI with an allow list. Flag GPL/AGPL/MPL dependencies for review.
7. Obsidian is closed source. Only public documentation and open specs (JSON Canvas, MIT) are used. Do not decompile or copy Obsidian assets.

## Vellune's own license

Undecided. Candidates: AGPL-3.0 for the server (protects hosted forks) with Apache-2.0/MIT for core crates and clients, or all Apache-2.0. Decide before the first public release (TODO: ADR 0005).

## Contributor hygiene

- Anyone contributing must state they did not copy from AFFiNE's EE-licensed code.
- Design docs cite *observations*, not code excerpts. Short identifiers (event names, field names) are fine; paragraphs of code are not.
