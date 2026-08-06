<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N4 — Policy Packs & Gates Standard

**Version:** 0.7.0-draft
**Date:** 2026-08-05
**Status:** Draft
**Conformance:** MUST

_v0.7.0 unifies the severity vocabulary, adds pack resolution and gate binding
(§5.3–§5.5), check-function execution semantics (§3.5), taxonomy compatibility
(§2.1.1), signature canonicalization (§8.1.1), the override/waiver contract
(§6.3.1), and conformance requirement IDs (§9.4)._

---

## 1. Purpose & Scope

This specification defines the **policy pack** and **gate validation** contracts for miniforge
autonomous software factory. It establishes:

- **Taxonomy artifact** - Independently versioned category tree (new in 0.6)
- **Policy pack structure** - Format, versioning, taxonomy reference, overlay/extends
- **Mapping artifact** - First-class bridge between policy systems (new in 0.6)
- **Overlay pack** - Extension model for packs without forking (new in 0.6)
- **Gate execution contract** - Check and repair function interfaces, and the
  execution semantics that bound them (§3.5)
- **Pack resolution and gate binding** - How a gate acquires its rules, and what
  happens when packs disagree (§5.3–§5.4)
- **Semantic intent validation** - Rules for intent vs. behavior matching
- **Violation schema** - Severity levels, remediation, enforcement actions
- **Remediation UX contract** - Human and machine-readable repair guidance
- **Override and waiver** - What may be overridden, and what records it (§6.3.1)

Policy packs enable **policy-as-code** enforcement at workflow gates, preventing intent violations and dangerous
changes.

For the full design rationale behind the four-artifact model, see
`docs/design/policy-pack-taxonomy.md`.

### 1.1 Design Principles

1. **Declarative** - Policies define "what" to check, not "how" to check it
2. **Composable** - Multiple policy packs can be combined via overlays and mappings
3. **Versioned** - Taxonomy, packs, and mappings are independently versioned
4. **Repairable** - Violations should provide actionable remediation guidance
5. **Observable** - All policy checks emit events and store results in evidence (see N3, N6)
6. **Rule IDs are the durable anchor** - Rule IDs are stable, namespace-qualified keywords;
   categories are classification metadata that can evolve independently

### 1.2 Canonical Taxonomy

miniforge ships a canonical taxonomy (`miniforge/dewey`) and a canonical pack (`miniforge/core`).
The platform normalises findings against this taxonomy by default. This is an explicit design
decision, not an implementation detail. Third-party packs extend or map to it.

---

## 2. Policy Pack Structure

### 2.1 Taxonomy Artifact

A taxonomy is an independently versioned category tree. Packs reference taxonomies by ID and
minimum version. Taxonomy and ruleset have different change velocities and MUST be versioned
separately.

```clojure
{:taxonomy/id         keyword           ; REQUIRED: e.g. :miniforge/dewey
 :taxonomy/version    string            ; REQUIRED: SemVer e.g. "1.0.0"
 :taxonomy/title      string            ; REQUIRED: Human-readable name

 :taxonomy/categories                   ; REQUIRED: Category definitions
 [{:category/id    keyword              ; REQUIRED: Stable namespaced keyword
   :category/code  string              ; REQUIRED: Display code (e.g. "210")
   :category/title string              ; REQUIRED: Human-readable label
   :category/parent keyword            ; OPTIONAL: Parent category ID (nil = root)
   :category/order  int}               ; REQUIRED: Sort order for rule application
  ...]

 :taxonomy/aliases                      ; OPTIONAL: Logical name → category ID
 [{:alias/id keyword
   :alias/of keyword}
  ...]}
```

The canonical miniforge taxonomy is distributed at
`components/policy-pack/resources/taxonomies/miniforge-dewey-1.0.0.edn`.

#### 2.1.1 Taxonomy Compatibility

`:taxonomy/min-version` (§2.2) only means something if a taxonomy version bump
has defined semantics. For a taxonomy:

| Change | Bump |
|--------|------|
| Adding a category | MINOR |
| Adding an alias | MINOR |
| Changing a `:category/title` or `:category/order` | MINOR |
| Removing a category | MAJOR |
| Changing a `:category/id`'s meaning | MAJOR |
| Re-parenting a category | MAJOR |

A pack declaring `:taxonomy/min-version "1.2.0"` MUST load against any 1.x at
or above 1.2.0, and MUST NOT load against 2.x without an explicit pack revision.

`:category/id` values are stable identifiers, not labels. Renaming a category's
display code or title is a MINOR change precisely because the ID does not move;
rules bind to IDs and are unaffected. Reusing a retired `:category/id` for a
different concept is forbidden — it silently reclassifies every rule that
referenced it.

A pack referencing a `:category/id` absent from its resolved taxonomy MUST fail
validation. Implementations MUST NOT drop the unknown category and load the
rule anyway: a rule whose classification silently vanished still reports, but
no longer routes.

### 2.2 Policy Pack Schema

```clojure
{:pack/id           keyword            ; REQUIRED: Namespaced e.g. :miniforge/core
 :pack/version      string             ; REQUIRED: SemVer e.g. "1.0.0"
 :pack/title        string             ; REQUIRED: Human-readable name

 :pack/description  string             ; OPTIONAL: What this pack validates
 :pack/author       string             ; OPTIONAL: Pack author/maintainer
 :pack/license      string             ; OPTIONAL: License (e.g. "Apache-2.0")

 ;; Taxonomy reference — pack declares which taxonomy its rule categories belong to.
 ;; min-version allows compatible taxonomy upgrades without requiring pack rev.
 :pack/taxonomy-ref                    ; REQUIRED for packs with rules
 {:taxonomy/id          keyword
  :taxonomy/min-version string}

 ;; Overlay — inherit rules + taxonomy ref from base packs; add/override on top.
 ;; Pack MUST NOT declare both :pack/extends and conflicting :pack/taxonomy-ref.
 :pack/extends                         ; OPTIONAL
 [{:pack/id      keyword
   :pack/version string}
  ...]

 :pack/rules       [...]               ; REQUIRED: Validation rules (see Section 2.3)
 :pack/overrides   [...]               ; OPTIONAL: Severity/enable overrides on inherited rules
 :pack/scanners    [...]               ; OPTIONAL: Custom scanners (see Section 2.5)

 ;; Convenience bundled mapping artifacts. These are standalone mapping artifacts
 ;; distributed with the pack. They can also be loaded independently.
 :pack/bundled-mappings [keyword ...]  ; OPTIONAL: Mapping artifact IDs

 :pack/metadata
 {:tags         [string ...]           ; OPTIONAL: Tags for discovery
  :target-types [keyword ...]          ; OPTIONAL: Applicable workflow types
  :created-at   inst
  :updated-at   inst}

 :pack/signature string}               ; OPTIONAL: Cryptographic signature
```

### 2.3 Policy Rule Schema

```clojure
{:rule/id           keyword            ; REQUIRED: Namespaced e.g. :mf.rule/copyright-header
                                       ;   Globally unique. Immutable after publication.
 :rule/title        string             ; REQUIRED: Human-readable label for reports
 :rule/description  string             ; REQUIRED: What this rule checks

 :rule/categories   [keyword ...]      ; REQUIRED: One or more taxonomy category IDs (plural)
                                       ;   A rule may belong to multiple categories.
 :rule/severity     keyword            ; REQUIRED: canonical severity (§2.3.1)
 :rule/enabled?     boolean            ; OPTIONAL: Default true
 :rule/auto-fix?    boolean            ; REQUIRED: Whether mechanical fix is safe without review

 :rule/check-fn     function           ; REQUIRED: Validation function (see Section 3.1)
 :rule/repair-fn    function           ; OPTIONAL: Auto-repair function (see Section 3.2)

 :rule/applies-to   [keyword ...]      ; OPTIONAL: Artifact types this rule checks
 :rule/phase        keyword            ; OPTIONAL: Which phase to run (:implement, :review, etc.)

 :rule/remediation-template string     ; REQUIRED: Template for remediation message
 :rule/documentation-url    string     ; OPTIONAL: Link to detailed docs

 :rule/deprecated-by keyword}          ; OPTIONAL: Rule ID that supersedes this rule
```

**Rule ID convention:** `:<pack-ns>.rule/<rule-name>` — e.g. `:mf.rule/copyright-header`,
`:acme.rule/internal-banner`. Rule IDs are keywords, never strings.

**Categories are plural from day one.** Use `:rule/categories [...]` even when a rule currently
belongs to a single category. This prevents a schema migration when multi-category rules arise.

#### 2.3.1 Severity Levels

There is **one severity vocabulary** in miniforge, ordered most to least severe:

```clojure
:critical  :high  :medium  :low  :info
```

It is used by `:rule/severity` (§2.3), `:violation/severity` (§3.3), and every
downstream projection — supervisory attention, dashboards, reports. A rule's
severity is what its violations carry, so a second vocabulary for rules would
require a lossy translation at exactly the boundary where enforcement is
decided.

| Severity | Meaning | Enforcement |
|----------|---------|-------------|
| `:critical` | Unsafe or intent-violating; no autonomous path forward | MUST block phase completion; MUST NOT be overridable by `:gate/allow-override?` alone (§6.3) |
| `:high` | Serious defect or policy breach | MUST block phase completion |
| `:medium` | Should be fixed; auto-repair or review | SHOULD block unless auto-repaired or waived (§6.3) |
| `:low` | Minor; worth reporting | MUST NOT block |
| `:info` | Informational; no action required | MUST NOT block |

Implementations MUST reject a pack whose `:rule/severity` is outside this set.

**Legacy values.** `:error` and `:warning` appeared in earlier drafts of this
spec and MAY be encountered in third-party packs authored against them.
Implementations MUST normalize `:error` → `:high` and `:warning` → `:medium` at
load time, and SHOULD warn that the pack targets a withdrawn vocabulary. They
MUST NOT carry a legacy value past the load boundary.

### 2.4 Mapping Artifact

