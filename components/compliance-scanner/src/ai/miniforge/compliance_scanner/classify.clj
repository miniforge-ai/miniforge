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

(ns ai.miniforge.compliance-scanner.classify
  "Classify violations: add :auto-fixable? and :rationale to each violation.

   Layer 0: Per-rule classification predicates
   Layer 1: Top-level classify-violations entry point"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Per-rule classification predicates

(def ^:private json-signal-keys
  "Field names that suggest the key is part of a JSON/external schema."
  #{":type" ":priority" ":status"})

(defn- json-context?
  "Return true if the violation line looks like it might be a JSON-mapped field.

   Heuristics:
   1. File is under a server/ path
   2. The current (matched) text contains a known JSON-signal key
   3. The current text contains a ;; JSON comment signal"
  [violation]
  (let [file    (get violation :file "")
        current (get violation :current "")]
    (or (str/includes? file "server/")
        (some #(str/includes? current %) json-signal-keys)
        (str/includes? current ";; JSON"))))

(defn- classify-210
  "Classify a Dewey 210 violation."
  [violation]
  (if (json-context? violation)
    (assoc violation
           :auto-fixable? false
           :rationale     "Possible JSON-mapped field — manual review required")
    (assoc violation
           :auto-fixable? true
           :rationale     "Literal default, non-JSON field")))

(defn- classify-730
  "Classify a Dewey 730 violation."
  [violation]
  (assoc violation
         :auto-fixable? true
         :rationale     "SemVer X.Y.Z found where DateVer X.Y.Z.N is required; append build count .0"))

(defn- classify-810
  "Classify a Dewey 810 violation."
  [violation]
  (let [current (get violation :current "")]
    (if (= current "(missing copyright header)")
      ;; Header is completely absent — can be prepended automatically
      (assoc violation
             :auto-fixable? true
             :rationale     "Copyright header absent; can be prepended")
      ;; Header exists but content is wrong — needs manual review
      (assoc violation
             :auto-fixable? false
             :rationale     "Copyright header content incorrect; manual correction required"))))

(defn- classify-one
  "Dispatch classification for a single violation by Dewey code."
  [violation]
  (case (get violation :rule/dewey)
    "210" (classify-210 violation)
    "730" (classify-730 violation)
    "810" (classify-810 violation)
    ;; Unknown rule — default to needs-review
    (assoc violation
           :auto-fixable? false
           :rationale     "Unknown rule; manual review required")))

;------------------------------------------------------------------------------ Layer 1
;; Top-level entry point

(defn classify-violations
  "Add :auto-fixable? and :rationale to each violation.
   Returns updated violation list."
  [violations]
  (mapv classify-one violations))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify-violations
   [{:rule/dewey "210"
     :file       "components/foo/src/ai/miniforge/foo/core.clj"
     :current    "(or (:timeout m) 5000)"}
    {:rule/dewey "210"
     :file       "server/handler.clj"
     :current    "(or (:status m) :pending)"}
    {:rule/dewey "730"
     :file       "build.clj"
     :current    "1.2.3"}
    {:rule/dewey "810"
     :file       "docs/guide.md"
     :current    "(missing copyright header)"}])

  :leave-this-here)
