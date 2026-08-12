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
(ns ai.miniforge.policy-pack.mapping.schema
  "Malli schemas for mapping artifacts. Split out of
   `ai.miniforge.policy-pack.mapping` (rule 210: the combined namespace
   measured 5 real layers, over the budget of 3) — the schema hierarchy
   stays here; resolution, projection, validation, and loading functions
   stay in the parent namespace.

   Layer 0: MappingType/MappingConfidence/MappingSystemRef
   Layer 1: MappingAuthorship (over MappingConfidence),
     MappingEntry (over MappingType)
   Layer 2: MappingArtifact (over MappingSystemRef + MappingEntry +
     MappingAuthorship)

   Related:
     specs/normative/N4-policy-packs.md §2.4 — Mapping artifact spec
     docs/design/policy-pack-taxonomy.md §2.4 — Four-artifact model design")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} MappingType
  "How closely the source satisfies the target."
  [:enum :exact :broad :partial :none])

(def ^{:stratum 0} MappingConfidence
  "Confidence in the mapping's accuracy."
  [:enum :high :medium :low :unvalidated])

(def ^{:stratum 0} MappingSystemRef
  "Reference to a source or target system."
  [:map
   [:mapping/system-kind [:enum :pack :taxonomy :framework]]
   [:mapping/system-id keyword?]
   [:mapping/system-version {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} MappingAuthorship
  "Provenance metadata for a mapping artifact."
  [:map
   [:publisher keyword?]
   [:confidence MappingConfidence]
   [:validated-at {:optional true} string?]])

(def ^{:stratum 1} MappingEntry
  "A single mapping between a source rule/category and a target control."
  [:map
   [:source/rule {:optional true} keyword?]
   [:source/category {:optional true} keyword?]
   [:target/control {:optional true} [:maybe string?]]
   [:mapping/type MappingType]
   [:mapping/notes {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} MappingArtifact
  "Schema for a mapping artifact — bridges between policy systems."
  [:map
   [:mapping/id keyword?]
   [:mapping/version string?]
   [:mapping/source MappingSystemRef]
   [:mapping/target MappingSystemRef]
   [:mapping/entries [:vector MappingEntry]]
   [:mapping/authorship {:optional true} MappingAuthorship]])