A mapping artifact bridges one policy system to another. It is a first-class standalone artifact —
neither the source nor the target owns it. A pack may bundle convenience mappings, but mappings
MUST be loadable independently of any pack.

```clojure
{:mapping/id      keyword            ; REQUIRED: Namespaced e.g. :miniforge-to-vanta/core-2026
 :mapping/version string             ; REQUIRED: SemVer

 :mapping/source
 {:mapping/source-kind    keyword    ; :pack | :taxonomy | :framework
  :mapping/source-id      keyword
  :mapping/source-version string}

 :mapping/target
 {:mapping/target-kind    keyword    ; :pack | :taxonomy | :framework
  :mapping/target-id      keyword
  :mapping/target-version string}

 :mapping/entries
 [{;; Source side: reference by rule ID or category ID (not both)
   :source/rule     keyword          ; OPTIONAL: specific rule ID
   :source/category keyword          ; OPTIONAL: category ID (category-level mapping)

   ;; Target side
   :target/control  string           ; OPTIONAL: target framework control ID (nil = no mapping)

   ;; Mapping quality metadata
   :mapping/type    keyword          ; REQUIRED: :exact | :broad | :partial | :none
   :mapping/notes   string}          ; OPTIONAL: rationale / caveats
  ...]

 :mapping/authorship
 {:publisher    keyword              ; Who authored this mapping
  :confidence   keyword              ; :high | :medium | :low | :unvalidated
  :validated-at string}}             ; ISO date when last validated against target version
```

**Mapping types:**

| Type | Meaning |
|---|---|
| `:exact` | Source rule directly and completely satisfies the target control |
| `:broad` | Category-level coverage; individual rules may vary |
| `:partial` | Source rule partially satisfies the target control |
| `:none` | Explicitly documented as having no mapping (absence of entry ≠ no mapping) |

### 2.5 Overlay Pack

An overlay pack extends one or more base packs. It inherits the base taxonomy reference and rule
set, adding new rules and/or overriding severity and enable/disable settings.

```clojure
{:pack/id      keyword              ; REQUIRED: Namespaced e.g. :acme/internal-policy
 :pack/version string               ; REQUIRED

 :pack/extends                      ; REQUIRED for overlay packs
 [{:pack/id      keyword
   :pack/version string}
  ...]

 ;; New rules — IDs must not collide with any inherited rule
 :pack/rules    [...]

 ;; Overrides on inherited rules — only :rule/severity and :rule/enabled? may be overridden
 :pack/overrides
 [{:rule/id      keyword            ; REQUIRED: must exist in inherited rule set
   :rule/severity  keyword          ; OPTIONAL: override severity
   :rule/enabled?  boolean}         ; OPTIONAL: enable or disable
  ...]}
```

**Overlay resolution rules (MUST):**

1. Inherited rules are merged from all `:pack/extends` entries in declaration order.
2. Overlay `:pack/rules` are appended. Rule IDs MUST NOT collide with inherited rules.
3. `:pack/overrides` apply last; only `:rule/severity` and `:rule/enabled?` are overridable.
4. Taxonomy ref is inherited from the base pack(s). An overlay that declares a conflicting
   `:pack/taxonomy-ref` is invalid.

### 2.6 Scanner Protocol

**Scanners** are reusable components that analyze artifacts and extract structured data for rules to check.

```clojure
(defprotocol Scanner
  (scan [scanner artifact context]
    "Analyze artifact and return structured findings.
     Returns {:findings [...] :metadata {...}}"))
```

#### 2.6.1 Example Scanner

```clojure
;; Terraform Plan Scanner
(defn terraform-plan-scanner
  "Scans Terraform plan output for resource changes"
  [artifact context]

  (let [plan-output (:artifact/content artifact)
        parsed (parse-terraform-plan plan-output)]

    {:findings
     [{:finding/type :resource-change
       :finding/action :create
       :finding/resource-type "aws_security_group"
       :finding/resource-name "new_sg"
       :finding/location "main.tf:45"}

      {:finding/type :resource-change
       :finding/action :update
       :finding/resource-type "aws_instance"
       :finding/resource-name "web"
       :finding/location "main.tf:12"}]

     :metadata
     {:total-creates 1
      :total-updates 1
      :total-destroys 0}}))
```

### 2.7 Knowledge Safety and Pack Validation (Reference)

miniforge MUST support deterministic policy packs that protect the system from prompt-injection
and untrusted input escalation during ingestion and execution.

A reference policy pack named `knowledge-safety` SHOULD be provided.

#### 2.7.1 Threat Model

Untrusted repository content (markdown, issues, wikis, etc.) may contain instructions that
attempt to override agent behavior. The platform MUST treat such content as _data_ unless it
is normalized into schema-valid packs and promoted to `:trusted` under policy.

#### 2.7.2 Reference Rules (knowledge-safety)

The `knowledge-safety` pack SHOULD include rules such as:

- `require-trust-labels`
  - FAIL if ingested knowledge units or packs lack `:trust-level` and `:authority`
- `no-untrusted-instruction-authority`
  - FAIL if any `:trust-level :untrusted` content is routed into instruction authority
- `no-markdown-agent-interface`
  - FAIL if runtime agent definitions are derived from markdown rather than EDN packs
- `prompt-injection-tripwire`
  - WARN/FAIL on high-confidence prompt injection patterns in untrusted sources
- `pack-schema-validation`
  - FAIL if generated packs do not conform to schemas
- `pack-root-allowlist`
  - FAIL if packs are loaded from non-declared registry roots
- `pack-dependency-validation`
  - FAIL if pack dependencies contain circular references, missing dependencies, or version conflicts
  - FAIL if pack depends on higher-trust content without explicit promotion path
  - WARN if pack dependency chain exceeds configured depth limit (default: 5 levels)
  - Implementations MUST:
    1. Build complete dependency graph before loading any pack
    2. Detect circular dependencies (A depends on B, B depends on A)
    3. Validate all transitive dependencies are available
    4. Check version constraints are satisfiable across dependency tree
    5. Enforce trust level constraints (untrusted pack cannot require trusted dependency)
  - Example violations:
    - Circular: pack A v1.0 → pack B v1.0 → pack A v2.0
    - Missing: pack A requires pack B v1.0, but pack B not in registry
    - Version conflict: pack A requires pack C v1.x, pack B requires pack C v2.x
    - Trust violation: pack A (:untrusted) requires pack B (:trusted, :authority/instruction)

#### 2.7.3 Deterministic Prompt Injection Tripwire Scanner

The platform SHOULD ship a deterministic scanner that emits findings on suspicious
directives, including (non-exhaustive):

- **Role and instruction overrides:** `SYSTEM:`, `DEVELOPER:`,
  "ignore previous instructions", "you are now", "disregard all prior"
- **Tool invocation bait:** "run this command", "call tool",
  "execute the following", "invoke function"
- **Data exfiltration attempts:** "send output to", "POST to", "curl http",
  "webhook", patterns suggesting data leakage to external endpoints
- **Embedded execution patterns:** Unusual code blocks in documentation context
  (e.g., shell scripts, base64 blobs with `eval`, obfuscated JavaScript/Python)
- **Time-based triggers:** Patterns suggesting delayed or conditional execution
  ("wait until", "after N days", "when timestamp", "cron-like expressions"
  in unexpected contexts)
- **Obfuscation indicators:** Large base64 blobs, repeated encoding markers
  (multiple layers of encoding), hexadecimal or unicode escape sequences
  suggesting hidden content
- **Authority escalation:** "this is the system prompt", "highest priority",
  "override all policies", "administrator mode", "root access"
- **Context confusion:** Attempts to blur boundaries between documentation
  and instructions ("the following is a system message",
  "internal use only: execute")

The scanner SHOULD use pattern matching (regex, keyword detection) combined with
contextual heuristics (e.g., code blocks in markdown files that aren't in fenced
code syntax).

Implementations SHOULD tune sensitivity based on content type:

- Markdown files in wiki/docs directories → higher sensitivity
- Code files with inline documentation → moderate sensitivity
- Structured data files (JSON, YAML, EDN) → context-dependent

This scanner MUST be treated as a _tripwire_ rather than a complete security solution.
The primary defense MUST remain trust labeling, schema validation, and
instruction/data separation.

---

## 3. Gate Execution Contract

### 3.1 Check Function Signature

All policy rules MUST provide a check function:

```clojure
(defn check-fn
  "Validate artifact against rule.

   Args:
     artifacts - Vector of artifacts being validated
     context   - Execution context (workflow, phase, intent, etc.)

   Returns:
     {:passed? boolean
      :violations [...]  ; Vector of violations (see Section 3.3)
      :metadata {...}}   ; Optional metadata"

  [artifacts context]
  ...)
```

#### 3.1.1 Check Function Contract

Check functions MUST:

1. Return map with `:passed?` boolean
2. Include `:violations` vector (empty if passed)
3. Be pure functions (no side effects)
4. Be deterministic (same inputs → same outputs)

Check functions MAY:

- Use scanners to analyze artifacts
- Access context for intent, constraints, etc.
- Return metadata for debugging

#### 3.1.2 Example Check Function

```clojure
(defn no-public-s3-buckets-check
  "Validates that S3 buckets are not publicly accessible"
  [artifacts context]

  (let [;; Find Terraform plan artifact
        tf-plan (find-artifact-by-type artifacts :terraform-plan)

        ;; Scan for S3 bucket changes
        scan-result (terraform-plan-scanner tf-plan context)

        ;; Find S3 bucket resources
        s3-buckets (filter #(= "aws_s3_bucket" (:finding/resource-type %))
                          (:findings scan-result))

        ;; Check for public access
        public-buckets (filter #(public-acl? %) s3-buckets)

        violations (map (fn [bucket]
                          {:violation/rule-id :mf.rule/no-public-s3
                           :violation/pack-id  :miniforge/terraform-aws
                           :violation/severity :critical
                           :violation/message (str "S3 bucket '"
                                                  (:finding/resource-name bucket)
                                                  "' has public ACL")
                           :violation/location (:finding/location bucket)
                           :violation/auto-fixable? true
                           :violation/remediation "Set acl = \"private\" or use bucket policy"})
                       public-buckets)]

    {:passed? (empty? violations)
     :violations violations}))
```

