<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Promotion Justification

## Overview

Enhances evidence bundles to include `:promotion-justification` field for pack promotions, enabling audit trails for trust decisions as specified in N6 §2.1.

## Motivation

Spec enhancement PR #84 added requirement for audit trails on trust promotion decisions. Without justification tracking:

- Pack promotions from `:untrusted` to `:trusted` have no documented rationale
- Audit trails for trust decisions are incomplete
- Security reviews cannot verify why packs were promoted
- Compliance requirements for justifying trust changes are not met
- Debugging trust escalation issues is difficult

This PR implements the `:promotion-justification` field as a required component of pack promotion evidence.

## Changes in Detail

### New Files

**`components/knowledge/src/ai/miniforge/knowledge/promotion.clj`** (158 lines)

- Standard justification templates:
  - `safety-scan-passed` - "passed knowledge-safety scans with no violations"
  - `manual-review-approved` - "manual review approved by trusted administrator"
  - `signature-verified` - "verified signature from trusted key"
  - `policy-compliance` - "meets all policy compliance requirements"
  - `automated-validation` - "automated validation and security checks"
- `create-promotion-record` with required `:justification` field
- Trust level validation preventing invalid promotions:
  - Cannot promote from `:tainted` to `:trusted` (must go through `:untrusted` first)
  - Validates source and target trust levels
- `record-promotion-in-workflow` for workflow state integration

**`components/evidence-bundle/test/ai/miniforge/evidence_bundle/schema_test.clj`** (117 lines)

- 9 tests validating `pack-promotion-schema`:
  - Required `:promotion-justification` field
  - Trust level validation
  - Evidence bundle integration
- Tests verify schema validation catches missing justifications

**`components/knowledge/test/ai/miniforge/knowledge/promotion_test.clj`** (117 lines)

- 15 tests covering:
  - Justification formatting with templates
  - Promotion record creation
  - Trust level validation (prevents invalid promotions)
  - Workflow integration
- 54 assertions, 100% passing

### Modified Files

**`components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj`** (+145 lines)

- Added `pack-promotion-schema`:

  ```clojure
  {:pack/id string
   :pack/version string
   :pack/from-trust-level keyword  ; :untrusted, :trusted, :tainted
   :pack/to-trust-level keyword
   :promotion-timestamp inst
   :promotion-justification string  ; REQUIRED
   :promoted-by string}
  ```

- Added `:evidence/pack-promotions` vector to evidence bundle schema
- Fixed `optional-key` mechanism using `OptionalKey` record for proper validation
- Made `:evidence/semantic-validation` optional when not applicable

**`components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj`** (+162 lines)

- `build-pack-promotion-evidence` to transform promotion records
- `collect-pack-promotions` to gather promotions from workflow state
- Integration into `assemble-evidence-bundle`:
  - Extracts promotion records from `:workflow/pack-promotions`
  - Transforms into evidence format
  - Includes in `:evidence/pack-promotions` field

**`components/evidence-bundle/src/ai/miniforge/evidence_bundle/protocols/impl/evidence_bundle.clj`** (+82 lines)

- Fixed validation to skip empty semantic-validation maps
- Allows optional evidence sections to be omitted cleanly

## Justification Examples

The implementation provides standard templates for common promotion scenarios:

1. **Safety Scan Passed**
   - "passed knowledge-safety scans with no violations"
   - Used when automated scanners approve the pack

2. **Manual Review Approved**
   - "manual review approved by trusted administrator"
   - Used when human review is required for promotion

3. **Signature Verified**
   - "verified signature from trusted key"
   - Used when cryptographic signatures validate the pack

4. **Policy Compliance**
   - "meets all policy compliance requirements"
   - Used when all configured policies pass

5. **Automated Validation**
   - "automated validation and security checks"
   - Generic justification for automated promotion

Custom justifications can be provided by pack authors for specific scenarios.

## Promotion Record Schema

```clojure
{:pack/id "com.example/feature-auth"
 :pack/version "1.2.3"
 :pack/from-trust-level :untrusted
 :pack/to-trust-level :trusted
 :promotion-timestamp #inst "2026-01-25T..."
 :promotion-justification "passed knowledge-safety scans with no violations"
 :promoted-by "automated-etl-pipeline"}
```

## Testing Plan

- `bb test` - All tests passing:
  - Evidence bundle schema tests: 22 assertions, 0 failures
  - Knowledge promotion tests: 54 assertions, 0 failures
  - All existing tests: 1,800+ assertions, 0 failures
- `bb pre-commit` - All checks passed

## Deployment Plan

- No special deployment steps; merge and release with OSS v1.0
- Breaking changes: None (`:promotion-justification` is required for new promotions, but existing evidence bundles without it remain valid)
- Migration: Existing packs can be re-promoted with justification if needed

## Related Issues/PRs

- Spec enhancement: PR #84 (ETL & Trust System Enhancements)
- N6 spec: §2.1 Evidence Bundle Schema
- ETL critical sequence: PR17/21 (this PR)
- Complements: PR14 (trust rules), PR15 (dependency validation), PR16 (ETL events)

## Checklist

- [x] `:promotion-justification` field added to schema
- [x] Field is REQUIRED for pack promotions
- [x] Standard justification templates provided
- [x] Justification collected during promotion
- [x] Included in evidence bundles
- [x] Tests verify field presence and validation (24 tests, 76 assertions, 100% passing)
- [x] Trust level validation prevents invalid promotions
- [x] Workflow state integration implemented
- [x] Pre-commit validation passed
- [x] N6 §2.1 conformant
