---
title: Policy Pack Taxonomy System
description: Four-artifact model for extensible, interoperable compliance policy systems. Externalises the miniforge
  Dewey-derived taxonomy as a first-class published artifact and establishes the canonical design for policy packs,
  mapping bridges, and overlay extensions.
tags: [policy-pack, taxonomy, compliance, extensibility, m3]
---

# Policy Pack Taxonomy System

## Vision

The compliance scanner's Dewey numbering system was originally an internal ordering mechanism. This design externalises
it as a published, versioned taxonomy artifact — the first categorisation system in the miniforge policy ecosystem.

The key reframe: miniforge does not need a bespoke internal representation separate from its published system. The
platform's canonical taxonomy **is** the miniforge policy taxonomy. It happens to be Dewey-derived, and it is published
so others can adopt, extend, or map to it — but it is explicitly canonical, not "just another pack."

---

## Design Decisions

These four decisions are explicit and must not be left fuzzy:

**1. miniforge taxonomy is the platform canonical taxonomy.**
For now and the foreseeable future, miniforge normalises findings against its own taxonomy. The more general
"choose any canonical view" model is a later-stage feature. Calling it "just another pack" while treating it as the
required normalisation target would be architecturally dishonest.

**2. Rule IDs are the durable anchor.**
Rule IDs are what users put in ignore lists, what mappings key on, what change logs reference. They must be globally
unique, namespace-qualified keywords, and immutable after publication. Categories are classification and display — they
can be reorganised without touching rule IDs.

**3. Normalise to rule IDs and category IDs, not labels and not raw Dewey strings.**
Storage and inter-component contracts carry `:rule/id` and `:rule/categories` as structured keywords. The report
renderer resolves those to human-readable labels by looking up the loaded taxonomy. Dewey code strings (`"210"`) are
a display convenience produced at render time, not a stored identifier.

**4. Mapping artifacts are first-class and standalone.**
A pack may bundle convenience mappings, but the mapping loader must accept them as independent artifacts. miniforge
authors bridges to Vanta, SOC 2, ISO 27001; vendors author bridges to miniforge; customers author private bridges.
No side owns the mapping — it is a separate artifact with its own provenance.

---

## Four-Artifact Model

```
taxonomy          ← category tree, independently versioned
policy pack       ← ruleset + taxonomy ref + optional bundled mappings
mapping artifact  ← bridge between two systems (standalone, first-class)
overlay pack      ← extends a base pack with adds/overrides
```

Taxonomy and ruleset have different change velocities. A new category should not require every rule consumer to rev
their pack version. A rule severity fix should not imply a taxonomy revision. Structural separation, packaging optional:
a pack may distribute the taxonomy it references, but taxonomy must be independently identifiable and versionable.

---

## Artifact Schemas

### 1. Taxonomy Artifact

