<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: ETL & Trust System Enhancements

**Branch:** `chris/spec-updates`
**Date:** 2026-01-25

## Summary

Enhances the ETL and trust system specifications with security improvements, architectural clarifications, and operational details based on implementation review. Adds missing lifecycle events, strengthens dependency validation, clarifies multi-user boundaries, and defines revocation mechanisms.

**Changes:** 6 normative spec files enhanced with ~500 lines of requirements and clarifications.

## Changes

### N1 - Core Architecture & Concepts

**§2.10.2 Knowledge Trust and Authority - Transitive Trust Rules**

- Added 4 explicit transitive trust rules:
  - Instruction authority is not transitive
  - Trust level inheritance (lowest trust wins)
  - Cross-trust reference tracking requirements
  - Tainted isolation enforcement

**§2.10.2 Knowledge Trust and Authority - Trust Promotion and Revocation**

- **NEW:** Trust promotion is one-way per pack version (no demotion)
- **NEW:** Revocation mechanisms defined:
  - Pack removal from registries
  - Key revocation via KRLs
  - Version deprecation
  - New corrected versions (start untrusted, promote separately)

**§2.10.3 Pack Versioning and Identity**

- **NEW:** Complete pack versioning schema
  - Semantic versioning with content hashes
  - Dependency specification with version constraints
  - Circular dependency rejection requirement

**§2.10.4 Signature Key Management**

- **NEW:** Complete key management specification
  - Key configuration (no auto-generation)
  - Secure key storage (keychain/HSM/encrypted file)
  - Public key distribution requirements
  - Key rotation and revocation support
  - Algorithm requirements (ed25519 recommended, rsa-sha256 acceptable)

**§2.10.5 ETL Pipeline**

- Enhanced sanitization requirement: "BEFORE extraction" (not during)
- **NEW:** Incremental ETL specification
  - Source content hash tracking
  - Skip unchanged sources
  - Deletion detection
  - Dependency-aware re-processing
  - Pack index maintenance

**§5.4.1 OSS Concurrency**

- **NEW:** Explicit single-developer model for OSS
- **NEW:** Event streaming as integration point for Team+ plans
- Clarified value is in what you do with aggregated data, not just having it

### N2 - Workflow Execution Model

**§4.4.1 Phase Context Structure**

- **NEW:** `:data/trust-verified` subchannel
  - Scanner-verified but not promoted content
  - Enriches agent context without elevating trust
  - Includes scanner findings for transparency
  - Content retains `:untrusted` trust level

**§9.1.1 ETL Workflow Type**

- **NEW:** Custom phase sequence clarification
  - ETL uses different phases than standard workflows (e.g., Inventory → Classify → Scan → Extract → Validate → Index)
  - Standard phases: Plan → Design → Implement → Verify → Review → Release → Observe
  - ETL phases: Custom, appropriate to ingestion/normalization
  - Workflow extensibility model treats ETL as just another workflow type

### N3 - Event Stream & Observability Contract

**§3.4 ETL and Pack Events**

- **NEW:** `etl/completed` event
  - Duration tracking
  - Summary statistics (packs generated/promoted, risk findings, sources processed)
  - MUST emit after all pack activities complete
- **NEW:** `etl/failed` event
  - Failure stage identification (classification, scanning, extraction, validation)
  - Structured error details for debugging without log diving
  - MUST emit on critical failures

### N4 - Policy Packs & Gates Standard

**§2.4.2 Reference Rules (knowledge-safety)**

- **NEW:** `pack-dependency-validation` rule
  - Circular dependency detection (A → B → A)
  - Missing dependency detection
  - Version conflict resolution
  - Trust level constraint enforcement (untrusted cannot require trusted)
  - Dependency depth limit (default: 5 levels)
  - Complete dependency graph validation before loading

**§2.4.3 Deterministic Prompt Injection Tripwire Scanner**

- **ENHANCED:** Expanded detection patterns:
  - **Data exfiltration:** "send output to", "POST to", "curl http", webhook patterns
  - **Embedded execution:** Unusual code blocks, base64 blobs with `eval`, obfuscated scripts
  - **Time-based triggers:** "wait until", "after N days", "when timestamp", cron-like expressions
  - **Context confusion:** Blurring documentation vs instruction boundaries
  - Pattern matching + contextual heuristics
  - Sensitivity tuning based on content type (markdown docs → higher sensitivity)

### N5 - Interface Standard: CLI/TUI/API

**§2.3.7 ETL Namespace**

- **NEW:** `--dry-run` flag for `miniforge etl repo`
  - Show what would be processed without generating packs
  - Enables ETL workflow preview and validation

**§2.3.8 Pack Namespace**

- **ENHANCED:** `miniforge pack promote` policy enforcement
  - Multiple `--policy` flags supported (repeatable)
  - Default: ALL policies must pass (AND logic)
  - Configurable via `~/.miniforge/config.edn` (`:pack-promotion/require-all-policies`)
  - Explicit documentation of policy combination behavior

### N6 - Evidence & Provenance Standard

**§2.1 Evidence Bundle Schema**

- **ENHANCED:** `:evidence/pack-promotions` schema
  - **NEW:** `:promotion-justification` field (REQUIRED)
  - Documents why pack was promoted (e.g., "passed knowledge-safety scans", "manual review approved")
  - Enables audit trail for trust decisions

