# miniforge.ai — Policy Pack Specification

**Version:** 0.1.0
**Status:** Draft
**Date:** 2026-01-22

---

## 1. Overview

### 1.1 Purpose

Policy Packs are extensible, versioned collections of validation rules that can be authored, shared, and imported. They enable "policy-as-code" enforcement across the miniforge ecosystem.

This spec defines:
- **policy-pack**: Schema, registry, and lifecycle for rule collections
- **Marketplace patterns**: Distribution, versioning, and discovery
- **Integration**: How packs plug into the existing gate system

### 1.2 Design Principles

1. **Data over code**: Rules are EDN data, not compiled code
2. **Dewey organization**: Rules follow Dewey-style hierarchical categories
3. **Composable**: Packs can extend and override other packs
4. **Versioned**: DateVer versioning for clear chronological ordering
5. **Signable**: Paid feature for organizational trust chains

### 1.3 Inspiration

This design draws from:
- Kiddom cursor-rules Dewey organization (000-900 categories)
- `.mdc` file format (YAML frontmatter + Markdown body)
- Git submodule distribution pattern
- DateVer versioning (`YYYY.MM.DD`)

---

## 2. Rule Schema

### 2.1 Individual Rule

```clojure
(def RuleSeverity
  [:enum :critical :major :minor :info])

(def RuleEnforcement
  [:enum :hard-halt        ; Blocks all progress
         :require-approval ; Requires human sign-off
         :warn             ; Warning only, doesn't block
         :audit])          ; Log only, for compliance

(def RuleApplicability
  [:map
   ;; When does this rule apply?
   [:task-types {:optional true} [:set [:enum :create :import :modify :delete :migrate]]]
   [:file-globs {:optional true} [:vector string?]]
   [:resource-patterns {:optional true} [:vector [:or string? :re]]]
   [:repo-types {:optional true} [:set [:enum :terraform-module :terraform-live
                                        :kubernetes :argocd :application]]]
   [:phases {:optional true} [:set keyword?]]])

(def RuleDetection
  [:map
   [:type [:enum :plan-output      ; Parse terraform plan
                 :diff-analysis    ; Analyze git diff
                 :state-comparison ; Compare before/after state
                 :content-scan     ; Regex scan of content
                 :ast-analysis     ; Parse code AST
                 :custom]]         ; Custom detection function
   [:pattern {:optional true} [:or string? :re]]
   [:patterns {:optional true} [:vector [:or string? :re]]]
   [:context-lines {:optional true} pos-int?]
   [:custom-fn {:optional true} symbol?]])  ; For custom detection

(def Rule
  [:map
   [:rule/id keyword?]                      ; e.g., :310-tf-import-preservation
   [:rule/title string?]
   [:rule/description string?]
   [:rule/severity RuleSeverity]
   [:rule/category string?]                 ; Dewey category e.g., "300"

   ;; When does this rule apply?
   [:rule/applies-to RuleApplicability]

   ;; How to detect violations
   [:rule/detection RuleDetection]

   ;; Agent guidance (critical for correct interpretation)
   [:rule/agent-behavior {:optional true} string?]

   ;; What happens when violated
   [:rule/enforcement
    [:map
     [:action RuleEnforcement]
     [:message string?]
     [:remediation {:optional true} string?]
     [:approvers {:optional true} [:vector [:enum :human :senior-engineer :security]]]]]

   ;; Examples (for documentation and testing)
   [:rule/examples {:optional true}
    [:vector
     [:map
      [:description string?]
      [:input string?]
      [:expected [:enum :pass :fail]]
      [:explanation {:optional true} string?]]]]

   ;; Metadata
   [:rule/version {:optional true} string?]
   [:rule/author {:optional true} string?]
   [:rule/references {:optional true} [:vector string?]]])
```

### 2.2 Rule Categories (Dewey-Style)