```edn
{:taxonomy/id         :miniforge/dewey
 :taxonomy/version    "1.0.0"
 :taxonomy/title      "Miniforge Canonical Taxonomy"

 :taxonomy/categories
 [;; Architecture
  {:category/id    :mf.cat/001
   :category/code  "001"
   :category/title "Stratified Design"
   :category/parent nil
   :category/order  1}

  {:category/id    :mf.cat/002
   :category/code  "002"
   :category/title "Code Quality"
   :category/parent nil
   :category/order  2}

  {:category/id    :mf.cat/003
   :category/code  "003"
   :category/title "Result Handling"
   :category/parent nil
   :category/order  3}

  {:category/id    :mf.cat/004
   :category/code  "004"
   :category/title "Validation Boundaries"
   :category/parent nil
   :category/order  4}

  {:category/id    :mf.cat/010
   :category/code  "010"
   :category/title "Simple Made Easy"
   :category/parent nil
   :category/order  10}

  {:category/id    :mf.cat/020
   :category/code  "020"
   :category/title "Specification Standards"
   :category/parent nil
   :category/order  20}

  {:category/id    :mf.cat/050
   :category/code  "050"
   :category/title "Localisation"
   :category/parent nil
   :category/order  50}

  ;; Language-specific
  {:category/id    :mf.cat/200
   :category/code  "200"
   :category/title "Language Standards"
   :category/parent nil
   :category/order  200}

  {:category/id    :mf.cat/210
   :category/code  "210"
   :category/title "Clojure"
   :category/parent :mf.cat/200
   :category/order  210}

  {:category/id    :mf.cat/220
   :category/code  "220"
   :category/title "Python"
   :category/parent :mf.cat/200
   :category/order  220}

  ;; Infrastructure
  {:category/id    :mf.cat/300
   :category/code  "300"
   :category/title "Infrastructure"
   :category/parent nil
   :category/order  300}

  {:category/id    :mf.cat/310
   :category/code  "310"
   :category/title "Polylith"
   :category/parent :mf.cat/300
   :category/order  310}

  {:category/id    :mf.cat/320
   :category/code  "320"
   :category/title "Kubernetes"
   :category/parent :mf.cat/300
   :category/order  320}

  ;; Quality
  {:category/id    :mf.cat/400
   :category/code  "400"
   :category/title "Testing"
   :category/parent nil
   :category/order  400}

  ;; Process
  {:category/id    :mf.cat/700
   :category/code  "700"
   :category/title "Process"
   :category/parent nil
   :category/order  700}

  {:category/id    :mf.cat/710
   :category/code  "710"
   :category/title "Git Branch Management"
   :category/parent :mf.cat/700
   :category/order  710}

  {:category/id    :mf.cat/715
   :category/code  "715"
   :category/title "Pre-commit Discipline"
   :category/parent :mf.cat/700
   :category/order  715}

  {:category/id    :mf.cat/720
   :category/code  "720"
   :category/title "Pull Requests"
   :category/parent :mf.cat/700
   :category/order  720}

  {:category/id    :mf.cat/721
   :category/code  "721"
   :category/title "PR Documentation"
   :category/parent :mf.cat/720
   :category/order  721}

  {:category/id    :mf.cat/722
   :category/code  "722"
   :category/title "PR Layering"
   :category/parent :mf.cat/720
   :category/order  722}

  {:category/id    :mf.cat/725
   :category/code  "725"
   :category/title "Git Worktrees"
   :category/parent :mf.cat/700
   :category/order  725}

  {:category/id    :mf.cat/730
   :category/code  "730"
   :category/title "Versioning"
   :category/parent :mf.cat/700
   :category/order  730}

  ;; Project Standards
  {:category/id    :mf.cat/800
   :category/code  "800"
   :category/title "Project Standards"
   :category/parent nil
   :category/order  800}

  {:category/id    :mf.cat/810
   :category/code  "810"
   :category/title "Copyright & Licensing"
   :category/parent :mf.cat/800
   :category/order  810}

  ;; Meta
  {:category/id    :mf.cat/900
   :category/code  "900"
   :category/title "Meta"
   :category/parent nil
   :category/order  900}]

 ;; Stable aliases — allow rules to reference a logical name insulated from reorganisation
 :taxonomy/aliases
 [{:alias/id :mf.cat/licensing    :alias/of :mf.cat/810}
  {:alias/id :mf.cat/versioning   :alias/of :mf.cat/730}
  {:alias/id :mf.cat/map-access   :alias/of :mf.cat/210}]}
```

**Notes:**

- `:category/code` carries the Dewey number as a display/ordering string. It is never used as a data key.
- `:category/order` drives ascending rule application order within a file (most foundational first).
- Aliases decouple rule category references from taxonomy reorganisation.

---

### 2. Policy Pack