### 3.2 Repair Function Signature

Policy rules MAY provide a repair function for auto-fixable violations:

```clojure
(defn repair-fn
  "Attempt to repair violations.

   Args:
     artifacts  - Vector of artifacts to repair
     violations - Violations to fix
     context    - Execution context

   Returns:
     {:repaired? boolean
      :artifacts [...]   ; Repaired artifacts
      :failures [...]}   ; Violations that couldn't be auto-fixed"

  [artifacts violations context]
  ...)
```

#### 3.2.1 Repair Function Contract

Repair functions MUST:

1. Return map with `:repaired?` boolean
2. Return repaired artifacts if successful
3. Return unresolved violations if unable to fix
4. Be idempotent (can be called multiple times safely)

Repair functions SHOULD:

- Fix violations in order of severity (critical first)
- Preserve artifact provenance (link repaired artifact to original)
- Emit events for repair attempts (see N3)

#### 3.2.2 Example Repair Function

```clojure
(defn no-public-s3-buckets-repair
  "Repairs S3 buckets with public ACLs"
  [artifacts violations context]

  (let [tf-plan (find-artifact-by-type artifacts :terraform-plan)
        code-artifact (find-artifact-by-type artifacts :code-changes)

        ;; For each violation, fix the code
        repaired-code (reduce
                       (fn [code violation]
                         (fix-s3-acl code
                                     (:violation/location violation)
                                     "private"))
                       (:artifact/content code-artifact)
                       violations)

        ;; Create repaired artifact
        repaired-artifact (assoc code-artifact
                                 :artifact/content repaired-code
                                 :artifact/provenance
                                 (assoc (:artifact/provenance code-artifact)
                                        :repaired-from (:artifact/id code-artifact)
                                        :repair-reason "Fixed public S3 buckets"))]

    {:repaired? true
     :artifacts [repaired-artifact]
     :failures []}))
```

### 3.3 Violation Schema

```clojure
{:violation/id uuid                ; REQUIRED: Unique violation ID
 :violation/rule-id keyword        ; REQUIRED: Rule that detected violation (§2.3)
 :violation/pack-id keyword        ; REQUIRED: Pack the rule was resolved from (§5.3)
 :violation/gate-id keyword        ; REQUIRED: Gate that ran this rule

 :violation/severity keyword       ; REQUIRED: canonical severity (§2.3.1)
 :violation/message string         ; REQUIRED: Human-readable description

 :violation/location               ; OPTIONAL: Where violation occurred
 {:file string
  :line long
  :column long
  :resource-type string            ; For infrastructure changes
  :resource-name string}

 :violation/auto-fixable? boolean  ; REQUIRED: Can this be auto-repaired?
 :violation/remediation string     ; REQUIRED: How to fix (human-readable)
 :violation/remediation-code string ; OPTIONAL: Machine-readable fix (diff, patch, etc.)

 :violation/context {...}          ; OPTIONAL: Additional context for debugging
 :violation/documentation-url string ; OPTIONAL: Link to docs

 :failure/class keyword}           ; REQUIRED when the violation reports a rule
                                   ;   that failed to execute (§3.5.1);
                                   ;   canonical class per N1 §5.3.3
```

`:violation/rule-id` is a keyword, matching `:rule/id` (§2.3). A violation that
cannot name its rule as a keyword cannot be joined back to the pack that
produced it, which is what §5.3 resolution and every downstream report depend
on.

`:violation/pack-id` is REQUIRED because a rule ID alone does not identify
which pack in the resolved set produced the finding once overlays (§2.5) and
severity overrides are in play. Two packs may legitimately surface the same
inherited rule at different severities.

**This section is the single definition of the violation schema.** Other specs
reference it; they MUST NOT restate it. Where a restatement exists and differs,
this section governs.

#### 3.3.1 Findings vs Recorded Violations

The schema above describes a violation **as recorded** — in the gate result,
the evidence bundle, and the event stream. A check function does not produce
that map directly, and MUST NOT be expected to.

Three fields are unknowable to a check function:

| Field | Why the runtime supplies it |
|-------|-----------------------------|
| `:violation/id` | Minting a UUID is impure and non-deterministic; §3.1.1 forbids both |
| `:violation/gate-id` | The gate invokes the rule; the rule does not know which one |
| `:violation/pack-id` | Determined by §5.3 resolution, which happens above the rule |

A check function therefore returns **findings**: violation maps carrying every
field it can know — at minimum `:violation/rule-id`, `:violation/severity`,
`:violation/message`, `:violation/auto-fixable?`, and
`:violation/remediation`, plus `:violation/location` where applicable.

The gate runtime MUST complete each finding into a conformant violation before
recording it, supplying the three fields above. A finding is not a violation
until completed; nothing outside the gate runtime observes an incomplete one.

The examples in §3.1.2 and §4.2 return findings, which is why they carry no
`:violation/id` or `:violation/gate-id`.

### 3.4 Validation Layer Taxonomy

Validation in miniforge occurs at multiple layers with distinct responsibilities.
This taxonomy defines the canonical ordering and ensures that failures are classified
and debugged at the correct layer.

| Layer | Name | When | What it Checks | Failure Behavior |
|-------|------|------|----------------|-----------------|
| L0 | **Syntax** | Ingestion / parsing | Schema conformance, encoding, data types, required fields | Reject input with parse error |
| L1 | **Semantic** | Pre-execution | Type correctness, referential integrity, constraint satisfaction, identifier validity | Block with structured violation and remediation |
| L2 | **Policy** | Gate evaluation | Organizational rules, security constraints, compliance requirements, semantic intent | Per-severity enforcement (N4 violation schema) |
| L3 | **Operational** | Runtime | Resource availability, tool health, circuit-breaker state, error budget, timeout budgets | Retry, degrade, or fail with `:failure.class/resource` or `:failure.class/timeout` |
| L4 | **Authorization** | Pre-capability | RBAC, trust level, autonomy level (N1 §5.6), capability scope (N10 §6) | Deny or escalate to human approval |

#### 3.4.1 Layer Ordering Invariant

Validation MUST be applied in layer order (L0 before L1, L1 before L2, etc.). A failure
at a lower layer MUST NOT be masked by a pass at a higher layer. This ensures:

- Syntax errors are caught before semantic analysis wastes resources
- Semantic issues are resolved before policy evaluation
- Policy violations are identified before operational checks
- Authorization is verified only after all other checks pass

#### 3.4.2 Layer-to-Spec Mapping

| Layer | Primary Spec | Implementation |
|-------|-------------|----------------|
| L0 | N1 (schema definitions), N10 §7.4 (tool response) | Implicit parsing and schema validation |
| L1 | N4 §4 (semantic intent), N6 (provenance integrity) | Check functions with `:semantic-intent` type |
| L2 | N4 §5 (policy rules) | Check functions with `:policy-validation` type |
| L3 | N10 §3.4 (tool operational semantics), N1 §5.5 (SLIs) | Runtime health and budget checks |
| L4 | N8 §2 (RBAC), N10 §6 (capability broker), N1 §5.6 (autonomy) | Capability and authorization checks |

Policy pack rules (§5) operate primarily at L1 and L2. Standard packs SHOULD document
which validation layer each rule targets.

### 3.5 Check Function Execution Semantics

§3.1.1 states what a conformant check function does. This section states what
the runtime does when one does not — which matters because a policy pack is
**code**, third parties author packs (§10), and §2.7.1 already assumes
untrusted material reaches the system.

#### 3.5.1 Fail-Closed

A check function that throws, times out, or returns a value not matching the
§3.1 shape MUST be treated as a **failure of that rule**, not as a pass and not
as an absent rule. The runtime MUST synthesize a **complete** violation per
§3.3 — it is the gate runtime, so unlike a check function it knows all three
fields of §3.3.1. Beyond the schema's requirements, the synthesized violation
MUST carry:

- `:violation/rule-id` — the rule that failed to execute
- `:violation/severity` — the rule's declared `:rule/severity`
- `:failure/class` — the canonical class per N1 §5.3.3
- `:violation/auto-fixable?` — `false`
- `:violation/message` — naming the execution failure rather than a policy finding

Treating an erroring check as a pass converts every crash into a silent
approval, which is the failure mode a policy gate exists to prevent. A rule
that cannot run has not been satisfied.

The synthesized violation MUST NOT be auto-repairable: a repair function keyed
to a rule whose check did not complete has no verified precondition to repair
against.

#### 3.5.2 Resource Bounds

Implementations MUST bound check function execution:

- **Timeout** — a per-rule wall-clock limit, default 5 seconds (matching the
  §9.1 p99 target). Exceeding it is a §3.5.1 failure with
  `:failure.class/timeout`.
- **Total gate budget** — a limit across all rules in a gate. Exhausting it
  fails the gate; it MUST NOT silently truncate the rule set, because a
  truncated gate reports a pass it did not evaluate.

Implementations MUST record which rules ran, which failed to run, and which
were never reached, so a partial gate is distinguishable from a complete one.

#### 3.5.3 Isolation

Check functions run with the privileges of the workflow. Implementations
SHOULD execute rules from packs below `:trusted` in a restricted context with
no network access, no filesystem writes outside a scratch path, and no ability
to invoke connectors or tools.

§3.1.1 already requires check functions to be pure and side-effect free, so a
conformant rule loses nothing under these restrictions. A rule that breaks when
isolated was not conformant.

Implementations MUST NOT grant a policy pack capabilities beyond those the
governed run itself holds (N1 §2.25). A pack is subject to the capability
model, never an exemption from it.

#### 3.5.4 Determinism

§3.1.1 requires determinism. Implementations SHOULD verify it for packs at or
above `:trusted` by re-executing a sampled rule against identical inputs and
comparing results. A rule that disagrees with itself MUST be reported —
non-determinism in a gate makes every downstream evidence claim unreproducible.