## Design Decisions

### 1. OSS is Single-Developer Focused

**Decision:** OSS miniforge targets individual developers, not multi-user teams.

**Rationale:**

- Simplifies local-first architecture
- Multi-user coordination is Team+ value proposition
- Event streaming provides integration point for aggregation

**Impact:** Clear product boundaries, simpler implementation, explicit upsell path.

### 2. ETL Uses Custom Phase Sequences

**Decision:** ETL workflows can define custom phases, not forced into standard 7-phase model.

**Rationale:**

- ETL is fundamentally different from infrastructure changes
- Standard phases (Plan → Design → Implement) don't map to ingestion workflow
- Workflow extensibility model supports this naturally

**Impact:** ETL can use phases like Inventory → Classify → Scan → Extract → Validate → Index.

### 3. Pack Promotion Requires ALL Policies by Default

**Decision:** When multiple policies are configured for promotion, ALL must pass (AND logic).

**Rationale:**

- Conservative default prevents accidental trust escalation
- Security policies should be additive, not alternative
- Configurable for special cases (e.g., "pass security OR manual-approval")

**Impact:** Stronger default security posture, explicit policy combination rules.

### 4. Trust Promotion is One-Way

**Decision:** Pack versions cannot be demoted from `:trusted` to `:untrusted`.

**Rationale:**

- Immutability of trust decisions prevents accidental downgrades
- Revocation handled through separate mechanisms (pack removal, key revocation, deprecation)
- Corrected packs released as new versions (start untrusted, promote separately)

**Impact:** Clearer trust semantics, explicit revocation paths, stronger security guarantees.

### 5. Incremental ETL for Performance

**Decision:** ETL SHOULD support incremental processing via content hash tracking.

**Rationale:**

- Large repositories shouldn't require full re-processing on every run
- Content-hash-based change detection is efficient and reliable
- Dependency-aware re-processing handles transitive changes

**Impact:** Faster ETL iterations, reduced compute costs, better developer experience.

### 6. Enhanced Prompt Injection Detection

**Decision:** Prompt injection scanner includes data exfiltration, embedded execution, and time-based trigger detection.

**Rationale:**

- Initial patterns were limited to role override and tool invocation
- Real-world attacks use more sophisticated techniques
- Defense-in-depth requires comprehensive pattern coverage

**Impact:** Stronger protection against malicious content in untrusted repositories.

## Security Considerations

### Transitive Trust Rules

**Risk:** Pack A (trusted) references Pack B (untrusted), B gains instruction authority transitively.

**Mitigation:** Explicit transitive trust rules prevent instruction authority escalation. Trust level inheritance uses lowest trust in dependency chain.

### Pack Dependency Attacks

**Risk:** Circular dependencies, version conflicts, or missing dependencies could crash workflows or enable DoS.

**Mitigation:** Comprehensive dependency validation before pack loading. Circular dependency detection, version constraint solving, dependency graph validation.

### Trust Demotion Exploits

**Risk:** Attacker demotes trusted pack to untrusted, bypasses security policies.

**Mitigation:** One-way trust promotion per version. Revocation uses separate mechanisms (removal, key revocation, deprecation).

### Time-Based Payload Triggers

**Risk:** Malicious content waits until specific timestamp before executing (evades scanner).

**Mitigation:** Prompt injection scanner detects time-based trigger patterns. Trust labeling and instruction/data separation remain primary defense.

## Implementation Priority

**Immediate (OSS v1):**

1. Implement transitive trust rules (N1 §2.10.2)
2. Add `etl/completed` and `etl/failed` events (N3)
3. Implement pack dependency validation (N4)
4. Add `:promotion-justification` to evidence bundles (N6)

**Near-term (OSS v1.1):**
5. Implement incremental ETL (N1 §2.10.5)
6. Add `--dry-run` flag to ETL CLI (N5)
7. Enhance prompt injection scanner patterns (N4)
8. Implement `:data/trust-verified` subchannel (N2)

**Future (Post-OSS):**
9. Key rotation and revocation (N1 §2.10.4)
10. Pack deprecation and removal workflows
11. Event streaming to aggregation sinks (Team+)

## Testing

**Unit Tests Required:**

- Transitive trust rule enforcement
- Circular dependency detection
- Trust promotion validation (no demotion)
- Pack dependency version constraint solving

**Integration Tests Required:**

- ETL workflow with incremental processing
- Pack promotion with multiple policies (AND logic)
- Prompt injection scanner on malicious samples
- `:data/trust-verified` subchannel in phase context

**Conformance Tests Required:**

- `etl/completed` and `etl/failed` event emission
- Pack versioning schema validation
- Evidence bundle `:promotion-justification` presence

## References

- **N1 (Architecture):** Core trust model, pack versioning, ETL pipeline, operational model
- **N2 (Workflows):** Phase context structure, ETL workflow type
- **N3 (Event Stream):** ETL lifecycle events
- **N4 (Policy Packs):** Knowledge-safety pack, dependency validation, prompt injection scanner
- **N5 (CLI/TUI/API):** ETL and pack CLI commands
- **N6 (Evidence & Provenance):** Pack promotion evidence

---

**Changes Summary:**

- 6 normative specs enhanced
- 9 new sections added
- 14 existing sections expanded
- ~500 lines of requirements and clarifications
- Security hardening across trust model, ETL, and dependency validation
