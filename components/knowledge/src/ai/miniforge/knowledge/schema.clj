;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.knowledge.schema
  "Malli schemas for the knowledge component (Zettelkasten).
   Layer 0: Link schemas
   Layer 1: Source/provenance schemas
   Layer 2: Zettel schemas
   Layer 3: Query schemas"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Link schemas

(def LinkType
  "Types of connections between zettels."
  [:enum
   :supports      ; Evidence or argument supporting the target
   :contradicts   ; Conflicts with the target
   :extends       ; Adds detail or specializes the target
   :applies-to    ; Rule/concept applies to this context
   :example-of    ; Concrete example of an abstract concept
   :questions     ; Raises a question about the target
   :answers       ; Responds to a question
   :supersedes    ; Replaces/updates the target
   :related])     ; General association

(def Link
  "A connection between zettels with explicit rationale."
  [:map
   [:link/target-id uuid?]
   [:link/type LinkType]
   [:link/rationale [:string {:min 10}]]  ; Must explain WHY
   [:link/strength {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:link/bidirectional? {:optional true} boolean?]])

;------------------------------------------------------------------------------ Layer 1
;; Source/provenance schemas

(def SourceType
  "How this knowledge was created."
  [:enum
   :manual        ; Human-authored
   :inner-loop    ; Generated during repair cycle
   :meta-loop     ; Observed pattern across executions
   :import        ; Imported from external source
   :migration])   ; Migrated from existing rules

(def Source
  "Provenance information for a zettel."
  [:map
   [:source/type SourceType]
   [:source/agent {:optional true} keyword?]     ; Which agent role
   [:source/task-id {:optional true} uuid?]      ; Task that generated this
   [:source/context {:optional true} string?]    ; Additional context
   [:source/confidence {:optional true} [:double {:min 0.0 :max 1.0}]]])

;------------------------------------------------------------------------------ Layer 2
;; Zettel schemas

(def ZettelType
  "Categories of knowledge units."
  [:enum
   :rule         ; Constraint/convention (from .mdc files)
   :concept      ; Definition/explanation
   :learning     ; Insight from execution
   :example      ; Concrete code/pattern
   :hub          ; Structure note organizing others
   :question     ; Open question to resolve
   :decision])   ; ADR-style decision record

;; ----------------------------------------------------------------------------
;; Fleet-share fields (Decision 6 + 8 + 13 of the miniforge-fleet Phase E
;; planning doc). All optional — local Zettelkasten use does not require any
;; of them; they're populated when the zettel becomes a candidate for
;; cross-instance share via miniforge-fleet's event log.

(def ShareScope
  "Audience scope a zettel is intended for when shared via Fleet.
   Decision 14 — every event carries an audience scope, even on
   single-org deployments (multi-tenant retrofit otherwise becomes
   unbounded work)."
  [:enum :org :team :repo :workflow])

(def Classification
  "Privacy classification from Decision 8.
     :public-org  — distributable across the org-wide fleet.
     :internal    — org-only, not pushed beyond org boundaries.
     :restricted  — audience-limited; needs governance for embeddings-only
                    sharing on the Fleet ingest path.
     :secret      — same governance + scanner has higher veto bar
                    (`:fleet/shareable true` is a hard reject upstream)."
  [:enum :public-org :internal :restricted :secret])

;; ----------------------------------------------------------------------------

(def Zettel
  "An atomic unit of knowledge.

   Decision 6 of miniforge-fleet's Phase E planning doc requires every
   zettel that may cross an instance boundary to carry an immutable
   revision-id + a content digest, so trust applies to the immutable
   revision rather than to a mutable logical id. `zettel/create-zettel`
   stamps both; `zettel/update-zettel` rotates them when a content-bearing
   field changes. Local-only Zettelkasten use does not require the
   `:fleet/*` / `:privacy/*` / `:fleet/oss-version` fields — they're
   populated by producers that intend to ship the zettel through a
   Fleet boundary."
  [:map
   [:zettel/id uuid?]
   [:zettel/uid [:string {:min 1}]]              ; Human-readable ID
   [:zettel/title [:string {:min 1 :max 200}]]
   [:zettel/content [:string {:min 1}]]          ; Markdown body
   [:zettel/type ZettelType]
   [:zettel/dewey {:optional true} [:string {:min 3 :max 3}]]  ; "210"
   [:zettel/tags {:optional true} [:vector keyword?]]
   [:zettel/links {:optional true} [:vector Link]]
   [:zettel/backlinks {:optional true} [:vector uuid?]]  ; Computed
   [:zettel/source {:optional true} Source]
   [:zettel/created inst?]
   [:zettel/modified {:optional true} inst?]
   [:zettel/author [:string {:min 1}]]           ; "user" or "agent:role"

   ;; Revision-keyed identity (miniforge-fleet Decision 6). Optional in
   ;; the schema so legacy zettels round-trip; new zettels stamp these
   ;; via `zettel/create-zettel` and rotate via `zettel/update-zettel`.
   [:zettel/revision-id {:optional true} uuid?]
   [:zettel/digest      {:optional true} [:string {:min 64 :max 64}]]  ; SHA-256 hex

   ;; Fleet share intent (Decision 8). Producers that intend to share
   ;; a zettel set these explicitly; absence means "local-only".
   [:fleet/shareable        {:optional true} boolean?]
   [:fleet/share-scope      {:optional true} ShareScope]
   [:privacy/classification {:optional true} Classification]

   ;; Version provenance (Decision 13). Pin to the OSS version that
   ;; produced the zettel; Fleet's E.4 quarantine gate + E.9 migration
   ;; registry both key off this.
   [:fleet/oss-version {:optional true} [:string {:min 1}]]])

(def ZettelSummary
  "Lightweight zettel reference for listings."
  [:map
   [:zettel/id uuid?]
   [:zettel/uid string?]
   [:zettel/title string?]
   [:zettel/type ZettelType]
   [:zettel/dewey {:optional true} string?]
   [:zettel/tags {:optional true} [:vector keyword?]]])

;------------------------------------------------------------------------------ Layer 3
;; Query schemas

(def KnowledgeQuery
  "Query specification for retrieving relevant knowledge."
  [:map
   [:agent-role {:optional true} keyword?]
   [:task-type {:optional true} keyword?]
   [:tags {:optional true} [:vector keyword?]]
   [:dewey-range {:optional true} [:tuple string? string?]]  ; ["200" "299"]
   [:dewey-prefixes {:optional true} [:vector string?]]      ; ["210" "220"]
   [:include-types {:optional true} [:vector ZettelType]]
   [:exclude-types {:optional true} [:vector ZettelType]]
   [:min-strength {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:related-to {:optional true} [:or uuid? string?]]
   [:traverse-links? {:optional true} boolean?]
   [:max-hops {:optional true} [:int {:min 1 :max 5}]]
   [:limit {:optional true} [:int {:min 1}]]
   [:text-search {:optional true} string?]])

(def AgentManifest
  "Knowledge injection configuration for an agent role."
  [:map
   [:agent-role keyword?]
   [:dewey-prefixes {:optional true} [:vector string?]]
   [:tags {:optional true} [:vector keyword?]]
   [:types {:optional true} [:vector ZettelType]]
   [:hubs {:optional true} [:vector string?]]        ; Hub UIDs to include
   [:always-include {:optional true} [:vector string?]]  ; UIDs always injected
   [:max-zettels {:optional true} [:int {:min 1}]]])

(def LearningCapture
  "Input for capturing new learning from agent execution."
  [:map
   [:type SourceType]
   [:agent {:optional true} keyword?]
   [:task-id {:optional true} uuid?]
   [:title [:string {:min 1}]]
   [:content [:string {:min 1}]]
   [:tags {:optional true} [:vector keyword?]]
   [:dewey {:optional true} string?]
   [:links {:optional true} [:vector
                             [:map
                              [:target [:or uuid? string?]]  ; ID or UID
                              [:type LinkType]
                              [:rationale string?]]]]
   [:confidence {:optional true} [:double {:min 0.0 :max 1.0}]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate a zettel
  (m/validate Zettel
              {:zettel/id (random-uuid)
               :zettel/uid "210-clojure-ns"
               :zettel/title "Clojure Namespace Conventions"
               :zettel/content "# Namespace Conventions\n\nFollow Polylith..."
               :zettel/type :rule
               :zettel/dewey "210"
               :zettel/tags [:clojure :namespace]
               :zettel/created (java.util.Date.)
               :zettel/author "user"})
  ;; => true

  ;; Validate a link
  (m/validate Link
              {:link/target-id (random-uuid)
               :link/type :extends
               :link/rationale "This extends the base Clojure rule with namespace details"})
  ;; => true

  ;; Validate a query
  (m/validate KnowledgeQuery
              {:agent-role :implementer
               :tags [:clojure]
               :include-types [:rule :learning]
               :traverse-links? true})
  ;; => true

  :leave-this-here)
