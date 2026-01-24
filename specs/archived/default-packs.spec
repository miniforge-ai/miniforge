# miniforge.ai — Default Policy Packs Specification

**Version:** 0.1.0
**Status:** Draft
**Date:** 2026-01-22

---

## 1. Overview

This spec defines the initial set of official policy packs that ship with miniforge. These packs establish baseline safety and quality standards while demonstrating the pack authoring patterns.

### 1.1 Official Packs

| Pack ID | Category | Description |
|---------|----------|-------------|
| `foundations` | 000 | Architectural principles (Stratified Design, Simple Made Easy) |
| `clojure` | 200 | Clojure/Polylith conventions |
| `terraform-safety` | 300 | Terraform infrastructure safety |
| `kubernetes-safety` | 400 | Kubernetes deployment safety |
| `security-baseline` | 500 | Security best practices |

---

## 2. Foundations Pack

### 2.1 Manifest

```clojure
{:pack/id "foundations"
 :pack/name "Foundations Pack"
 :pack/version "2026.01.22"
 :pack/description "Core architectural principles: Stratified Design and Simple Made Easy"
 :pack/author "miniforge-official"
 :pack/license "Apache-2.0"

 :pack/categories
 [{:category/id "001"
   :category/name "Stratified Design"
   :category/rules [:001-no-upward-imports
                    :001-no-cycles
                    :001-pure-domain
                    :001-ports-and-adapters]}
  {:category/id "002"
   :category/name "Simple Made Easy"
   :category/rules [:002-values-over-state
                    :002-data-over-syntax
                    :002-functions-over-methods]}]

 :pack/rules [...]}
```

### 2.2 Rules

#### 001-no-upward-imports

```clojure
{:rule/id :001-no-upward-imports
 :rule/title "No upward imports in stratified design"
 :rule/description "Modules may only import from same stratum or below. Never import 'up' the stack."
 :rule/severity :major
 :rule/category "001"

 :rule/applies-to
 {:file-globs ["**/*.clj" "**/*.cljc" "**/*.ts" "**/*.py"]}

 :rule/detection
 {:type :custom
  :custom-fn 'miniforge.packs.foundations/detect-upward-import}

 :rule/agent-behavior
 "Before writing code for a feature, output:
  - A short stratified plan (modules, their stratum, and allowed dependencies)
  - The ports/interfaces to be introduced or reused

  When editing existing code:
  - Detect and call out any 'upward' import or cross-strata leakage
  - If violation found, propose minimal refactor (move interface down, split module, add adapter)"

 :rule/enforcement
 {:action :hard-halt
  :message "Upward import detected: %s imports from %s (stratum %d → %d)"
  :remediation "Move the interface to a lower stratum, or inject via port/adapter pattern"}}
```

#### 001-no-cycles

```clojure
{:rule/id :001-no-cycles
 :rule/title "No import cycles"
 :rule/description "Reject any change that introduces an import/namespace cycle"
 :rule/severity :critical
 :rule/category "001"

 :rule/applies-to
 {:file-globs ["**/*.clj" "**/*.cljc" "**/*.ts" "**/*.py"]}

 :rule/detection
 {:type :custom
  :custom-fn 'miniforge.packs.foundations/detect-import-cycle}

 :rule/enforcement
 {:action :hard-halt
  :message "Import cycle detected: %s"
  :remediation "Break the cycle by extracting shared interface to lower stratum"}}
```

#### 001-pure-domain

