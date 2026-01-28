# feat: Transitive Trust Rules

## Overview

Implements N1 §2.10.2 transitive trust rules to prevent trust escalation attacks in knowledge packs.
Ensures that packs maintain proper trust boundaries and cannot maliciously escalate privileges through
dependency chains.

## Motivation

Spec enhancement PR #84 added comprehensive trust model requirements to prevent attacks where:

- Trusted packs with instruction authority transitively grant authority to untrusted dependencies
- Tainted content leaks into instruction authority through dependency chains
- Trust levels are incorrectly computed across pack references

This PR implements the 4 core transitive trust rules defined in N1 §2.10.2.

## Changes in Detail

### New Files

**`components/knowledge/src/ai/miniforge/knowledge/trust.clj`** (303 lines)

- Trust level ordering: `:tainted` < `:untrusted` < `:trusted`
- Authority channels: `:authority/instruction` (requires trusted) vs `:authority/data` (any trust level)
- Pack reference creation and validation
- Circular dependency detection using depth-first search
- Tainted content isolation verification
- Trust level inheritance computation (lowest trust wins)

**`components/knowledge/test/ai/miniforge/knowledge/trust_test.clj`** (285 lines)

- 59 assertions testing all 4 transitive trust rules
- Edge case coverage: circular dependencies, missing dependencies, tainted isolation
- Trust level inheritance validation
- Cross-trust reference tracking

### Modified Files

**`components/knowledge/src/ai/miniforge/knowledge/interface.clj`** (+62 lines)

- Exported trust validation API:
  - `make-pack-ref` - Create pack references with trust metadata
  - `validate-transitive-trust` - Validate entire dependency graph
  - `compute-inherited-trust-level` - Calculate inherited trust level

**`components/policy-pack/src/ai/miniforge/policy_pack/loader.clj`** (+170 lines)

- Layer 4: Trust validation integration
- `validate-pack-trust` with dependency resolution
- `load-pack-with-trust-validation` for trust-enforced loading
- Pack-to-trust-ref conversion utilities

**`components/policy-pack/src/ai/miniforge/policy_pack/schema.clj`** (+24 lines)

- `TrustLevel` schema: `:trusted` | `:untrusted` | `:tainted`
- `AuthorityChannel` schema: `:authority/instruction` | `:authority/data`
- Enhanced `PackManifest` with optional trust fields

## The 4 Transitive Trust Rules

1. **Instruction authority is not transitive**
   - If Pack A (`:trusted`, `:authority/instruction`) references Pack B (`:untrusted`), Pack B MUST remain
     `:authority/data` and MUST NOT gain instruction authority through the reference
   - Prevents privilege escalation through dependency chains

2. **Trust level inheritance**
   - When Pack A includes content from Pack B, the resulting combined content MUST be assigned the lower trust level
   - Trust ordering: `:tainted` < `:untrusted` < `:trusted`
   - Ensures conservative trust propagation

3. **Cross-trust references**
   - Packs MAY reference other packs of any trust level
   - Implementations MUST track and validate the transitive trust graph before allowing execution
   - Detects circular dependencies and missing dependencies

4. **Tainted isolation**
   - Content marked `:tainted` MUST NOT be included in any pack used for instruction authority, even transitively
   - Checked through entire dependency chain with path tracing for debugging

## Testing Plan

- `bb test` - All 59 trust-specific assertions passing
- `bb pre-commit` - All checks passed
- No regressions in existing tests

## Deployment Plan

- No special deployment steps; merge and release with OSS v1.0
- Breaking changes: None (additive only)

## Related Issues/PRs

- Spec enhancement: PR #84 (ETL & Trust System Enhancements)
- N1 spec: §2.10.2 Knowledge Trust and Authority
- ETL critical sequence: PR14/21 (this PR)
- Integrates with: PR15 (pack-dependency-validation)

## Checklist

- [x] All 4 transitive trust rules enforced
- [x] Trusted pack cannot transitively grant instruction authority to untrusted pack
- [x] Trust level inheritance uses lowest trust in dependency chain
- [x] Tainted content isolated from instruction authority
- [x] Circular dependency detection implemented
- [x] Cross-trust reference tracking implemented
- [x] Tests verify all rules (59 assertions, 100% passing)
- [x] Pre-commit validation passed
- [x] N1 §2.10.2 conformant