```edn
{:pack/id           :miniforge/core
 :pack/version      "1.0.0"
 :pack/title        "Miniforge Core Policy Pack"
 :pack/description  "Standard rules for miniforge-managed Clojure/Polylith repositories."

 ;; Pack references the taxonomy it uses. min-version allows compatible upgrades
 ;; without requiring every pack to pin exact taxonomy versions.
 :pack/taxonomy-ref {:taxonomy/id          :miniforge/dewey
                     :taxonomy/min-version "1.0.0"}

 :pack/rules
 [{:rule/id          :mf.rule/clojure-map-access
   :rule/title       "Clojure Map Access Style"
   :rule/categories  [:mf.cat/210]
   :rule/severity    :warning
   :rule/auto-fix?   true
   :rule/description "Use (get m k default) over (or (:k m) default) for non-JSON maps."}

  {:rule/id          :mf.rule/copyright-header
   :rule/title       "Copyright Header"
   :rule/categories  [:mf.cat/810]
   :rule/severity    :error
   :rule/auto-fix?   true
   :rule/description "All source files must carry a valid copyright header."}

  {:rule/id          :mf.rule/datever-format
   :rule/title       "DateVer Version String"
   :rule/categories  [:mf.cat/730]
   :rule/severity    :error
   :rule/auto-fix?   true
   :rule/description "Version strings must follow DateVer X.Y.Z.N format."}

  {:rule/id          :mf.rule/stratified-design
   :rule/title       "Stratified Design"
   :rule/categories  [:mf.cat/001]
   :rule/severity    :error
   :rule/auto-fix?   false
   :rule/description "Code must be organised in layers with no upward dependencies."}

  ;; ... remaining rules elided for brevity; full list in resources/packs/miniforge-core-1.0.0.edn
  ]

 ;; Convenience bundled mappings — these are standalone mapping artifacts packaged here
 ;; for distribution. They can also be loaded independently.
 :pack/bundled-mappings
 [:miniforge-to-vanta/core-2026]}
```

**Rule schema fields:**

| Field | Required | Type | Notes |
|---|---|---|---|
| `:rule/id` | yes | namespaced keyword | Globally unique; never changes after publication |
| `:rule/title` | yes | string | Human-readable label for reports |
| `:rule/categories` | yes | `[keyword ...]` | One or more taxonomy category IDs; plural from day one |
| `:rule/severity` | yes | keyword | `:error :warning :info` |
| `:rule/auto-fix?` | yes | boolean | Whether a mechanical fix can be applied without review |
| `:rule/description` | yes | string | One-line description for tooling and reports |
| `:rule/deprecated-by` | no | keyword | Rule ID that supersedes this rule |

---

### 3. Mapping Artifact

```edn
{:mapping/id      :miniforge-to-vanta/core-2026
 :mapping/version "1.0.0"

 :mapping/source
 {:mapping/source-kind    :pack
  :mapping/source-id      :miniforge/core
  :mapping/source-version "1.0.0"}

 :mapping/target
 {:mapping/target-kind    :framework
  :mapping/target-id      :vanta
  :mapping/target-version "2026"}

 :mapping/entries
 [{:source/rule     :mf.rule/copyright-header
   :target/control  "CC6.2"
   :mapping/type    :exact
   :mapping/notes   "Direct requirement: source files must be attributed."}

  {:source/category :mf.cat/810
   :target/control  "CC6.1"
   :mapping/type    :broad
   :mapping/notes   "Category-level mapping; individual rules may vary in coverage."}

  {:source/rule     :mf.rule/clojure-map-access
   :target/control  nil
   :mapping/type    :none
   :mapping/notes   "No corresponding Vanta control; internal code quality only."}]

 :mapping/authorship
 {:publisher  :miniforge
  :confidence :high
  :validated-at "2026-04-05"}}
```

**Mapping types:**

| Type | Meaning |
|---|---|
| `:exact` | Source rule directly satisfies the target control |
| `:broad` | Category-level coverage; individual rules may vary |
| `:partial` | Source rule partially satisfies the target control |
| `:none` | Explicitly documented as having no mapping |