```clojure
{:rule/id :001-pure-domain
 :rule/title "Domain layer must be pure"
 :rule/description "Domain modules must be free of I/O, frameworks, and runtime state"
 :rule/severity :major
 :rule/category "001"

 :rule/applies-to
 {:file-globs ["**/domain/**/*.clj" "**/domain/**/*.ts" "**/domain/**/*.py"]}

 :rule/detection
 {:type :content-scan
  :patterns [;; Clojure I/O
             #"slurp\s*\(" #"spit\s*\(" #"clojure\.java\.io"
             #"http/get" #"http/post" #"jdbc/"
             ;; TypeScript I/O
             #"fetch\s*\(" #"fs\." #"axios\." #"prisma\."
             ;; Python I/O
             #"open\s*\(" #"requests\." #"urllib" #"sqlalchemy"]}

 :rule/agent-behavior
 "Domain layer must contain only:
  - Pure functions (no side effects)
  - Business rules and invariants
  - Data transformations

  If I/O is needed, define a port (interface) in Application layer and inject adapter."

 :rule/enforcement
 {:action :hard-halt
  :message "I/O detected in Domain layer: %s"
  :remediation "Move I/O to Infrastructure adapter, inject via port"}}
```

#### 002-values-over-state

```clojure
{:rule/id :002-values-over-state
 :rule/title "Prefer values over mutable state"
 :rule/description "Minimize mutable state; make it explicit, local, and small when necessary"
 :rule/severity :minor
 :rule/category "002"

 :rule/applies-to
 {:file-globs ["**/*.clj" "**/*.cljc"]}

 :rule/detection
 {:type :content-scan
  :patterns [#"def\s+\S+\s+\(atom" #"def\s+\S+\s+\(ref\s"
             #"volatile!" #"set!"]}

 :rule/agent-behavior
 "When proposing a design, include:
  1) A short 'simple vs easy' comparison (2-3 bullets)
  2) The minimal set of pure functions and data structures
  3) Where any state is confined and why it's necessary"

 :rule/enforcement
 {:action :warn
  :message "Mutable state detected: %s. Is this necessary?"
  :remediation "Consider if immutable value + pure function would suffice"}}
```

---

## 3. Clojure Pack

### 3.1 Manifest

```clojure
{:pack/id "clojure"
 :pack/name "Clojure Conventions Pack"
 :pack/version "2026.01.22"
 :pack/description "Clojure and Polylith coding conventions"
 :pack/author "miniforge-official"
 :pack/license "Apache-2.0"

 :pack/extends
 [{:pack-id "foundations" :version-constraint ">=2026.01.01"}]

 :pack/categories
 [{:category/id "210"
   :category/name "Polylith Architecture"
   :category/rules [:210-interface-only-deps
                    :210-no-project-deps
                    :210-component-layout]}
  {:category/id "211"
   :category/name "Per-File Stratification"
   :category/rules [:211-layer-headings
                    :211-max-three-layers
                    :211-comment-block-last]}]

 :pack/rules [...]}
```

### 3.2 Rules

#### 210-interface-only-deps

```clojure
{:rule/id :210-interface-only-deps
 :rule/title "Cross-component deps must target interface"
 :rule/description "Components may only depend on other components via their .interface namespace"
 :rule/severity :major
 :rule/category "210"

 :rule/applies-to
 {:file-globs ["components/**/src/**/*.clj" "components/**/src/**/*.cljc"]}

 :rule/detection
 {:type :content-scan
  :patterns [#"\[ai\.miniforge\.\w+\.core\s" #"\[ai\.miniforge\.\w+\.impl\s"
             #"require.*\.core\]" #"require.*\.impl\]"]}

 :rule/agent-behavior
 "For cross-component calls, target ...<other>.interface — surface a fix if .core is used.

  WRONG: [ai.miniforge.agent.core :as agent-core]
  RIGHT: [ai.miniforge.agent.interface :as agent]"

 :rule/enforcement
 {:action :hard-halt
  :message "Direct dependency on component implementation: %s"
  :remediation "Change to .interface namespace"}}
```

#### 211-layer-headings