| Range | Category | Description |
|-------|----------|-------------|
| 000-099 | Foundations | Architectural principles (Stratified Design, Simple Made Easy) |
| 100-199 | Version Control | Git workflows, branching, PR patterns |
| 200-299 | Languages | Language-specific conventions (Clojure, Python, TypeScript) |
| 300-399 | Infrastructure | Terraform, Terragrunt, AWS, IaC safety |
| 400-499 | Orchestration | Kubernetes, ArgoCD, container management |
| 500-599 | Security | Secrets, IAM, access control, compliance |
| 600-699 | Documentation | PR docs, ADRs, API documentation |
| 700-799 | Workflows | CI/CD, release processes, operational procedures |
| 800-899 | Project-Specific | Team/org-specific rules (reserved for customization) |
| 900-999 | Meta | Rule format, pack creation, tooling |

---

## 3. Policy Pack Schema

### 3.1 Pack Manifest

```clojure
(def PackManifest
  [:map
   [:pack/id string?]                       ; e.g., "terraform-safety"
   [:pack/name string?]                     ; Human-readable name
   [:pack/version string?]                  ; DateVer: "2026.01.22"
   [:pack/description string?]
   [:pack/author string?]
   [:pack/license {:optional true} string?] ; e.g., "Apache-2.0"
   [:pack/homepage {:optional true} string?]
   [:pack/repository {:optional true} string?]

   ;; For signed packs (paid feature)
   [:pack/signature {:optional true} string?]
   [:pack/signed-by {:optional true} string?]
   [:pack/signed-at {:optional true} inst?]

   ;; Dependencies on other packs
   [:pack/extends {:optional true}
    [:vector
     [:map
      [:pack-id string?]
      [:version-constraint {:optional true} string?]]]]  ; e.g., ">=2026.01.01"

   ;; Categories included in this pack
   [:pack/categories
    [:vector
     [:map
      [:category/id string?]                ; e.g., "300"
      [:category/name string?]
      [:category/rules [:vector keyword?]]]]]  ; Rule IDs in this category

   ;; The actual rules
   [:pack/rules [:vector Rule]]

   ;; Metadata
   [:pack/created-at inst?]
   [:pack/updated-at inst?]
   [:pack/changelog {:optional true} string?]])
```

