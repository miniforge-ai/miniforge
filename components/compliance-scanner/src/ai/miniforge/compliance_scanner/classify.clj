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
  (:require [ai.miniforge.compliance-scanner.messages :as msg]
            [clojure.string :as str]))

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

(defn- classify-clojure-map-access
  "Classify a :std/clojure violation (Clojure Map Access standard)."
  [violation]
  (if (json-context? violation)
    (assoc violation
           :auto-fixable? false
           :rationale     (msg/t :classify/json-context))
    (assoc violation
           :auto-fixable? true
           :rationale     (msg/t :classify/literal-default))))

(defn- classify-datever
  "Classify a :std/datever violation (DateVer version format standard).
   Always needs-review — SemVer→DateVer requires human context (release date)."
  [violation]
  (assoc violation
         :auto-fixable? false
         :rationale     (msg/t :classify/datever)))

(defn- classify-copyright-header
  "Classify a :std/header-copyright violation (copyright header standard)."
  [violation]
  (let [current (get violation :current "")]
    (if (= current (msg/t :scan/missing-header))
      ;; Header is completely absent — can be prepended automatically
      (assoc violation
             :auto-fixable? true
             :rationale     (msg/t :classify/header-absent))
      ;; Header exists but content is wrong — needs manual review
      (assoc violation
             :auto-fixable? false
             :rationale     (msg/t :classify/header-incorrect)))))

(defn- classify-one
  "Dispatch classification for a single violation by :rule/id."
  [violation]
  (case (get violation :rule/id)
    :std/clojure          (classify-clojure-map-access violation)
    :std/datever          (classify-datever violation)
    :std/header-copyright (classify-copyright-header violation)
    ;; Unknown rule — default to needs-review
    (assoc violation
           :auto-fixable? false
           :rationale     (msg/t :classify/unknown-rule))))

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
   [{:rule/id :std/clojure :rule/category "210"
     :file    "components/foo/src/ai/miniforge/foo/core.clj"
     :current "(or (:timeout m) 5000)"}
    {:rule/id :std/clojure :rule/category "210"
     :file    "server/handler.clj"
     :current "(or (:status m) :pending)"}
    {:rule/id :std/datever :rule/category "730"
     :file    "build.clj"
     :current "1.2.3"}
    {:rule/id :std/header-copyright :rule/category "810"
     :file    "docs/guide.md"
     :current "(missing copyright header)"}])

  :leave-this-here)