```clojure
{:rule/id :211-layer-headings
 :rule/title "Use layer headings in namespaces"
 :rule/description "Structure each ns with labeled strata as headings"
 :rule/severity :minor
 :rule/category "211"

 :rule/applies-to
 {:file-globs ["components/**/src/**/*.clj" "bases/**/src/**/*.clj"]}

 :rule/detection
 {:type :content-scan
  :patterns [#"defn\s.*\n.*defn"]} ; Multiple defns without layer separator

 :rule/agent-behavior
 "When creating/editing a namespace:
  1) Insert/maintain Layer 0/1/2 headings: ;-------------- Layer N
  2) Keep them monotonic increasing (0 → 1 → 2)
  3) Keep pure helpers in Layer 0
  4) Put orchestration in higher layers"

 :rule/enforcement
 {:action :warn
  :message "Namespace lacks layer headings"
  :remediation "Add layer heading comments to organize code by abstraction level"}}
```

#### 211-max-three-layers

```clojure
{:rule/id :211-max-three-layers
 :rule/title "Maximum 3 layers per file"
 :rule/description "If you need more than 3 strata, split the namespace"
 :rule/severity :minor
 :rule/category "211"

 :rule/applies-to
 {:file-globs ["components/**/src/**/*.clj"]}

 :rule/detection
 {:type :content-scan
  :pattern #"Layer\s+[4-9]"}

 :rule/agent-behavior
 "If you cross 3 layers, propose a split into another namespace."

 :rule/enforcement
 {:action :warn
  :message "More than 3 layers detected"
  :remediation "Split into separate namespaces to maintain clarity"}}
```

#### 211-comment-block-last

```clojure
{:rule/id :211-comment-block-last
 :rule/title "Rich comment block must be last"
 :rule/description "Reserve the very bottom of file for (comment ...) section. No def/defn after it."
 :rule/severity :minor
 :rule/category "211"

 :rule/applies-to
 {:file-globs ["**/*.clj" "**/*.cljc"]}

 :rule/detection
 {:type :content-scan
  :pattern #"\(comment[\s\S]*?\)\s*\n\s*\(def"}

 :rule/agent-behavior
 "Ensure the last top-level form is a single (comment ...) block with quick REPL samples."

 :rule/enforcement
 {:action :warn
  :message "Code appears after (comment ...) block"
  :remediation "Move all def/defn above the comment block"}}
```

---

## 4. Terraform Safety Pack

### 4.1 Manifest

```clojure
{:pack/id "terraform-safety"
 :pack/name "Terraform Safety Pack"
 :pack/version "2026.01.22"
 :pack/description "Essential safety rules for Terraform infrastructure changes"
 :pack/author "miniforge-official"
 :pack/license "Apache-2.0"

 :pack/categories
 [{:category/id "310"
   :category/name "Import Safety"
   :category/rules [:310-import-block-preservation
                    :311-import-no-creates
                    :312-import-state-verification]}
  {:category/id "320"
   :category/name "Network Safety"
   :category/rules [:320-network-recreation-block
                    :321-security-group-blast-radius]}
  {:category/id "330"
   :category/name "State Safety"
   :category/rules [:330-state-drift-detection
                    :331-module-version-pinning]}
  {:category/id "340"
   :category/name "Data Safety"
   :category/rules [:340-s3-lifecycle-data-loss
                    :341-database-deletion-protection]}]

 :pack/rules [...]}
```

### 4.2 Rules

#### 310-import-block-preservation