---

## 4. Semantic Intent Validation

### 4.1 Intent Types & Validation Rules

Semantic intent validation MUST enforce these rules:

| Intent Type | Creates | Updates        | Destroys | Notes                                  |
| ----------- | ------- | -------------- | -------- | -------------------------------------- |
| `:import`   | 0       | 0 (state-only) | 0        | Pure import, no infrastructure changes |
| `:create`   | >0      | Any            | 0        | Creating new resources                 |
| `:update`   | 0       | >0             | 0        | Modifying existing resources           |
| `:destroy`  | 0       | 0              | >0       | Removing resources                     |
| `:refactor` | 0       | 0              | 0        | Code/structure changes only            |
| `:migrate`  | >0      | 0              | >0       | Moving resources (create + destroy)    |

### 4.2 Semantic Intent Check Function

```clojure
(defn semantic-intent-check
  "Validates that actual behavior matches declared intent"
  [artifacts context]

  (let [;; Extract declared intent from workflow spec
        declared-intent (get-in context [:workflow/intent :intent/type])

        ;; Analyze implementation to determine actual behavior
        tf-plan (find-artifact-by-type artifacts :terraform-plan)
        scan-result (terraform-plan-scanner tf-plan context)

        creates (:total-creates (:metadata scan-result))
        updates (:total-updates (:metadata scan-result))
        destroys (:total-destroys (:metadata scan-result))

        ;; Infer actual behavior from changes
        actual-behavior (infer-intent creates updates destroys)

        ;; Validate match
        violations (when-not (intent-matches? declared-intent
                                             creates updates destroys)
                     [{:violation/rule-id :mf.rule/semantic-intent-mismatch
                       :violation/pack-id  :miniforge/core
                       :violation/severity :critical
                       :violation/message
                       (str "Declared intent is " declared-intent
                            " but actual behavior is " actual-behavior
                            " (creates: " creates
                            ", updates: " updates
                            ", destroys: " destroys ")")
                       :violation/auto-fixable? false
                       :violation/remediation
                       (str "Review implementation. Either:\n"
                            "1. Fix implementation to match " declared-intent " intent\n"
                            "2. Update intent declaration to " actual-behavior)}])]

    {:passed? (empty? violations)
     :violations violations
     :metadata {:declared-intent declared-intent
                :actual-behavior actual-behavior
                :creates creates
                :updates updates
                :destroys destroys}}))
```

### 4.3 Intent Inference Algorithm

```clojure
(defn infer-intent
  "Infer intent type from resource change counts"
  [creates updates destroys]

  (cond
    ;; No changes at all
    (and (zero? creates) (zero? updates) (zero? destroys))
    :refactor

    ;; Only creates
    (and (pos? creates) (zero? updates) (zero? destroys))
    :create

    ;; Only updates
    (and (zero? creates) (pos? updates) (zero? destroys))
    :update

    ;; Only destroys
    (and (zero? creates) (zero? updates) (pos? destroys))
    :destroy

    ;; Creates and destroys (migration)
    (and (pos? creates) (zero? updates) (pos? destroys))
    :migrate

    ;; Mixed operations (unclear intent)
    :else
    :mixed))
```

### 4.4 Terraform-Specific Intent Validation

#### 4.4.1 Terraform Plan Parsing

Implementations MUST parse Terraform plan output to categorize changes:

```text
# Example Terraform plan output

Terraform will perform the following actions:

  # aws_s3_bucket.example will be created
  + resource "aws_s3_bucket" "example" {
      + bucket = "my-bucket"
      ...
    }

  # aws_instance.web will be updated in-place
  ~ resource "aws_instance" "web" {
      ~ instance_type = "t2.micro" -> "t2.small"
      ...
    }

  # aws_db_instance.main will be replaced
-/+ resource "aws_db_instance" "main" {
      ...
    }

Plan: 2 to add, 1 to change, 1 to destroy.
```

Parser MUST extract:

- Creates: `+` prefix or `Plan: X to add`
- Updates: `~` prefix or `Plan: X to change`
- Destroys: `-` prefix or `Plan: X to destroy`
- Recreates: `-/+` or `+/-` (counts as both create and destroy)
- Imports: `import` blocks (state-only, not in plan changes)

#### 4.4.2 Import Intent Validation

For `:import` intent, implementations MUST verify:

1. **No resource blocks** - Only `import` blocks present
2. **State-only changes** - `terraform plan` shows 0 infrastructure changes
3. **No creates/updates/destroys** - All change counts are 0

Example valid import:

```hcl
# Valid for :import intent
import {
  to = aws_db_instance.main
  id = "acme-prod-postgres"
}
```

Example invalid import (violates intent):

```hcl
# INVALID for :import intent - contains resource block
import {
  to = aws_db_instance.main
  id = "acme-prod-postgres"
}

resource "aws_db_instance" "main" {
  # This creates a new resource - violates IMPORT intent!
  instance_class = "db.t3.micro"
  ...
}
```

### 4.5 Kubernetes-Specific Intent Validation

For Kubernetes workflows, implementations MUST parse `kubectl diff` or `kubectl apply --dry-run` output:

```yaml
# Example kubectl diff output

# New resource (CREATE)
+apiVersion: v1
+kind: Service
+metadata:
+  name: my-service

# Modified resource (UPDATE)
 apiVersion: apps/v1
 kind: Deployment
 metadata:
   name: web
 spec:
-  replicas: 2
+  replicas: 3
```

Parser MUST detect:

- Creates: New resources in manifest not in cluster
- Updates: Changed fields in existing resources
- Destroys: Resources in cluster not in manifest (if using `--prune`)

---

## 5. Standard Policy Packs

### 5.1 Required Standard Packs

Implementations SHOULD provide these standard policy packs.

**Identifier convention.** Each pack below is introduced by its short name for
readability. The short name is not the identifier. Canonical forms are
mechanical:

| Short name (display only) | Canonical identifier |
|---------------------------|----------------------|
| `foundations` | `:pack/id :miniforge/foundations` |
| `no-hardcoded-secrets` | `:rule/id :mf.rule/no-hardcoded-secrets` |

The **ID:** line under each pack heading below carries the short name for
continuity with earlier drafts. It is a label, not the identifier.

That is: a standard pack's `:pack/id` is `:miniforge/<short-name>`, and its
rules are `:mf.rule/<rule-name>`, per §2.2 and §2.3. Short names are display
sugar in this section only; a pack that ships bare strings as identifiers is
non-conformant.

Because §2.3 makes rule IDs **globally unique**, a rule name may appear in at
most one standard pack. Two packs needing a similar check MUST use distinct
names that say what each checks.

#### Standard Pack Registry

| § | Pack ID | Status | Governs | Defined by |
|---|---------|--------|---------|------------|
| 5.1.1 | `:miniforge/foundations` | SHOULD | Baseline security and hygiene | N4 |
| 5.1.2 | `:miniforge/terraform-aws` | SHOULD | AWS Terraform validations | N4 |
| 5.1.3 | `:miniforge/kubernetes` | SHOULD | Kubernetes manifest validations | N4 |
| 5.1.4 | `:miniforge/task-scope` | RECOMMENDED for DAG runs | Node capability contracts | N2 §13.6 |
| 5.1.5 | `:miniforge/opsv-governance` | REQUIRED for OPSV | Experiment and actuation gates | N7 §5 |
| 5.1.6 | `:miniforge/control-action-governance` | REQUIRED for control actions | RBAC and approval gates | N8 §2.3, §3 |
| 5.1.7 | `:miniforge/external-pr-evaluation` | REQUIRED for external PRs | Read-only PR policy evaluation | N9 §8 |
| 5.1.8 | `:miniforge/pack-trust` | RECOMMENDED | Signature, publisher, trust level | N1 §2.24 |
| 5.1.9 | `:miniforge/capability-grant` | REQUIRED for pack runs | Capability declaration and scope | N1 §2.25 |
| 5.1.10 | `:miniforge/pack-high-risk-action` | RECOMMENDED for production | High-risk pack actions | N1 §2.26 |
| 5.1.11 | `:miniforge/financial-statement-validation` | REQUIRED for DF financials | Accounting invariants | Data Foundry N4 |
| ↳ | `:miniforge/macro-series-integrity` | RECOMMENDED for DF macro | Distribution and continuity | Data Foundry N4 |
| ↳ | `:miniforge/valuation-consistency` | REQUIRED for DF valuation | Derivation consistency | Data Foundry N4 |
| ↳ | `:miniforge/time-series-completeness` | REQUIRED for DF time series | Period completeness | Data Foundry N4 |

`↳` marks a continuation of the section above it: §5.1.11 defines four Data
Foundry packs in one section rather than four subsections.

The **Status** column is normative and states when a pack is obligatory rather
than optional. A pack marked REQUIRED for a context MUST be bound (§5.4) to the
gates of that context; per N4.RB.5 an unbound gate fails closed, so omitting a
required pack does not yield a silent pass.

The **Defined by** column names the spec that owns each pack's rule semantics.
N4 owns the pack and rule _contract_; extension specs own the rules they
contribute. A rule's normative content lives with its owning spec, and this
section MUST NOT contradict it.

#### 5.1.1 Foundations Pack

**ID:** `foundations`
**Purpose:** Basic security and best practices

Rules:

- No hardcoded secrets
- No public resources without justification
- Require tags on all resources
- Enforce naming conventions

#### 5.1.2 Terraform-AWS Pack

**ID:** `terraform-aws`
**Purpose:** AWS-specific Terraform validations

Rules:

- No public S3 buckets
- Require encryption at rest (RDS, S3, EBS)
- No `0.0.0.0/0` ingress rules
- Require VPC for all resources
- No unapproved instance types

#### 5.1.3 Kubernetes Pack

**ID:** `kubernetes`
**Purpose:** Kubernetes manifest validations

Rules:

- No `latest` image tags
- Require resource limits
- No privileged containers
- Require liveness/readiness probes
- No host network mode

