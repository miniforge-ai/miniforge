<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N4 — Policy Packs & Gates Standard

**Version:** 0.6.0-draft
**Date:** 2026-04-05
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **policy pack** and **gate validation** contracts for miniforge
autonomous software factory. It establishes:

- **Taxonomy artifact** - Independently versioned category tree (new in 0.6)
- **Policy pack structure** - Format, versioning, taxonomy reference, overlay/extends
- **Mapping artifact** - First-class bridge between policy systems (new in 0.6)
- **Overlay pack** - Extension model for packs without forking (new in 0.6)
- **Gate execution contract** - Check and repair function interfaces
- **Semantic intent validation** - Rules for intent vs. behavior matching
- **Violation schema** - Severity levels, remediation, enforcement actions
- **Remediation UX contract** - Human and machine-readable repair guidance

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
 :rule/severity     keyword            ; REQUIRED: :error, :warning, :info
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

#### 2.3.1 Rule Severity Levels

| Severity   | Meaning                                      | Enforcement                       |
|------------|----------------------------------------------|-----------------------------------|
| `:error`   | Blocks phase; requires fix or human override | MUST block phase completion       |
| `:warning` | Strongly discouraged; auto-repair or review  | SHOULD block unless auto-repaired |
| `:info`    | Informational; no action required            | MUST NOT block                    |

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
attempt to override agent behavior. The platform MUST treat such content as *data* unless it
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

This scanner MUST be treated as a *tripwire* rather than a complete security solution.
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
                          {:violation/rule-id "no-public-s3"
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
 :violation/rule-id string         ; REQUIRED: Rule that detected violation
 :violation/gate-id keyword        ; REQUIRED: Gate that ran this rule

 :violation/severity keyword       ; REQUIRED: :critical, :high, :medium, :low, :info
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
 :violation/documentation-url string} ; OPTIONAL: Link to docs
```

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
                     [{:violation/rule-id "semantic-intent-mismatch"
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

Implementations SHOULD provide these standard policy packs:

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

- `require-capability-declaration` (severity: medium)
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
 [{:rule/id string                    ; From N4 policy pack rule
   :rule/outcome keyword              ; :pass, :fail, :warn, :skip, :unknown
   :rule/message string
   :rule/evidence-id uuid}]           ; N6 artifact with full details
 :policy/evaluated-at inst
 :policy/packs-applied
 [{:policy-pack/id string
   :policy-pack/version string}]}
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

- `require-capability-declaration` (severity: critical)
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

3. **Override** (if `allow-override?` is true and severity ≤ `:medium`)
   - Log override decision
   - Record in evidence bundle
   - Proceed with warning

4. **Cancel** (always available)
   - Stop workflow
   - Preserve partial evidence

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
{:policy-pack/signature
 {:algorithm :ed25519
  :public-key "..."
  :signature "..."
  :signed-at inst}}
```

### 8.2 Verification Protocol

Before executing policy pack:

1. Verify signature against public key
2. Check signature timestamp
3. Validate pack hasn't been tampered with
4. Warn on unsigned packs (if required)

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

### 11.1 Complete Example: Terraform Foundations

```clojure
{:policy-pack/id "terraform-foundations"
 :policy-pack/version "1.0.0"
 :policy-pack/name "Terraform Foundations"
 :policy-pack/description "Basic Terraform security and best practices"
 :policy-pack/author "miniforge.ai"
 :policy-pack/license "Apache-2.0"

 :policy-pack/rules
 [{:rule/id "no-hardcoded-secrets"
   :rule/name "No Hardcoded Secrets"
   :rule/description "Detects hardcoded secrets in Terraform code"
   :rule/severity :critical
   :rule/enabled? true
   :rule/check-fn check-no-hardcoded-secrets
   :rule/applies-to [:code-changes :terraform-plan]
   :rule/phase :implement
   :rule/remediation-template
   "Move secrets to AWS Secrets Manager or environment variables"}

  {:rule/id "require-tags"
   :rule/name "Require Resource Tags"
   :rule/description "All resources must have required tags"
   :rule/severity :medium
   :rule/enabled? true
   :rule/check-fn check-required-tags
   :rule/repair-fn repair-add-tags
   :rule/applies-to [:terraform-plan]
   :rule/phase :review
   :rule/remediation-template
   "Add tags: {Environment, Owner, CostCenter}"}]

 :policy-pack/metadata
 {:tags ["terraform" "security" "best-practices"]
  :target-types [:infrastructure-change]
  :created-at #inst "2026-01-23"
  :updated-at #inst "2026-01-23"}}
```

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
- N8 (Observability Control Interface): RBAC and control action governance (§5.1.6)
- N9 (External PR Integration): External PR policy evaluation (§5.1.7)

---

**Version History:**

- 0.5.0-draft (2026-03-08): Reliability Nines amendments — Validation Layer Taxonomy
  (§3.4) with 5-layer model and ordering invariant
- 0.4.0-draft (2026-02-16): Added Pack Trust Gate (§5.1.8), Capability Grant Gate
  (§5.1.9), High-Risk Pack Action Gate (§5.1.10)
- 0.3.0-draft (2026-02-07): Added extension spec gates from N7, N8, N9
  (§5.1.5–§5.1.7)
- 0.2.0-draft (2026-02-04): Added task-scope policy pack for capability enforcement (§5.1.4)
- 0.1.0-draft (2026-01-23): Initial policy packs and gates specification