**Why provenance matters:** without `:mapping/type` and `:mapping/authorship`, bridge files become ungovernable as
soon as there are community-contributed mappings of varying quality. Confidence and validation date allow the platform
to warn when a mapping is unvalidated or stale relative to a framework version.

---

### 4. Overlay Pack

```edn
{:pack/id      :acme/internal-policy
 :pack/version "1.0.0"
 :pack/title   "ACME Internal Policy Extension"

 ;; Inherit taxonomy ref and rule set from all extended packs.
 ;; Overlay pack does not need to redeclare the taxonomy.
 :pack/extends
 [{:pack/id      :miniforge/core
   :pack/version "1.0.0"}]

 ;; Additional rules use the inherited taxonomy
 :pack/rules
 [{:rule/id          :acme.rule/internal-banner
   :rule/title       "Internal Use Banner"
   :rule/categories  [:mf.cat/810]
   :rule/severity    :error
   :rule/auto-fix?   false
   :rule/description "Internal files must carry the ACME internal use notice."}]

 ;; Severity and enable/disable overrides on inherited rules
 :pack/overrides
 [{:rule/id       :mf.rule/datever-format
   :rule/severity :error}     ; promote from :warning

  {:rule/id       :mf.rule/stratified-design
   :rule/enabled? false}]}    ; disable for this org
```

**Overlay resolution rules:**

1. Inherited rules are merged from all `:pack/extends` entries in declaration order.
2. Overlay `:pack/rules` are appended; IDs must not collide with inherited rules.
3. `:pack/overrides` apply last; only `:rule/severity` and `:rule/enabled?` may be overridden.
4. Taxonomy ref is inherited from the base pack(s). An overlay declaring a different taxonomy is invalid.

---

## Runtime Model

### Loading

```
load-taxonomy(miniforge/dewey@1.0.0)
  → taxonomy index: { :mf.cat/210 → {:category/title "Clojure" :category/order 210} ... }

load-pack(miniforge/core@1.0.0)
  → resolve taxonomy ref → validate all :rule/categories exist in taxonomy
  → rule index: { :mf.rule/copyright-header → {...} ... }

load-pack(acme/internal-policy@1.0.0)
  → resolve extends → merge inherited rules + local rules + apply overrides
  → combined rule index
```

### Taxonomy Version Resolution

When multiple packs are loaded simultaneously, the host taxonomy wins. Each pack declares
`:taxonomy/min-version`; if the loaded taxonomy version satisfies all pack min-versions, loading
proceeds. Incompatible version requirements are a load-time error, not a runtime one.

This is the "host taxonomy wins" rule made explicit. It avoids the diamond problem: two packs
requiring different taxonomy versions do not create a conflict unless the host taxonomy cannot
satisfy both min-versions simultaneously.

### Normalisation

The compliance scanner normalises all findings to:

```edn
{:rule/id         :mf.rule/copyright-header   ; stable anchor
 :rule/title      "Copyright Header"           ; from loaded pack (for display)
 :rule/categories [:mf.cat/810]               ; from loaded pack
 :rule/pack       :miniforge/core              ; provenance
 :file            "components/foo/src/..."
 :line            12
 :current         "(missing copyright header)"
 :suggested       "Copyright 2026 ..."
 :auto-fix?       true
 :rationale       "Copyright header absent; can be prepended"}
```

**Not in storage:** Dewey code strings, category labels, framework control IDs. Those are produced
at render time by looking up the loaded taxonomy and any requested mapping artifacts.

### Report Projection

The report renderer resolves categories to display labels at render time:

```
:mf.cat/810 → taxonomy lookup → "Copyright & Licensing (810)"
```

With a mapping loaded:

```
:mf.rule/copyright-header → mapping lookup → "Vanta CC6.2"
```

Reports can be projected through any loaded mapping without changing stored findings.

---

## miniforge/core — the Canonical Pack

`miniforge/core` is the default pack loaded by the compliance scanner. It is not special in schema
terms, but it is the platform's canonical policy set. Third-party packs extend or map to it.