#### 5.1.4 Task Scope Pack

**ID:** `task-scope`
**Purpose:** Enforce node capability contracts during DAG execution (see N2 §13.6)

Rules:

- `require-task-capability-declaration` (severity: medium)
  - WARN if a task node in a DAG has no `:task/capabilities` declared
  - Tasks without contracts run with full archetype defaults (less safe)
- `enforce-tool-scope` (severity: critical)
  - FAIL if agent invokes a tool not listed in `:cap/tools`
  - Implementations MUST intercept tool calls and validate against contract
- `enforce-path-scope` (severity: critical)
  - FAIL if agent writes/reads files outside `:cap/paths` glob patterns
  - Implementations MUST validate file operations against contract
- `enforce-knowledge-scope` (severity: high)
  - FAIL if agent accesses knowledge packs not listed in `:cap/knowledge`
- `enforce-timeout` (severity: high)
  - FAIL if agent execution exceeds `:cap/timeout-ms`
  - Implementations MUST terminate agent and transition task to `:failed`

This pack is OPTIONAL for single-task workflows but RECOMMENDED for DAG execution
with multiple concurrent agents.

#### 5.1.5 OPSV Gates Pack (N7)

**ID:** `opsv-governance`
**Purpose:** Govern operational policy synthesis experiments and actuation (see N7 §5)

Gates:

- `instrumentation-gate` (severity: critical)
  - FAIL if required metric/trace signals do not exist or are unreliable
  - MUST validate signal availability before experiment execution
- `environment-gate` (severity: critical)
  - FAIL if target environments are not in the allowed set or outside time windows
  - Production targets MUST require explicit allowlisting
- `blast-radius-gate` (severity: critical)
  - FAIL if proposed changes exceed configured max replicas delta, max node delta, or namespace limits
- `abort-gate` (severity: critical)
  - FAIL if abort triggers are not configured before experiment execution
  - MUST verify error budget burn, saturation, and tail latency thresholds are set
- `actuation-gate` (severity: critical)
  - FAIL if `APPLY_ALLOWED` is requested but not explicitly enabled in policy pack
  - `APPLY_ALLOWED` MUST be disabled by default
  - Apply requires explicit per-service allowlist
- `evidence-completeness-gate` (severity: high)
  - FAIL if evidence bundle is missing required fields before actuation
  - MUST verify experiment pack hash, environment fingerprint, metric snapshots

If any gate fails, OPSV MUST produce remediation guidance as machine-readable output
and human-readable summary.

#### 5.1.6 Control Action Governance (N8)

**ID:** `control-action-governance`
**Purpose:** RBAC and policy gates governing control actions (see N8 §2.3, §3)

Rules:

- `require-rbac-authorization` (severity: critical)
  - FAIL if control action requester lacks required RBAC role for the action type
  - MUST validate against RBAC schema (see N8 §2.3)
- `require-multi-party-approval` (severity: critical)
  - FAIL if High/Critical risk actions proceed without configured number of approvers
  - Requester MUST NOT be their own approver (when `require-different-principal?` is true)
- `control-action-audit` (severity: high)
  - FAIL if control action is not audit-logged with pre-state, post-state, and justification
- `control-action-risk-classification` (severity: medium)
  - WARN if action risk level is not classified per N8 §3.1 risk levels
  - MUST enforce justification requirement for High/Critical actions

RBAC role schema for control action governance:

```clojure
{:rbac/role keyword
 :rbac/permissions
 {:workflows {:pause boolean :resume boolean :retry boolean
              :cancel boolean :rollback boolean :force-complete boolean}
  :agents {:quarantine boolean :adjust-budget boolean :inject-context boolean}
  :fleet {:emergency-stop boolean :drain boolean :scale boolean}
  :approvals {:gate-override boolean :budget-escalation boolean}}
 :rbac/constraints
 {:workflow-patterns [string ...]
  :time-windows [{:start inst :end inst} ...]
  :require-mfa? boolean}}
```

#### 5.1.7 External PR Evaluation (N9)

**ID:** `external-pr-evaluation`
**Purpose:** Policy evaluation for external PRs (see N9 §8)

Rules:

- `external-pr-policy-check` (severity: configurable)
  - Evaluate existing policy pack rules against external PR diffs
  - Context differs from workflow gate evaluation: artifacts are the PR diff and
    metadata, context is PR author/repo/labels/base branch (not workflow spec)
  - MUST NOT invoke repair functions (N4 §3.2) — external PRs are read-only unless adopted
- `policy-evaluation-trigger` (severity: high)
  - MUST run policy evaluation on: PR opened, PR synchronized (new commits),
    check run completed, configuration changed
- `provider-feedback-governance` (severity: medium)
  - If `:policies/mode` is `:enforcing`, MAY publish outcomes as provider-native
    signals (e.g., GitHub Check Runs) with stable check names per policy pack
  - If `:policies/mode` is `:advisory`, MUST NOT publish enforcing checks
  - MUST respect automation tier constraints (N9 §10)

Policy result schema for external PR evaluation:

```clojure
{:policy/overall keyword              ; :pass, :fail, :unknown
 :policy/results
 [{:rule/id keyword                   ; From N4 policy pack rule (§2.3)
   :pack/id keyword                   ; Pack the rule resolved from (§5.3)
   :rule/outcome keyword              ; :pass, :fail, :warn, :skip, :unknown
   :rule/message string
   :rule/evidence-id uuid}]           ; N6 artifact with full details
 :policy/evaluated-at inst
 :policy/packs-applied
 [{:pack/id      keyword
   :pack/version string}]}
```

#### 5.1.8 Pack Trust Gate

**ID:** `pack-trust`
**Purpose:** Enforce trust requirements when installing and running Workflow Packs (see N1 §2.24)

Rules:

- `require-signature-verification` (severity: high)
  - WARN if a pack is installed without a valid signature
  - Implementations SHOULD allow configuring this to FAIL for production environments
- `enforce-publisher-allowlist` (severity: critical)
  - FAIL if pack publisher is not in the configured allowlist (when allowlist is enabled)
  - Implementations MUST support publisher allowlists in pack trust configuration
- `enforce-minimum-trust-level` (severity: high)
  - FAIL if pack trust level is below the configured minimum for the environment
  - Default minimum: `:untrusted` (no restriction); configurable per environment

This pack is RECOMMENDED for all Workflow Pack installations.

#### 5.1.9 Capability Grant Gate

**ID:** `capability-grant`
**Purpose:** Enforce capability declarations and grants for Workflow Pack runs (see N1 §2.25)

Rules:

- `require-pack-capability-declaration` (severity: critical)
  - FAIL if a Workflow Pack does not declare its required capabilities in the manifest
- `enforce-deny-default-writes` (severity: critical)
  - FAIL if a write capability (`*.write`) is invoked without explicit user grant
  - Write capabilities MUST default to denied; read capabilities MAY default to granted
- `enforce-capability-scope` (severity: critical)
  - FAIL if a pack invokes a connector action not covered by its declared and granted capabilities
  - Implementations MUST intercept connector actions and validate against the grant set
- `require-re-approval-on-upgrade` (severity: high)
  - FAIL if a pack update increases required capabilities without re-approval
  - Capability changes MUST be presented to the user before the update takes effect

This pack is REQUIRED for all Workflow Pack runs.

#### 5.1.10 High-Risk Pack Action Gate

**ID:** `pack-high-risk-action`
**Purpose:** Govern high-risk actions triggered by pack runs (see N1 §2.26)

Rules:

- `require-justification-for-writes` (severity: medium)
  - WARN if a pack run performs write actions without a justification record
- `enforce-production-restrictions` (severity: critical)
  - FAIL if a pack run targets production resources without explicit approval
  - Production targets MUST be defined in pack trust configuration
- `audit-all-connector-actions` (severity: high)
  - FAIL if connector actions during a pack run are not logged in the evidence bundle
  - All connector actions (read and write) MUST be recorded with timestamps and principals

This pack is RECOMMENDED for production environments.

#### 5.1.11 Data Foundry Quality Packs

**Purpose:** Register Data Foundry data quality policy packs as standard packs in the Core registry. These packs extend
  the Core N4 gate model with data-specific quality validation. See Data Foundry N4 for full specifications.

**financial-statement-validation** (severity: critical)
**ID:** `financial-statement-validation`

Rules:

- `accounting-equation-balance` (severity: critical)
  - FAIL if Assets ≠ Liabilities + Equity beyond 0.01% materiality tolerance per GAAP
- `gaap-receivables-non-null` (severity: critical)
  - FAIL if accounts receivable contains null values in financial statement datasets
- `inventory-non-negative` (severity: high)
  - FAIL if inventory quantities are negative, indicating data corruption
- `material-account-non-null` (severity: critical)
  - FAIL if total-assets, total-liabilities, or total-equity are null

This pack is REQUIRED for all Data Foundry pipelines producing financial statement datasets.

**macro-series-integrity** (severity: medium)
**ID:** `macro-series-integrity`

Rules:

- `distribution-drift-detection` (severity: medium)
  - WARN if z-score exceeds 2.5σ against trailing baseline for macro time series
- `row-count-stability` (severity: medium)
  - WARN if row count deviates > ±5% from rolling average
- `temporal-continuity` (severity: high)
  - FAIL if time series contains missing periods or non-monotonic timestamps

This pack is RECOMMENDED for Data Foundry pipelines processing macroeconomic data.

**valuation-consistency** (severity: critical)
**ID:** `valuation-consistency`

Rules:

- `cross-table-sum-check` (severity: critical)
  - FAIL if derived valuation totals diverge from component sums
- `derivation-chain-validation` (severity: high)
  - FAIL if computed values cannot be reproduced from declared inputs
- `timestamp-ordering` (severity: high)
  - FAIL if valuation timestamps are non-monotonic or predate input timestamps

This pack is REQUIRED for Data Foundry pipelines producing valuation datasets.

**time-series-completeness** (severity: critical)
**ID:** `time-series-completeness`