```clojure
{:rule/id :310-import-block-preservation
 :rule/title "Preserve import blocks during IMPORT tasks"
 :rule/description "When task type is IMPORT, never remove import blocks"
 :rule/severity :critical
 :rule/category "310"

 :rule/applies-to
 {:task-types #{:import}
  :file-globs ["**/*.tf" "**/terragrunt.hcl"]}

 :rule/detection
 {:type :diff-analysis
  :pattern #"^-\s*import\s*\{"
  :context-lines 5}

 :rule/agent-behavior
 "When Atlantis reports 'resource already exists':
  - WRONG interpretation: Remove import block (agent thinks import is done)
  - RIGHT interpretation: Keep import block (resource exists in AWS, import is working)

  Only remove import block when Atlantis reports 'already managed by Terraform'.

  Error interpretation guide:
  | Error Message | Meaning | Action |
  |---------------|---------|--------|
  | 'resource already exists' | Resource in AWS, import working | KEEP import block |
  | 'already managed by Terraform' | Import complete | OK to remove import block |
  | 'resource not found' | Resource doesn't exist | Check AWS, maybe create instead |"

 :rule/enforcement
 {:action :hard-halt
  :message "Cannot remove import blocks during IMPORT task"
  :remediation "Restore the import block and re-run the plan"}

 :rule/examples
 [{:description "Agent removes import block thinking import is done"
   :input "- import {\n-   to = aws_s3_bucket.logs\n-   id = \"my-bucket\"\n- }"
   :expected :fail
   :explanation "Import block removal forbidden during IMPORT task"}
  {:description "Import block unchanged"
   :input "  import {\n    to = aws_s3_bucket.logs\n    id = \"my-bucket\"\n  }"
   :expected :pass}]}
```

#### 311-import-no-creates

```clojure
{:rule/id :311-import-no-creates
 :rule/title "IMPORT tasks must not create resources"
 :rule/description "When task type is IMPORT, plan should show 0 resources to create"
 :rule/severity :critical
 :rule/category "310"

 :rule/applies-to
 {:task-types #{:import}}

 :rule/detection
 {:type :plan-output
  :pattern #"Plan:.*(\d+) to add"
  :condition (fn [match] (> (parse-long (second match)) 0))}

 :rule/agent-behavior
 "For IMPORT tasks, the terraform plan should show:
  - 0 to add (no creates)
  - 0 to destroy (no destroys)
  - N to change (importing existing resources)

  If 'to add' > 0, the import block is wrong or the resource doesn't exist."

 :rule/enforcement
 {:action :hard-halt
  :message "IMPORT task would create %d new resources - this violates import semantics"
  :remediation "Verify resource exists in AWS and import block references correct ID"}}
```

#### 320-network-recreation-block

```clojure
{:rule/id :320-network-recreation-block
 :rule/title "Block recreation of network resources"
 :rule/description "Prevent destroy-then-create operations on critical network infrastructure"
 :rule/severity :critical
 :rule/category "320"

 :rule/applies-to
 {:file-globs ["**/*.tf"]}

 :rule/detection
 {:type :plan-output
  :patterns [#"-/\+\s+aws_route\b"
             #"-/\+\s+aws_route_table\b"
             #"-/\+\s+aws_subnet\b"
             #"-/\+\s+aws_nat_gateway\b"
             #"-/\+\s+aws_vpc\b"
             #"-/\+\s+aws_security_group\b"
             #"-/\+\s+aws_internet_gateway\b"
             #"-/\+\s+aws_vpc_peering_connection\b"]}

 :rule/agent-behavior
 "HIGHEST PRIORITY - Network resources affect all services:
  - aws_route, aws_route_table
  - aws_subnet, aws_nat_gateway
  - aws_vpc, aws_internet_gateway
  - aws_security_group (if attached to instances)
  - aws_vpc_peering_connection

  Recreation (-/+) of these resources causes:
  - Service outage during recreation window
  - IP address changes breaking DNS/routing
  - Security group detachment from running instances

  If recreation is truly required, document blast radius and get explicit approval."

 :rule/enforcement
 {:action :require-approval
  :approvers [:human :senior-engineer]
  :message "Network resource recreation detected: %s"
  :remediation "Review plan carefully. Consider lifecycle { prevent_destroy = true }. Document blast radius."}}
```

#### 340-s3-lifecycle-data-loss

