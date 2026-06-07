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

(ns ai.miniforge.policy-pack.interface.mapping
  "Mapping artifact loading, validation, resolution, and report projection."
  (:require
   [ai.miniforge.policy-pack.mapping :as mapping]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def MappingArtifact
  "Malli schema (a `[:map ...]` vector) for a mapping artifact — an independent,
   versioned bridge between a source and target policy system, carrying entries
   and optional authorship."
  mapping/MappingArtifact)

(def MappingEntry
  "Malli schema (a `[:map ...]` vector) for one mapping entry — an optional
   source rule/category, an optional target control, a :mapping/type, and
   optional notes."
  mapping/MappingEntry)

(def MappingAuthorship
  "Malli schema (a `[:map ...]` vector) for mapping provenance — :publisher,
   :confidence, and an optional :validated-at."
  mapping/MappingAuthorship)

(def MappingType
  "Malli enum schema (`[:enum :exact :broad :partial :none]`) for how closely a
   source satisfies a target."
  mapping/MappingType)

;------------------------------------------------------------------------------ Layer 0
;; Validation

(def valid-mapping?
  "Predicate. Returns true when the value conforms to the MappingArtifact
   schema, false otherwise."
  mapping/valid-mapping?)

(def validate-mapping
  "Validate a mapping artifact against the MappingArtifact schema. Returns
   {:valid? bool :errors map-or-nil}."
  mapping/validate-mapping)

;------------------------------------------------------------------------------ Layer 1
;; Loading

(def load-mapping
  "Load a mapping artifact from an EDN file. (load-mapping path) returns
   {:success? true :mapping <MappingArtifact>} or
   {:success? false :error <message>} on read or validation failure."
  mapping/load-mapping)

;------------------------------------------------------------------------------ Layer 2
;; Resolution

(def resolve-mapping
  "Resolve a mapping against a pack, enriching each entry with match status.
   (resolve-mapping mapping pack) returns a vector of entry maps each carrying
   :matched? (and :rule-title when a source rule matched)."
  mapping/resolve-mapping)

(def project-report
  "Project a mapping onto a pack to produce a coverage report.
   (project-report mapping pack) returns
   {:target-controls [{:control :type :matched? :source :notes} ...]
   :summary {:exact :broad :partial :none :unmatched}}."
  mapping/project-report)