Rules:

- `no-missing-periods` (severity: critical)
  - FAIL if expected time periods are absent from the dataset
- `partition-key-completeness` (severity: high)
  - FAIL if partition keys have gaps
- `weekend-holiday-gap-validation` (severity: medium)
  - WARN if gaps exist on non-holiday business days (financial calendars)

This pack is REQUIRED for Data Foundry pipelines publishing time series datasets.

### 5.2 Pack Discovery & Installation

Implementations SHOULD support:

```bash
# List available packs
miniforge policy list

# Install pack from registry
miniforge policy install terraform-aws

# Install custom pack from file
miniforge policy install ./custom-pack.edn

# Show pack details
miniforge policy show terraform-aws
```

### 5.3 Pack Resolution & Precedence

A gate rarely runs one pack. Overlays (§2.5), bundled packs, repo-local packs,
and organization packs can all bind to the same gate and disagree about a rule.
This section fixes what the gate actually executes.

#### 5.3.1 The Resolved Rule Set

Given a set of bound packs, implementations MUST compute a **resolved rule set**
before executing any check function:

1. Expand each bound pack's `:pack/extends` chain depth-first, in declaration
   order, producing its own effective rule set per §2.5.
2. Union the expanded sets across bound packs, keyed by `:rule/id`.
3. Where the same `:rule/id` appears from more than one pack, apply §5.3.2.
4. Drop rules whose effective `:rule/enabled?` is false.

Resolution MUST be deterministic: the same bound pack set and versions MUST
produce the same resolved rule set, independent of load order or map iteration.
Resolution MUST complete before the first check function runs, so a resolution
error is a pack error rather than a mid-gate failure.

#### 5.3.2 Conflict Resolution

When one `:rule/id` resolves from multiple packs:

| Field | Rule |
|-------|------|
| `:rule/severity` | The **most severe** value wins (§2.3.1 ordering) |
| `:rule/enabled?` | `false` wins — any pack may disable, none may force-enable |
| `:rule/check-fn` | The **owning** pack's — the pack that first defined the rule, not an overlay |
| everything else | The owning pack's |

Severity escalates and never de-escalates across packs. An organization pack
that raises `:medium` to `:critical` takes effect; one that lowers `:critical`
to `:low` does not. Overlay `:pack/overrides` (§2.5) are the sanctioned way to
lower a severity, and apply only within their own pack's expansion — an overlay
cannot weaken a rule for a pack it does not extend.

Implementations MUST record the winning pack in `:violation/pack-id` (§3.3) and
MUST make the full resolution — every pack that contributed, and what each
proposed — available in the evidence bundle (§5.5).

#### 5.3.3 Version Conflicts

If two bound packs require incompatible versions of the same extended pack,
resolution MUST fail with a violation naming both requirements. Implementations
MUST NOT silently pick one. This is the same failure the `pack-dependency-validation`
rule detects at load time (§2.7.2); §5.3 restates it as a resolution obligation
because binding can introduce a conflict that neither pack exhibits alone.

### 5.4 Gate Binding

N2 §6.4 defines a gate; this section defines how a gate acquires its rules.

A gate binding is:

```clojure
{:gate/id       keyword              ; REQUIRED: the gate being bound (N2 §6.4)
 :binding/packs                      ; REQUIRED: packs bound to this gate
 [{:pack/id      keyword
   :pack/version string}             ; exact version or range (§7.2)
  ...]
 :binding/rule-filter                ; OPTIONAL: narrow the resolved set
 {:filter/categories [keyword ...]   ; only rules in these taxonomy categories
  :filter/phase      keyword         ; only rules whose :rule/phase matches
  :filter/applies-to [keyword ...]}} ; only rules matching artifact types
```

Rules:

- A gate with no binding MUST fail closed: it MUST NOT pass by virtue of having
  no rules to run. An unbound policy gate is a configuration error, not a pass.
- `:binding/rule-filter` narrows the resolved set (§5.3.1); it MUST NOT add
  rules, and MUST NOT alter severity.
- A rule whose `:rule/applies-to` matches none of the gate's artifacts is
  `:skip`, not `:pass`. The distinction is what makes coverage auditable.
- The binding, its resolved rule set, and the pack versions MUST be recorded in
  the evidence bundle (§5.5).

### 5.5 Events and Evidence

Design principle 5 (§1.1) requires policy checks to be observable. This section
names the obligations rather than leaving them to the reader.

**Events (N3).** A gate execution MUST emit `gate/started`, and exactly one of
`gate/passed` or `gate/failed`, per N3 §3.9. Implementations MUST populate
`:gate/violations` on `gate/failed` with violations conforming to §3.3.

**Evidence (N6).** The evidence bundle for a workflow MUST record, for each
gate execution:

1. The gate binding and resolved rule set (§5.4), including every pack ID and
   exact version — a range is not sufficient for reproducibility.
2. Every violation (§3.3), including waived ones (§6.3).
3. Each pack's content hash, so a later reader can verify which bytes ran.
4. Any override or waiver, with its justification (§6.3).

A gate result that cannot be reproduced from its evidence — because a version
range was recorded rather than a resolved version, or because a pack hash is
absent — does not satisfy this section.

---

## 6. Remediation UX Contract

### 6.1 Remediation Message Format

All violations MUST provide remediation guidance in this format:

```text
[SEVERITY] Rule: [RULE_NAME]

Problem:
  [VIOLATION_MESSAGE]

Location:
  [FILE]:[LINE] ([RESOURCE_TYPE].[RESOURCE_NAME])

How to fix:
  [REMEDIATION_GUIDANCE]

[OPTIONAL: Auto-fix available - run `miniforge policy repair <workflow-id>`]

Docs: [DOCUMENTATION_URL]
```

Example:

```text
[CRITICAL] Rule: No Public S3 Buckets

Problem:
  S3 bucket 'my-data-bucket' has public ACL, exposing data to internet

Location:
  terraform/s3.tf:45 (aws_s3_bucket.data)

How to fix:
  1. Change ACL to private:
     acl = "private"

  2. Or use bucket policy for controlled access:
     resource "aws_s3_bucket_policy" "data" {
       ...
     }

Auto-fix available - run `miniforge policy repair workflow-abc123`

Docs: https://miniforge.ai/policies/no-public-s3
```

### 6.2 Machine-Readable Remediation

For auto-fixable violations, implementations SHOULD provide machine-readable repairs:

```clojure
{:violation/remediation-code
 {:type :diff
  :file "terraform/s3.tf"
  :patch "
- acl = \"public-read\"
+ acl = \"private\"
"}}
```

Or:

```clojure
{:violation/remediation-code
 {:type :replacement
  :file "terraform/s3.tf"
  :line 45
  :old-value "acl = \"public-read\""
  :new-value "acl = \"private\""}}
```

### 6.3 Remediation Actions

When violations occur, implementations MUST offer these actions:

1. **Auto-repair** (if `auto-fixable?` is true)
   - Attempt automatic fix
   - Re-run validation
   - Proceed if successful

2. **Manual fix** (if not auto-fixable)
   - Show remediation guidance
   - Wait for user to fix
   - Allow re-running validation

3. **Override** (see §6.3.1)
   - Record a Waiver
   - Proceed with the violation still visible

4. **Cancel** (always available)
   - Stop workflow
   - Preserve partial evidence

#### 6.3.1 Override and Waiver

An override is permitted only when **both** hold:

1. The gate declares `:gate/allow-override? true` (N2 §6.4), and
2. The violation's `:violation/severity` is `:medium` or less (§2.3.1).

`:critical` and `:high` violations MUST NOT be overridden through this path.
Bypassing them is an authorization decision, not a policy decision: it requires
the multi-party approval surface of N8 §3, and MUST be recorded there.

An override MUST produce a **Waiver** as defined in
N5-delta-supervisory-control-plane §3.1 — `:waiver/id`, `:waiver/evaluation-id`,
`:waiver/violations` (the rule IDs waived), `:waiver/actor`, `:waiver/reason`,
`:waiver/timestamp`. A waiver with no `:waiver/reason` is not a waiver;
implementations MUST reject it.

Per that spec, a Waiver MUST NOT mutate the original evaluation. The gate
result stays failed and the violation stays present; the waiver records that
someone accepted it. Implementations MUST NOT report a waived gate as passing,
in evidence, in events, or in any projection — "waived" and "passed" are
different facts, and collapsing them destroys the audit trail the waiver exists
to create.

---

## 7. Policy Pack Versioning

### 7.1 Semantic Versioning

Policy packs MUST follow semantic versioning (MAJOR.MINOR.PATCH):

- **MAJOR** - Breaking changes (rules removed, severity increased)
- **MINOR** - Additions (new rules added, severity decreased)
- **PATCH** - Bug fixes (rule logic fixes, no behavior change)

### 7.2 Version Compatibility

Implementations MUST:

1. Record policy pack version in evidence bundle
2. Support version ranges (e.g., `terraform-aws@^1.0.0`)
3. Warn on major version mismatches
4. Allow pinning exact versions for reproducibility

### 7.3 Pack Update Protocol

```bash
# Check for updates
miniforge policy outdated

# Update packs to latest compatible versions
miniforge policy update

# Update to specific version
miniforge policy update terraform-aws@2.0.0
```

---

## 8. Policy Pack Signature & Verification

### 8.1 Signature Requirements

For trusted policy packs, implementations MAY require cryptographic signatures:

```clojure
{:pack/signature "base64-encoded-ed25519-signature"  ; over §8.1.1 bytes
 :pack/signed-by "base64-encoded-ed25519-public-key" ; publisher key
 :pack/signed-at #inst "2026-08-05T00:00:00Z"}
```

These are three flat fields on the pack map, matching `:pack/signature string`
in §2.2. Earlier drafts of this section showed a nested map; a pack written
that way fails §2.2 validation. The algorithm is Ed25519; a future algorithm
change is a MAJOR pack-format change, not a per-pack field.

#### 8.1.1 What Is Signed