```clojure
{:rule/id :340-s3-lifecycle-data-loss
 :rule/title "S3 lifecycle rules that delete data require approval"
 :rule/description "Flag lifecycle expiration rules that permanently delete objects"
 :rule/severity :major
 :rule/category "340"

 :rule/applies-to
 {:file-globs ["**/*.tf"]}

 :rule/detection
 {:type :content-scan
  :patterns [#"expiration\s*\{[^}]*days\s*=\s*\d+"
             #"noncurrent_version_expiration"
             #"abort_incomplete_multipart_upload"]}

 :rule/agent-behavior
 "S3 lifecycle rules that expire objects result in PERMANENT DATA LOSS.

  Before adding expiration rules, verify:
  1. Data is not needed for compliance/audit
  2. Retention period meets business requirements
  3. Cross-region replication provides backup (if needed)

  Safe patterns:
  - Transition to GLACIER/DEEP_ARCHIVE (not delete)
  - Expire only noncurrent versions after backup period
  - Expire multipart uploads (usually safe)"

 :rule/enforcement
 {:action :require-approval
  :approvers [:human]
  :message "S3 lifecycle rule may cause data loss: %s"
  :remediation "Confirm data retention requirements. Consider transition instead of expiration."}}
```

---

## 5. Kubernetes Safety Pack

### 5.1 Manifest

```clojure
{:pack/id "kubernetes-safety"
 :pack/name "Kubernetes Safety Pack"
 :pack/version "2026.01.22"
 :pack/description "Safety rules for Kubernetes deployments"
 :pack/author "miniforge-official"
 :pack/license "Apache-2.0"

 :pack/categories
 [{:category/id "410"
   :category/name "Container Safety"
   :category/rules [:410-no-latest-tag
                    :411-no-privileged
                    :412-resource-limits-required]}
  {:category/id "420"
   :category/name "Deployment Safety"
   :category/rules [:420-replica-minimum
                    :421-rolling-update-strategy
                    :422-pod-disruption-budget]}
  {:category/id "430"
   :category/name "Security Context"
   :category/rules [:430-run-as-non-root
                    :431-read-only-root-filesystem]}]

 :pack/rules [...]}
```

### 5.2 Rules

#### 410-no-latest-tag

```clojure
{:rule/id :410-no-latest-tag
 :rule/title "No 'latest' image tags"
 :rule/description "Container images must use explicit version tags, not 'latest'"
 :rule/severity :major
 :rule/category "410"

 :rule/applies-to
 {:file-globs ["**/*.yaml" "**/*.yml"]
  :repo-types #{:kubernetes :argocd}}

 :rule/detection
 {:type :content-scan
  :patterns [#"image:\s*\S+:latest\b"
             #"image:\s*[^:]+\s*$"]}  ; No tag at all defaults to latest

 :rule/agent-behavior
 "Always use explicit image tags for reproducibility and rollback capability.

  WRONG: image: nginx:latest
  WRONG: image: nginx  (defaults to latest)
  RIGHT: image: nginx:1.25.3
  RIGHT: image: nginx@sha256:abc123..."

 :rule/enforcement
 {:action :hard-halt
  :message "Image uses 'latest' tag or no tag: %s"
  :remediation "Specify explicit version tag or SHA256 digest"}}
```

#### 411-no-privileged

```clojure
{:rule/id :411-no-privileged
 :rule/title "No privileged containers"
 :rule/description "Containers must not run in privileged mode"
 :rule/severity :critical
 :rule/category "410"

 :rule/applies-to
 {:file-globs ["**/*.yaml" "**/*.yml"]
  :repo-types #{:kubernetes}}

 :rule/detection
 {:type :content-scan
  :pattern #"privileged:\s*true"}

 :rule/agent-behavior
 "Privileged containers have full host access and bypass security controls.
  Only allowed for specific infrastructure components (CNI, storage drivers)
  with explicit approval."

 :rule/enforcement
 {:action :hard-halt
  :message "Privileged container detected"
  :remediation "Remove privileged: true. Use specific capabilities instead if needed."}}
```

#### 412-resource-limits-required

