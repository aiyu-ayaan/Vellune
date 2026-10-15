# ADR 0001: Modular Monolith and Rust Core Architecture

- **Status:** Accepted
- **Deciders:** Vellune Core Team
- **Date:** 2026-10-16

---

## Context and Problem Statement

Vellune requires high-performance document processing, vector inking, and real-time offline-first synchronization across multiple platforms (Desktop, Web, and future Android). We need to determine the backend architecture and shared core strategy that balances developer velocity, operational simplicity, and performance.

---

## Decision

We have decided to structure Vellune as a **Modular Monolith in Rust**:
1. All domain logic, document hierarchies, CRDT sync adapters, and storage interfaces are maintained in decoupled internal Rust crates (`crates/*`).
2. Cloud services deploy as a unified set of Rust binaries (`services/api`, `services/sync`, `services/workers`) sharing the core crates.
3. The desktop client uses Tauri 2 with React + TypeScript, embedding local SQLite persistence.
4. The web client shares the same React document and canvas UI components.
5. Native Android inking is deferred to Phase 2 (post-MVP) to ensure focused execution on the desktop and web core.

---

## Consequences

### Positive
- Single Cargo workspace eliminates redundant protocol definitions and DTO duplication.
- Rust provides sub-millisecond execution, zero-cost memory safety, and predictable memory footprint.
- Operational overhead is low: deployment requires a single binary or container set rather than a complex distributed microservices fleet.

### Negative / Tradeoffs
- Cross-compilation setup required for Tauri desktop platform targets.
- TypeScript frontend components and Rust backend share data types via versioned protocol contracts (`crates/api-types` and `crates/sync-protocol`).