A signature is unverifiable unless both parties agree on the exact bytes. The
signed payload MUST be the pack's **canonical serialization**:

1. Take the pack map, less `:pack/signature` and any signature metadata
   carried alongside it (`:pack/signed-by`, `:pack/signed-at`).
2. Serialize as EDN with map keys sorted by their printed representation, no
   insignificant whitespace, and UTF-8 encoding.
3. The resulting byte sequence is the signed payload. Ed25519 signs it
   directly; implementations MUST NOT pre-hash and sign a digest instead.

The pack **content hash** referenced by §5.5 and N1 §2.10.4.1 is a digest of
this same byte sequence. It identifies the pack; it is not what the signature
is computed over. Conflating the two produces signatures that verify in one
implementation and fail in another.

Implementations MUST NOT sign a pretty-printed or reader-dependent rendering.
Two implementations that serialize the same pack MUST produce identical bytes,
or signatures will not survive the trip between them.

`:rule/check-fn` and `:rule/repair-fn` are functions. Where a pack is
distributed as data, these are references (symbols or pack-relative paths) and
serialize as such. A pack whose signed form cannot round-trip to the executed
form is non-conformant — signing the manifest while shipping unsigned code
verifies nothing that matters.

### 8.2 Verification Protocol

Before executing policy pack:

1. Recompute the canonical serialization (§8.1.1)
2. Verify the signature over those bytes against the trusted public key
3. Check the signature timestamp against the key's validity window
4. Confirm the publisher is permitted for this pack (§5.1.8)
5. Warn on unsigned packs, or fail where policy requires signatures

Verification failure MUST prevent execution of the pack. Implementations MUST
NOT execute a pack whose signature is present and invalid — a broken signature
is a stronger signal than no signature, and MUST NOT be downgraded to the
unsigned path.

#### 8.2.1 Trust Roots

Signature verification requires a public key the verifier already trusts.
Implementations MUST maintain an explicit set of trusted publisher keys, and
MUST NOT accept a key supplied by the pack being verified. A pack that carries
its own verification key is self-certifying and establishes nothing.

Key distribution and rotation are deployment concerns and out of scope here.
The requirement is only that the trust root is configured out-of-band and is
auditable.

---

## 9. Conformance & Testing

### 9.1 Policy Pack Conformance

Implementations MUST validate:

1. **Schema compliance** - All required fields present
2. **Check function contract** - Returns correct shape
3. **Determinism** - Same inputs → same outputs
4. **Performance** - Check functions complete in <5 seconds (p99)

### 9.2 Semantic Intent Test Cases

Conformance tests MUST verify correct detection:

```clojure
;; Test: IMPORT intent with creates → violation
{:intent :import
 :creates 3 :updates 0 :destroys 0}
→ {:passed? false :violations [...]}

;; Test: IMPORT intent with 0 changes → pass
{:intent :import
 :creates 0 :updates 0 :destroys 0}
→ {:passed? true :violations []}

;; Test: CREATE intent with creates → pass
{:intent :create
 :creates 5 :updates 2 :destroys 0}
→ {:passed? true :violations []}

;; Test: UPDATE intent with creates → violation
{:intent :update
 :creates 1 :updates 3 :destroys 0}
→ {:passed? false :violations [...]}
```

### 9.3 Repair Function Tests

Conformance tests MUST verify:

1. **Idempotence** - Repairing twice produces same result
2. **Correctness** - Repair actually fixes violation
3. **Provenance** - Repaired artifact links to original

### 9.4 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec, so a conformance suite can cite what it tests and a gap analysis can cite
what is missing. IDs are never reused; a withdrawn requirement is marked
withdrawn, not deleted.

#### Pack and rule structure

| ID | Level | Requirement |
|----|-------|-------------|
| N4.PK.1 | MUST | Packs, rules, taxonomies, and mappings conform to the schemas of §2.1–§2.5. |
| N4.PK.2 | MUST | `:pack/id` and `:rule/id` are namespaced keywords, never strings (§2.2, §2.3). |
| N4.PK.3 | MUST | `:rule/id` is globally unique across all loaded packs (§2.3). |
| N4.PK.4 | MUST | Reject a pack whose `:rule/severity` is outside the canonical enum (§2.3.1). |
| N4.PK.5 | MUST | Normalize legacy `:error` / `:warning` at load and not carry them past it (§2.3.1). |
| N4.PK.6 | MUST | Reject a pack referencing a `:category/id` absent from its resolved taxonomy (§2.1.1). |
| N4.PK.7 | MUST | Honor `:taxonomy/min-version` compatibility per §2.1.1. |
| N4.PK.8 | MUST | Apply overlay resolution in the order of §2.5, overriding only severity and enabled. |

#### Execution

| ID | Level | Requirement |
|----|-------|-------------|
| N4.EX.1 | MUST | Check functions return the §3.1 shape; violations conform to §3.3. |
| N4.EX.2 | MUST | Treat a throwing, timing-out, or malformed check as a rule failure, never a pass (§3.5.1). |
| N4.EX.3 | MUST | Carry `:failure/class` on a synthesized execution-failure violation (§3.5.1, N1 §5.3.3). |
| N4.EX.4 | MUST NOT | Auto-repair a rule whose check did not complete (§3.5.1). |
| N4.EX.5 | MUST | Bound per-rule and per-gate execution, and record rules that did not run (§3.5.2). |
| N4.EX.6 | MUST NOT | Grant a pack capabilities beyond those the governed run holds (§3.5.3). |
| N4.EX.7 | MUST | Apply validation layers in L0→L4 order without masking a lower-layer failure (§3.4.1). |
| N4.EX.8 | MUST | Repair functions are idempotent and preserve provenance (§3.2.1). |

#### Resolution and binding

| ID | Level | Requirement |
|----|-------|-------------|
| N4.RB.1 | MUST | Compute the resolved rule set before executing any check (§5.3.1). |
| N4.RB.2 | MUST | Resolution is deterministic given the same bound packs and versions (§5.3.1). |
| N4.RB.3 | MUST | On conflict, most-severe severity wins and `false` enabled wins (§5.3.2). |
| N4.RB.4 | MUST | Fail resolution on incompatible version requirements; never pick one silently (§5.3.3). |
| N4.RB.5 | MUST | A gate with no binding fails closed (§5.4). |
| N4.RB.6 | MUST | A rule matching no artifact is `:skip`, not `:pass` (§5.4). |
| N4.RB.7 | MUST | Record binding, resolved set, exact versions, and pack hashes in evidence (§5.5). |

#### Enforcement and override

| ID | Level | Requirement |
|----|-------|-------------|
| N4.EN.1 | MUST | Enforce per-severity blocking per §2.3.1. |
| N4.EN.2 | MUST NOT | Override a `:critical` or `:high` violation via the §6.3 path (§6.3.1). |
| N4.EN.3 | MUST | Produce a Waiver with a justification for every override (§6.3.1). |
| N4.EN.4 | MUST NOT | Report a waived gate as passing in any surface (§6.3.1). |
| N4.EN.5 | MUST | Emit `gate/started` and exactly one of `gate/passed` / `gate/failed` (§5.5, N3 §3.9). |

#### Trust and signatures

| ID | Level | Requirement |
|----|-------|-------------|
| N4.TR.1 | MUST | Sign and verify over the canonical serialization of §8.1.1. |
| N4.TR.2 | MUST NOT | Execute a pack whose signature is present and invalid (§8.2). |
| N4.TR.3 | MUST NOT | Accept a verification key supplied by the pack being verified (§8.2.1). |
| N4.TR.4 | MUST | Treat untrusted content as data, not instruction authority (§2.7.1). |

### 9.5 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Severity round-trip** — a rule's `:rule/severity` is the severity its
   violations carry, unchanged, through gate result and evidence (N4.PK.4).
2. **Legacy normalization** — a pack authored with `:error` / `:warning` loads,
   normalizes, and never exposes a legacy value downstream (N4.PK.5).
3. **Resolution determinism** — the same bound packs resolve identically across
   repeated runs and shuffled load orders (N4.RB.2).
4. **Conflict precedence** — a pack raising a severity takes effect; one
   lowering it outside its own overlay does not (N4.RB.3).
5. **Fail-closed execution** — with a check function forced to throw and
   another to hang, the gate fails, both rules report violations carrying
   `:failure/class`, and neither is auto-repaired (N4.EX.2, N4.EX.3, N4.EX.4).
6. **Unbound gate** — a policy gate with no binding fails rather than passing
   (N4.RB.5).
7. **Waiver visibility** — a waived gate reports as waived, not passing, in
   evidence, events, and projections (N4.EN.4).
8. **Signature canonicalization** — two independent serializations of the same
   pack produce identical signed bytes, and a mutated pack fails verification
   (N4.TR.1, N4.TR.2).
9. **Semantic intent matrix** — every row of §4.1 is exercised in both the
   matching and violating direction (§9.2).

---

## 10. Policy Pack Distribution

### 10.1 Pack Registry

Implementations MAY support a policy pack registry:

- **Community packs** - Open-source packs from community
- **Organization packs** - Private packs for enterprise (see Enterprise roadmap)
- **Verified packs** - Packs reviewed and signed by miniforge team

### 10.2 Pack Sharing

Users MAY share policy packs:

```bash
# Export pack
miniforge policy export terraform-aws > terraform-aws.edn

# Import pack
miniforge policy import terraform-aws.edn
```

---

## 11. Example Policy Pack

### 11.1 Complete Example: A Third-Party Pack