The canonical taxonomy (`miniforge/dewey`) and the canonical pack (`miniforge/core`) are distributed
as resources within the `policy-pack` component:

```
components/policy-pack/resources/
  packs/
    miniforge-core-1.0.0.edn
  taxonomies/
    miniforge-dewey-1.0.0.edn
  mappings/
    miniforge-to-vanta-core-2026-1.0.0.edn
```

---

## M3 Component Work

### 1. `policy-pack` component — taxonomy and pack loading

Add to the existing `policy-pack` interface:

- `load-taxonomy` — parse and index a taxonomy EDN file
- `load-pack` — parse pack, resolve taxonomy ref, validate rule categories, merge overlays
- `load-mapping` — parse and index a mapping artifact
- `resolve-category` — look up `:category/title` and `:category/order` for a category ID
- `resolve-mapping` — translate a rule ID or category ID to a target framework control

Ship `miniforge-dewey-1.0.0.edn` and `miniforge-core-1.0.0.edn` as bundled resources.

### 2. `compliance-scanner` — migrate to pack-loaded rule definitions

Replace hardcoded Dewey strings in `scanner_registry.clj` with pack-loaded rule references.
The scanner registry becomes a runtime index built from the loaded pack, not a compile-time map.

Violation maps produced by the scanner carry `:rule/id` and `:rule/categories` (structured keywords),
not `:rule/dewey` (a string). The plan phase sorts nodes by `:category/order` looked up from the
taxonomy, not by parsing a Dewey string.

### 3. Report renderer — taxonomy-driven display

Replace inline Dewey string formatting with taxonomy lookups:

- Section headers: `(format-category-header (resolve-category taxonomy :mf.cat/810))`
  → `"Copyright & Licensing (810) —"`
- PR titles: `fix: [810] Copyright Header compliance pass`
- Mapping annotation (optional): append framework control ID when a mapping is loaded

### 4. `policy-selector` — category and rule ID selectors

Replace `"dewey.210"` string selectors with structured alternatives:

| Selector | Meaning |
|---|---|
| `:always-apply` | All rules with default enabled |
| `:all` | Every rule in every loaded pack |
| `#{:mf.rule/copyright-header}` | Specific rules by ID |
| `#{:mf.cat/810}` | All rules in a category |
| `"mf.cat/8xx"` | Prefix wildcard over category codes |

---

## Relationship to N4 Policy Packs Standard

This design extends `N4-policy-packs.md`. The existing gate execution contract, scanner protocol,
and knowledge-safety sections are orthogonal and unchanged. N4 gains:

- Taxonomy artifact schema (new Section 2.1)
- Updated pack schema with `:pack/taxonomy-ref` and `:pack/extends`
- Updated rule schema with `:rule/categories` (plural keyword vector) and `:rule/id` as keyword
- Mapping artifact schema (new Section 2.x)
- Overlay pack schema and resolution rules (new Section 2.x)

See `specs/normative/N4-policy-packs.md` for the normative contract.

---

## Open Questions

1. **Taxonomy evolution governance** — Who approves additions to `miniforge/dewey`? Initial answer:
   miniforge team owns it; the taxonomy is an open published artifact and PRs are welcome. This is
   the same model as OpenTelemetry semantic conventions.

2. **Overlay taxonomy swap** — Can an overlay reference a completely different taxonomy? Initial
   answer: no. An overlay must use the same taxonomy as its base packs. Multi-taxonomy composition
   is deferred to a later design.

3. **Fleet aggregate reporting** — When the scanner runs across multiple repos, do findings normalise
   to the same rule IDs? Yes — rule IDs are pack-scoped, not repo-scoped. A fleet report groups
   violations by `:rule/id` across all repos.

4. **Pack registry** — Where do third-party packs live? Out of scope for M3. In-scope: local file
   path and embedded resources. A registry (miniforge Hub or similar) is a later feature.