```clojure
{:rule/id :412-resource-limits-required
 :rule/title "Resource limits required"
 :rule/description "All containers must specify CPU and memory limits"
 :rule/severity :major
 :rule/category "410"

 :rule/applies-to
 {:file-globs ["**/*.yaml" "**/*.yml"]
  :repo-types #{:kubernetes}}

 :rule/detection
 {:type :custom
  :custom-fn 'miniforge.packs.kubernetes/detect-missing-limits}

 :rule/agent-behavior
 "Every container must have:
  resources:
    requests:
      memory: '128Mi'
      cpu: '100m'
    limits:
      memory: '512Mi'
      cpu: '500m'

  Without limits, a single pod can consume all node resources."

 :rule/enforcement
 {:action :hard-halt
  :message "Container missing resource limits: %s"
  :remediation "Add resources.limits.cpu and resources.limits.memory"}}
```

#### 430-run-as-non-root

```clojure
{:rule/id :430-run-as-non-root
 :rule/title "Run as non-root user"
 :rule/description "Containers should not run as root"
 :rule/severity :major
 :rule/category "430"

 :rule/applies-to
 {:file-globs ["**/*.yaml" "**/*.yml"]
  :repo-types #{:kubernetes}}

 :rule/detection
 {:type :custom
  :custom-fn 'miniforge.packs.kubernetes/detect-root-user}

 :rule/agent-behavior
 "Set securityContext at pod or container level:
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000

  If image requires root, consider using a different image or init container."

 :rule/enforcement
 {:action :warn
  :message "Container may run as root"
  :remediation "Add securityContext.runAsNonRoot: true"}}
```

---

## 6. Security Baseline Pack

### 6.1 Manifest

```clojure
{:pack/id "security-baseline"
 :pack/name "Security Baseline Pack"
 :pack/version "2026.01.22"
 :pack/description "Security best practices for all code"
 :pack/author "miniforge-official"
 :pack/license "Apache-2.0"

 :pack/categories
 [{:category/id "510"
   :category/name "Secrets Management"
   :category/rules [:510-no-hardcoded-secrets
                    :511-no-secrets-in-logs]}
  {:category/id "520"
   :category/name "IAM Best Practices"
   :category/rules [:520-no-wildcard-actions
                    :521-least-privilege]}]

 :pack/rules [...]}
```

### 6.2 Rules

#### 510-no-hardcoded-secrets

```clojure
{:rule/id :510-no-hardcoded-secrets
 :rule/title "No hardcoded secrets"
 :rule/description "Secrets must not be hardcoded in source files"
 :rule/severity :critical
 :rule/category "510"

 :rule/applies-to
 {:file-globs ["**/*.clj" "**/*.ts" "**/*.py" "**/*.tf" "**/*.yaml"]}

 :rule/detection
 {:type :content-scan
  :patterns [;; API keys and tokens
             #"(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*['\"][a-zA-Z0-9]{16,}['\"]"
             ;; AWS credentials
             #"AKIA[0-9A-Z]{16}"
             #"(?i)aws[_-]?secret[_-]?access[_-]?key\s*[:=]\s*['\"][^'\"]{40}['\"]"
             ;; Private keys
             #"-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"
             ;; Bearer tokens
             #"(?i)bearer\s+[a-zA-Z0-9_-]{20,}"]}

 :rule/agent-behavior
 "Never hardcode secrets. Use:
  - Environment variables: (System/getenv \"API_KEY\")
  - Secrets manager: AWS Secrets Manager, HashiCorp Vault
  - K8s secrets: Mounted as files or env vars

  If you see what looks like a secret in code, flag it immediately."

 :rule/enforcement
 {:action :hard-halt
  :message "Possible hardcoded secret detected"
  :remediation "Remove secret from code. Use environment variable or secrets manager."}}
```

#### 520-no-wildcard-actions

