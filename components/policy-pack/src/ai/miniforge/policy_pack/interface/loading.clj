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

(ns ai.miniforge.policy-pack.interface.loading
  "Pack loading and serialization helpers."
  (:require
   [ai.miniforge.policy-pack.loader :as loader]))

;------------------------------------------------------------------------------ Layer 0
;; Loading operations

(def load-pack
  "Load a policy pack from a path, auto-detecting single-EDN-file vs directory
   layout. Returns {:success? bool :pack PackManifest :errors [...]}."
  loader/load-pack)

(def load-pack-from-file
  "Load a policy pack from a single EDN manifest file (pack.edn or
   *.pack.edn). Returns {:success? bool :pack PackManifest :errors [...]}."
  loader/load-pack-from-file)

(def load-pack-from-directory
  "Load a policy pack from a directory (pack.edn manifest plus optional rules/
   subtree; directory rules override inline rules by :rule/id). Returns
   {:success? bool :pack PackManifest :errors [...]}."
  loader/load-pack-from-directory)

(def resolve-overlay
  "Resolve an overlay pack by merging inherited rules from its :pack/extends
   base packs (looked up in pack-store), appending overlay rules, applying
   :pack/overrides, and inheriting the taxonomy ref. Returns
   {:success? true :pack <resolved PackManifest>} or
   {:success? false :errors [...]} on missing base, taxonomy conflict, or
   rule-id collision."
  loader/resolve-overlay)

(def discover-packs
  "Discover packs in a directory: top-level *.pack.edn files and subdirectories
   containing pack.edn. Returns a vector of {:path string :type :file|:directory}
   (nil when the directory does not exist)."
  loader/discover-packs)

(def load-all-packs
  "Load every pack discovered in a directory. Arity [packs-dir] or
   [packs-dir opts] (opts: :validate-dependencies? default true,
   :max-dependency-depth default 5). Returns {:loaded [PackManifest...]
   :failed [{:path :errors}...] :dependency-validation {...}?}."
  loader/load-all-packs)

(def write-pack-to-file
  "Serialize a PackManifest to an EDN file via pprint. (write-pack-to-file pack
   file-path) returns {:success? bool :error string-or-nil}."
  loader/write-pack-to-file)
