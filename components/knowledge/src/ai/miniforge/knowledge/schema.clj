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

(def Zettel
  "An atomic unit of knowledge."
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
   [:zettel/author [:string {:min 1}]]])         ; "user" or "agent:role"

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
