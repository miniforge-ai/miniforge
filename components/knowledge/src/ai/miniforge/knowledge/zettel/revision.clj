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
(ns ai.miniforge.knowledge.zettel.revision
  "Content identity and revision stamping for zettels."
  (:require
   [ai.miniforge.content-hash.interface :as content-hash])
  (:import [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} content-bearing-fields
  "Fields whose values determine a zettel revision's identity."
  [:zettel/uid :zettel/title :zettel/content :zettel/type :zettel/dewey
   :zettel/tags :zettel/links :zettel/source])

(def ^{:stratum 0} derived-fields
  "Fields maintained by lifecycle operations rather than callers."
  #{:zettel/digest :zettel/revision-id :zettel/trust-level})

(defn ^{:stratum 0} revision-id-from-digest
  "Derive the stable revision UUID for a digest."
  ^UUID [^String digest]
  (UUID/nameUUIDFromBytes (.getBytes digest "UTF-8")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} content-projection
  "Project a zettel to the fields that determine its revision identity."
  [zettel]
  (select-keys zettel content-bearing-fields))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compute-digest
  "Return the canonical content digest for a zettel."
  [zettel]
  (content-hash/content-hash (content-projection zettel)))