```clojure
{:rule/id :520-no-wildcard-actions
 :rule/title "No wildcard IAM actions"
 :rule/description "IAM policies must not use Action: '*'"
 :rule/severity :critical
 :rule/category "520"

 :rule/applies-to
 {:file-globs ["**/*.tf" "**/*.json"]
  :resource-patterns [#"aws_iam_policy" #"aws_iam_role_policy"]}

 :rule/detection
 {:type :content-scan
  :patterns [#"\"Action\"\s*:\s*\"\*\""
             #"\"Action\"\s*:\s*\[\s*\"\*\"\s*\]"
             #"Action\s*=\s*\[\s*\"\*\"\s*\]"]}

 :rule/agent-behavior
 "IAM policies must follow least privilege:
  - Specify exact actions needed (s3:GetObject, s3:PutObject)
  - Never use Action: '*' (admin access)
  - Scope resources to specific ARNs, not '*'

  If broad access is truly needed, document justification and get security review."

 :rule/enforcement
 {:action :hard-halt
  :message "IAM policy uses wildcard action"
  :remediation "Replace '*' with specific actions needed. Document if broad access required."}}
```

---

## 7. Pack Composition Example

### 7.1 Combining Packs

```clojure
;; .miniforge/config.edn
{:packs
 [;; Official packs
  {:id "foundations" :version "2026.01.22"}
  {:id "clojure" :version "2026.01.22"}
  {:id "terraform-safety" :version "2026.01.22"}
  {:id "kubernetes-safety" :version "2026.01.22"}
  {:id "security-baseline" :version "2026.01.22"}

  ;; Company-specific overrides
  {:id "acme-policies"
   :source {:type :local :path ".miniforge/packs/acme-policies"}}]

 ;; Override specific rules
 :pack-overrides
 {;; Downgrade to warning for development repos
  :320-network-recreation-block
  {:rule/enforcement {:action :warn}}

  ;; Stricter for production
  :410-no-latest-tag
  {:rule/enforcement {:action :hard-halt
                      :approvers [:senior-engineer :security]}}}}
```

### 7.2 Custom Pack Extending Official

```clojure
;; acme-policies/pack.edn
{:pack/id "acme-policies"
 :pack/name "Acme Corp Policies"
 :pack/version "2026.01.22"
 :pack/author "acme-platform-team"

 :pack/extends
 [{:pack-id "security-baseline" :version-constraint ">=2026.01.01"}]

 :pack/rules
 [;; Company-specific: require specific naming convention
  {:rule/id :800-acme-naming
   :rule/title "Acme resource naming convention"
   :rule/description "All resources must follow {env}-{service}-{resource} naming"
   :rule/severity :minor
   :rule/category "800"

   :rule/applies-to
   {:file-globs ["**/*.tf"]}

   :rule/detection
   {:type :content-scan
    :pattern #"name\s*=\s*\"(?!(?:dev|staging|prod)-)"} ; Must start with env

   :rule/enforcement
   {:action :warn
    :message "Resource name doesn't follow Acme naming convention"
    :remediation "Use format: {env}-{service}-{resource}"}}]}
```

---

## 8. Deliverables

### Phase 1: Foundation Packs

- [ ] `foundations` pack with Stratified Design rules
- [ ] `clojure` pack with Polylith conventions
- [ ] Pack loader that reads EDN format
- [ ] Integration tests for all rules

### Phase 2: Infrastructure Packs

- [ ] `terraform-safety` pack
- [ ] `kubernetes-safety` pack
- [ ] Plan output parser for Terraform
- [ ] YAML manifest parser for Kubernetes

### Phase 3: Security Pack

- [ ] `security-baseline` pack
- [ ] Secret detection patterns
- [ ] IAM policy analyzer

### Phase 4: Distribution

- [ ] Pack bundled with miniforge distribution
- [ ] Pack update mechanism
- [ ] Documentation for each pack

---

## 9. References

- [policy-pack.spec](./policy-pack.spec) — Pack schema and registry
- [change-train.spec](./change-train.spec) — Integration with PR trains
- Kiddom cursor-rules — Rule patterns and organization