### 3.2 Example Pack

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
                    :321-security-group-blast-radius
                    :322-vpc-modification-approval]}
  {:category/id "330"
   :category/name "State Safety"
   :category/rules [:330-state-drift-detection
                    :331-state-backup-required
                    :332-state-lock-verification]}]

 :pack/rules
 [{:rule/id :310-import-block-preservation
   :rule/title "Preserve import blocks during IMPORT tasks"
   :rule/description "When task type is IMPORT, never remove import blocks"
   :rule/severity :critical
   :rule/category "310"

   :rule/applies-to
   {:task-types #{:import}
    :file-globs ["**/*.tf" "**/terragrunt.hcl"]
    :resource-patterns [#"import\s*\{" #"terraform\s+import"]}

   :rule/detection
   {:type :diff-analysis
    :pattern #"^-\s*import\s*\{"
    :context-lines 5}

   :rule/agent-behavior
   "When Atlantis reports 'resource already exists':
    - WRONG interpretation: Remove import block (agent thinks import is done)
    - RIGHT interpretation: Keep import block (resource exists in AWS, import is working)

    Only remove import block when Atlantis reports 'already managed by Terraform'."

   :rule/enforcement
   {:action :hard-halt
    :message "Cannot remove import blocks during IMPORT task"
    :remediation "Restore the import block and re-run the plan"}

   :rule/examples
   [{:description "Removing import block during import task"
     :input "diff showing -import { to = aws_s3_bucket.example ..."
     :expected :fail
     :explanation "Import block removal forbidden during IMPORT task"}
    {:description "Keeping import block after successful import"
     :input "no changes to import blocks"
     :expected :pass}]}

  {:rule/id :320-network-recreation-block
   :rule/title "Block recreation of network resources"
   :rule/description "Prevent destroy-then-create operations on critical network infrastructure"
   :rule/severity :critical
   :rule/category "320"

   :rule/applies-to
   {:file-globs ["**/*.tf"]
    :resource-patterns [#"aws_route" #"aws_subnet" #"aws_nat_gateway"
                        #"aws_vpc" #"aws_security_group" #"aws_internet_gateway"]}

   :rule/detection
   {:type :plan-output
    :patterns [#"-/\+.*aws_route"
               #"-/\+.*aws_subnet"
               #"-/\+.*aws_nat_gateway"
               #"-/\+.*aws_vpc"
               #"-/\+.*aws_security_group"
               #"-/\+.*aws_internet_gateway"]}

   :rule/enforcement
   {:action :require-approval
    :approvers [:human :senior-engineer]
    :message "Network resource recreation detected - requires human approval"
    :remediation "Review the plan carefully. Consider using lifecycle { prevent_destroy = true }"}}]}
```

---

## 4. Pack Registry

### 4.1 Protocol

```clojure
(defprotocol PolicyPackRegistry
  ;; CRUD
  (register-pack [this pack]
    "Register a new pack or new version of existing pack")

  (get-pack [this pack-id]
    "Get latest version of pack")

  (get-pack-version [this pack-id version]
    "Get specific version of pack")

  (list-packs [this criteria]
    "List packs matching criteria {:category :author :search}")

  (delete-pack [this pack-id version]
    "Remove a pack version (admin only)")

  ;; Import/Export
  (import-pack [this source]
    "Import pack from URL, file path, or git repo")

  (export-pack [this pack-id version format]
    "Export pack to EDN, JSON, or directory structure")

  ;; Validation
  (validate-pack [this pack]
    "Validate pack schema and rule consistency")

  (verify-signature [this pack]
    "Verify pack signature (paid feature)")

  ;; Composition
  (resolve-pack [this pack-id]
    "Resolve pack with all extended packs merged")

  (get-rules-for-context [this pack-ids context]
    "Get applicable rules given task context"))
```

### 4.2 Rule Resolution

When multiple packs are active, rules are resolved by:

1. **Merge by category**: Rules from all packs are collected
2. **Override by ID**: Later packs override earlier packs for same rule ID
3. **Severity escalation**: Higher severity wins when rules conflict
4. **Enforcement escalation**: Stricter enforcement wins

```clojure
(defn resolve-rules [packs]
  (let [all-rules (mapcat :pack/rules packs)
        by-id (group-by :rule/id all-rules)]
    (map (fn [[id rules]]
           (reduce merge-rules rules))
         by-id)))

(defn merge-rules [base override]
  (-> base
      (merge override)
      (update :rule/severity max-severity)
      (update-in [:rule/enforcement :action] stricter-enforcement)))
```

---

## 5. File Format

### 5.1 Directory Structure

Packs can be distributed as directories:

```
terraform-safety/
├── pack.edn                    # Pack manifest
├── CHANGELOG.md                # Version history
├── README.md                   # Documentation
├── rules/
│   ├── 310-import-safety/
│   │   ├── 310-import-block-preservation.edn
│   │   ├── 311-import-no-creates.edn
│   │   └── 312-import-state-verification.edn
│   ├── 320-network-safety/
│   │   ├── 320-network-recreation-block.edn
│   │   └── ...
│   └── ...
└── examples/                   # Test cases
    ├── 310-examples.edn
    └── 320-examples.edn
```

### 5.2 Single-File Format

For simple packs, a single EDN file:

```clojure
;; terraform-safety.pack.edn
{:pack/id "terraform-safety"
 :pack/version "2026.01.22"
 ...
 :pack/rules [...]}
```

### 5.3 MDC Format (Human-Friendly)

Rules can also be authored as `.mdc` files (Markdown Concise):

```markdown
---
rule-id: 310-import-block-preservation
title: Preserve import blocks during IMPORT tasks
severity: critical
category: "310"
applies-to:
  task-types: [import]
  file-globs: ["**/*.tf", "**/terragrunt.hcl"]
enforcement:
  action: hard-halt
  message: Cannot remove import blocks during IMPORT task
---

# Import Block Preservation (CRITICAL)

When task type is IMPORT, never remove import blocks.

## Agent Behavior

When Atlantis reports 'resource already exists':
- **WRONG**: Remove import block (agent thinks import is done)
- **RIGHT**: Keep import block (resource exists in AWS, import is working)

Only remove import block when Atlantis reports 'already managed by Terraform'.

## Detection

```regex
^-\s*import\s*\{
```

Look for removed lines starting with `import {`.

## Examples

<example type="fail">
Diff showing `-import { to = aws_s3_bucket.example ...`
This is forbidden during an IMPORT task.
</example>

<example type="pass">
No changes to import blocks - plan proceeds normally.
</example>
```

---

## 6. Integration with Gates

### 6.1 PolicyPackGate

```clojure
(defrecord PolicyPackGate [id registry pack-ids]
  Gate
  (check [_this artifact context]
    (let [applicable-rules (get-rules-for-context registry pack-ids context)
          violations (check-rules applicable-rules artifact context)]
      (if (empty? violations)
        (pass-result id :policy-pack)
        (let [blocking (filter #(= :hard-halt (get-in % [:rule/enforcement :action])) violations)
              warnings (remove #(= :hard-halt (get-in % [:rule/enforcement :action])) violations)]
          (if (seq blocking)
            (fail-result id :policy-pack
                         (map violation->error blocking)
                         :warnings (map violation->warning warnings))
            (pass-result id :policy-pack
                         :warnings (map violation->warning violations)))))))

  (gate-id [_this] id)
  (gate-type [_this] :policy-pack))
```

### 6.2 Check Rules Implementation

```clojure
(defn check-rules [rules artifact context]
  (keep (fn [rule]
          (when (rule-applies? rule artifact context)
            (when-let [violation (detect-violation rule artifact context)]
              {:rule rule
               :violation violation
               :timestamp (java.time.Instant/now)})))
        rules))

(defn rule-applies? [rule artifact context]
  (let [{:keys [task-types file-globs resource-patterns repo-types phases]}
        (:rule/applies-to rule)]
    (and (or (nil? task-types)
             (contains? task-types (get-in context [:task :task/intent :intent/type])))
         (or (nil? file-globs)
             (some #(glob-matches? % (:artifact/path artifact)) file-globs))
         (or (nil? repo-types)
             (contains? repo-types (get-in context [:repo :repo/type])))
         (or (nil? phases)
             (contains? phases (:phase context))))))

(defn detect-violation [rule artifact context]
  (let [{:keys [type pattern patterns]} (:rule/detection rule)]
    (case type
      :content-scan
      (when (or (and pattern (re-find (re-pattern pattern) (:artifact/content artifact)))
                (and patterns (some #(re-find (re-pattern %) (:artifact/content artifact)) patterns)))
        {:type :content-match
         :matched (extract-match pattern (:artifact/content artifact))})

      :diff-analysis
      (when-let [diff (:artifact/diff artifact)]
        (when (or (and pattern (re-find (re-pattern pattern) diff))
                  (and patterns (some #(re-find (re-pattern %) diff) patterns)))
          {:type :diff-match
           :matched (extract-match pattern diff)}))

      :plan-output
      (when-let [plan (:terraform-plan context)]
        (analyze-plan-violations rule plan))

      :custom
      (when-let [custom-fn (resolve (:custom-fn (:rule/detection rule)))]
        (custom-fn artifact context))

      nil)))
```

---

## 7. Distribution

### 7.1 Git Submodule (Recommended)

```bash
# Add pack as submodule
git submodule add https://github.com/miniforge-packs/terraform-safety.git \
    .miniforge/packs/terraform-safety

# Pin to specific version
cd .miniforge/packs/terraform-safety
git checkout 2026.01.22

# Update to latest
git submodule update --remote .miniforge/packs/terraform-safety
```

### 7.2 Direct Import

```bash
# Import from URL
miniforge pack import https://github.com/miniforge-packs/terraform-safety.git

# Import from local path
miniforge pack import ./my-custom-pack/

# Import specific version
miniforge pack import terraform-safety@2026.01.22
```

### 7.3 Pack Configuration

```clojure
;; .miniforge/config.edn
{:packs
 [{:id "terraform-safety"
   :source {:type :git
            :url "https://github.com/miniforge-packs/terraform-safety.git"
            :version "2026.01.22"}}
  {:id "kubernetes-safety"
   :source {:type :git
            :url "https://github.com/miniforge-packs/kubernetes-safety.git"
            :version "2026.01.15"}}
  {:id "company-policies"
   :source {:type :local
            :path ".miniforge/packs/company-policies"}}]

 :pack-overrides
 {:320-network-recreation-block
  {:rule/enforcement {:action :warn}}}}  ; Downgrade to warning for this project
```

---

## 8. CLI Commands

```bash
# Pack management
miniforge pack list                          # List installed packs
miniforge pack search "terraform"            # Search marketplace
miniforge pack install terraform-safety      # Install latest
miniforge pack install terraform-safety@2026.01.22  # Install specific version
miniforge pack update terraform-safety       # Update to latest
miniforge pack remove terraform-safety       # Uninstall

# Pack inspection
miniforge pack show terraform-safety         # Show pack details
miniforge pack rules terraform-safety        # List all rules in pack
miniforge pack rule 310-import-preservation  # Show specific rule

# Pack authoring
miniforge pack init my-pack                  # Create new pack scaffold
miniforge pack validate ./my-pack            # Validate pack structure
miniforge pack test ./my-pack                # Run rule examples as tests
miniforge pack build ./my-pack               # Build distributable pack
miniforge pack publish ./my-pack             # Publish to marketplace (paid)

# Pack execution
miniforge check --packs terraform-safety,kubernetes-safety ./changes
miniforge check --all-packs ./changes        # Run all installed packs
```

---

## 9. Marketplace (Paid Feature)

### 9.1 Official Packs

Maintained by miniforge team:
- `miniforge-official/foundations` - Stratified Design, Simple Made Easy
- `miniforge-official/terraform-safety` - Terraform safety rules
- `miniforge-official/kubernetes-safety` - Kubernetes safety rules
- `miniforge-official/security-baseline` - Security best practices

### 9.2 Community Packs

Published by community:
- Discovery via search
- Rating and reviews
- Usage statistics
- Verified author badges

### 9.3 Enterprise Packs (Paid)

- **Signed packs**: Cryptographic verification of pack integrity
- **Private packs**: Org-only distribution
- **Compliance packs**: SOC2, HIPAA, PCI-DSS mappings
- **Pack analytics**: Usage and violation statistics

---

## 10. Deliverables

### Phase 1: Core Schema

- [ ] Rule schema in `components/schema/`
- [ ] Pack manifest schema
- [ ] Pack registry protocol
- [ ] In-memory registry implementation

### Phase 2: Gate Integration

- [ ] `PolicyPackGate` in `components/loop/`
- [ ] Rule detection implementations (content-scan, diff-analysis)
- [ ] Integration with existing gate runner

### Phase 3: File Format

- [ ] EDN pack loader
- [ ] Directory pack loader
- [ ] MDC rule parser

### Phase 4: CLI

- [ ] Pack management commands
- [ ] Pack authoring commands
- [ ] Pack execution commands

### Phase 5: Distribution

- [ ] Git submodule support
- [ ] Direct URL import
- [ ] Version constraint resolution

---

## 11. Default Packs

See [default-packs.spec](./default-packs.spec) for the initial set of official packs including:
- Foundations (Stratified Design, Simple Made Easy)
- Clojure conventions
- Terraform safety
- Kubernetes safety
- Security baseline

---

## 12. Open Questions

1. **Custom detection functions**: How to safely execute user-defined detection code?
2. **Pack signing**: Which signing mechanism (GPG, Sigstore, custom)?
3. **Conflict resolution**: What happens when two packs have conflicting rules?
4. **Performance**: How to efficiently check many rules against large diffs?
5. **Offline mode**: Should packs be fully cached for air-gapped environments?

---

## 13. References

- [change-train.spec](./change-train.spec) — PR Train integration
- [loop/gates.clj](../../components/loop/src/ai/miniforge/loop/gates.clj) — Existing gate system
- Kiddom cursor-rules — Dewey organization pattern