```clojure
{:pack/id      :acme/terraform-foundations
 :pack/version "1.0.0"
 :pack/title   "Acme Terraform Foundations"

 :pack/description "Basic Terraform security and best practices"
 :pack/author      "acme.example"
 :pack/license     "Apache-2.0"

 :pack/taxonomy-ref
 {:taxonomy/id          :miniforge/dewey
  :taxonomy/min-version "1.0.0"}

 :pack/rules
 [{:rule/id          :acme.rule/no-hardcoded-secrets
   :rule/title       "No Hardcoded Secrets"
   :rule/description "Detects hardcoded secrets in Terraform code"
   :rule/categories  [:dewey/security]
   :rule/severity    :critical
   :rule/enabled?    true
   :rule/auto-fix?   false
   :rule/check-fn    check-no-hardcoded-secrets
   :rule/applies-to  [:code-changes :terraform-plan]
   :rule/phase       :implement
   :rule/remediation-template
   "Move secrets to AWS Secrets Manager or environment variables"}

  {:rule/id          :acme.rule/require-tags
   :rule/title       "Require Resource Tags"
   :rule/description "All resources must have required tags"
   :rule/categories  [:dewey/operations]
   :rule/severity    :medium
   :rule/enabled?    true
   :rule/auto-fix?   true
   :rule/check-fn    check-required-tags
   :rule/repair-fn   repair-add-tags
   :rule/applies-to  [:terraform-plan]
   :rule/phase       :review
   :rule/remediation-template
   "Add tags: {Environment, Owner, CostCenter}"}]

 :pack/metadata
 {:tags         ["terraform" "security" "best-practices"]
  :target-types [:infrastructure-change]
  :created-at   #inst "2026-01-23"
  :updated-at   #inst "2026-01-23"}}
```

The `:acme/*` and `:acme.rule/*` namespaces are deliberate: this is a
third-party pack, not a standard one. It is not in the §5.1 registry, and its
name deliberately does not collide with the standard `:miniforge/terraform-aws`.

This example is schema-valid against §2.2 and §2.3 as written. Earlier drafts
of this section used a `:policy-pack/*` key namespace and a `:rule/name` field
that §2.2 and §2.3 never defined; anything copied from those drafts will fail
pack validation.

---

## 12. Rationale & Design Notes

### 12.1 Why Policy-as-Code?

Policy-as-code enables:

- **Consistency** - Same rules applied every time
- **Automation** - No manual review for common issues
- **Learning** - Policy violations generate signals for meta loop
- **Compliance** - Audit trail shows policy enforcement

### 12.2 Why Semantic Intent Validation?

Traditional validation checks "does it work?" (tests, lints).
Semantic intent validation checks **"does it do what you said?"**

This prevents:

- Accidental drift (meant to import, accidentally created)
- Scope creep (started as update, evolved into create)
- Malicious changes (declared refactor, hiding backdoor)

**This is unique to miniforge.**

### 12.3 Why Auto-Repair?

Auto-repair enables autonomous correction:

- Inner loop can fix violations without human intervention
- Faster iteration (no waiting for human to fix trivial issues)
- Learning (repair strategies improve via meta loop)

---

## 13. Future Extensions

### 13.1 Custom Scanners (Post-OSS)

Future versions will support:

- User-defined scanners for custom artifact types
- Scanner marketplace
- Scanner composition (chain scanners)

### 13.2 Policy-as-Service (Enterprise)

Enterprise features will add:

- Central policy pack distribution
- Organization-wide policy enforcement
- Policy analytics (most violated rules, etc.)
- Custom policy authoring UI

### 13.3 Machine Learning Policy Improvement (Future Research)

Research directions:

- Learn policy rules from violations
- Suggest new rules based on patterns
- Optimize rule ordering for performance

---

## 14. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N1 (Architecture): Defines gate and policy pack concepts
- N2 (Workflow Execution): Defines gate execution in phases
- N3 (Event Stream): Defines gate lifecycle events
- N6 (Evidence & Provenance): Stores policy check results in evidence
- N7 (Operational Policy Synthesis): OPSV gate requirements (§5.1.5)
- N8 (Observability Control Interface): RBAC and control action governance (§5.1.6);
  multi-party approval for non-overridable violations (§6.3.1)
- N9 (External PR Integration): External PR policy evaluation (§5.1.7)
- N1 §5.3.3 (failure taxonomy): `:failure/class` on execution-failure violations (§3.5.1)
- N1 §2.24–§2.26 (pack trust, capabilities, high-risk actions): §5.1.8–§5.1.10
- N2 §6.4 (gate schema): the gate this spec binds packs to (§5.4)
- N3 §3.9 (gate events): the events a gate execution emits (§5.5)
- N5-delta-supervisory-control-plane §3.1 (Waiver): the override record (§6.3.1)
- N4-delta-policy-compilation-contract: originating packs from source material

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
currently diverges from the contract above, as of 2026-08-05. It is not a
relaxation of any requirement in §1–§14: the spec is normative and the
implementation conforms to it, not the reverse.

Each row is work, not an exemption.

### A.1 Type Divergences

| Spec (normative) | Implemented | Notes |
|------------------|-------------|-------|
| `:pack/id` is a namespaced keyword (§2.2, N4.PK.2) | `[:pack/id string?]` in `policy-pack/schema.clj` | `:rule/id` is correctly `keyword?`; pack IDs were never migrated. Every `:pack/id` in §5.1's registry is a keyword. |

Rule severity is **not** a divergence: `policy-pack/schema-types` already binds
`RuleSeverity` to the canonical `schema/Severity`
(`:critical :high :medium :low :info`). §2.3.1's former `:error`/`:warning`
vocabulary was the outlier, and this revision withdraws it.

### A.2 Specified, Not Implemented

- **Pack resolution across bound packs (§5.3).** `policy-pack/loader` resolves
  a single overlay chain (`compose-resolved-pack`), which satisfies §2.5. There
  is no multi-pack resolved rule set, no conflict precedence (N4.RB.3), and no
  cross-pack version-conflict failure (N4.RB.4).
- **Gate binding (§5.4).** No `:binding/packs` structure exists. Which packs a
  gate runs is not expressed as data, so N4.RB.5 (unbound gate fails closed)
  has nothing to enforce against.
- **Check-function execution bounds (§3.5.2).** No per-rule timeout and no
  per-gate budget. A hanging check function hangs the gate.
- **Isolation (§3.5.3).** Rules from untrusted packs execute with the same
  privileges as trusted ones.
- **Determinism sampling (§3.5.4).** Not implemented; determinism is asserted
  by §3.1.1 and unverified.
- **Override / waiver (§6.3.1).** The Waiver entity is modelled in
  `tui-views` for display, per N5-delta-supervisory-control-plane. No
  enforcement path produces one, so the §6.3 override action has no durable
  record.

### A.3 Implemented, Matching

Recorded because they were previously unspecified and this revision writes the
contract the code already satisfies:

- **Signature canonicalization (§8.1.1).** `policy-pack/crypto/pack-signable-bytes`
  already dissocs the signature fields, sorts keys via `(into (sorted-map) …)`,
  and serializes `pr-str` as UTF-8 — the algorithm §8.1.1 now specifies.
- **Legacy severity normalization (§2.3.1).** `schema/normalize-severity`
  already exists for `:major` → `:high` and `:minor` → `:low`; §2.3.1 adds
  `:error` → `:high` and `:warning` → `:medium` on the same seam.

### A.4 Structural

- **Stale citations.** `policy-pack/knowledge_safety.clj`,
  `policy-pack/loader.clj`, and `policy-pack/rules/pack_dependency_validation.clj`
  cite "N4 §2.4.2" for the knowledge-safety rules. Those moved to §2.7.2 when
  0.6 inserted the taxonomy, mapping, and overlay sections. Eight citations
  point at a section that now holds the mapping artifact.
- **Duplicated violation schema.** N2 §6.5 restates the violation schema with
  `:violation/rule-id string`, contradicting §3.3 as revised. §3.3 states it is
  the single definition; N2 §6.5 should reference rather than restate it. That
  edit belongs to an N2 change, not this one.

---

**Version History:**

- 0.7.0-draft (2026-08-05): Spec-completion pass.
  **Contract fixes:** one severity vocabulary — §2.3.1 rewritten from
  `:error`/`:warning`/`:info` to the canonical `:critical :high :medium :low
  :info` used by §3.3, all of §5.1, and §6.3, with normalization for the
  withdrawn values; `:violation/rule-id` typed keyword to match §2.3;
  `:violation/pack-id` added; §11.1's example rewritten from the pre-0.6
  `:policy-pack/*` namespace to the §2.2 schema; §8.1 signature key
  namespace aligned to `:pack/signature`; §5.1.7's result schema keys
  aligned; `require-capability-declaration` split into
  `require-task-capability-declaration` (§5.1.4) and
  `require-pack-capability-declaration` (§5.1.9), which collided under the
  global-uniqueness rule of §2.3.
  **New normative sections:** check-function execution semantics — fail-closed,
  resource bounds, isolation, determinism (§3.5); taxonomy compatibility
  (§2.1.1); standard pack registry and identifier convention (§5.1); pack
  resolution and precedence (§5.3); gate binding (§5.4); events and evidence
  obligations (§5.5); override and waiver (§6.3.1); signature canonicalization
  and trust roots (§8.1.1, §8.2.1); conformance requirement IDs and test
  obligations (§9.4–§9.5).
  Annex A records implementation divergence as tracked work.
- 0.6.0-draft (2026-04-05): Four-artifact model — independently versioned
  taxonomy artifact (§2.1), taxonomy reference on packs (§2.2), plural
  `:rule/categories` (§2.3), mapping artifact (§2.4), overlay pack with
  resolution rules (§2.5); knowledge-safety content renumbered to §2.7
- 0.5.0-draft (2026-03-08): Reliability Nines amendments — Validation Layer Taxonomy
  (§3.4) with 5-layer model and ordering invariant
- 0.4.0-draft (2026-02-16): Added Pack Trust Gate (§5.1.8), Capability Grant Gate
  (§5.1.9), High-Risk Pack Action Gate (§5.1.10)
- 0.3.0-draft (2026-02-07): Added extension spec gates from N7, N8, N9
  (§5.1.5–§5.1.7)
- 0.2.0-draft (2026-02-04): Added task-scope policy pack for capability enforcement (§5.1.4)
- 0.1.0-draft (2026-01-23): Initial policy packs and gates specification
