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
(ns ai.miniforge.bb-test-runner.diagnostic-plan
  "Parse stable-derived diagnostic CLI arguments and assemble the
   resulting test plan. Thin orchestration on top of `stable-derived`
   (project selectors, ordering, expand/bisect grouping) — kept in its
   own namespace because `parse-diagnostic-args` sits one layer above
   its own `assoc-long-arg`/`parse-long-arg` helpers, and folding those
   into `stable-derived` would extend that namespace's own longest
   chain past budget (this is exactly the shape that made the
   pre-split file report 5 layers).

   Layer 0: `parse-long-arg` (only same-namespace dependency is
   `stable-derived/parse-error`, so it's a leaf here) and
   `diagnostic-test-plan` (every dependency — `order-projects`,
   `expand-project-groups`, `bisect-project-groups`,
   `format-project-selector` — is a cross-namespace call to
   `stable-derived`, so it has no same-file dependency either).
   Layer 1: `assoc-long-arg`, built on `parse-long-arg`.
   Layer 2: `parse-diagnostic-args`, built on `assoc-long-arg`."
  (:require [ai.miniforge.bb-test-runner.stable-derived :as stable-derived]
            [clojure.string :as str])
  (:import
   [java.lang Long NumberFormatException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} parse-long-arg
  [arg prefix]
  (let [raw (subs arg (count prefix))]
    (try
      {:ok? true :data (Long/parseLong raw)}
      (catch NumberFormatException ex
        (stable-derived/parse-error :bb-test-runner/invalid-diagnostic-arg
                                    "Invalid stable-derived diagnostic argument."
                                    {:arg arg
                                     :prefix prefix
                                     :expected-format (str prefix "N")
                                     :cause (.getMessage ex)})))))

(defn ^{:stratum 0} diagnostic-test-plan
  "Return a stable-derived diagnostic plan over an explicit project
   vector."
  [{:keys [mode projects start-size direction order seed]}]
  (let [effective-mode (or mode :subset)
        ordered-projects (stable-derived/order-projects
                          (vec projects)
                          {:direction direction
                           :order order
                           :seed seed})
        project-groups (if (empty? ordered-projects)
                         []
                         (case effective-mode
                           :expand (stable-derived/expand-project-groups ordered-projects start-size)
                           :bisect (stable-derived/bisect-project-groups ordered-projects)
                           [(vec ordered-projects)]))
        total-groups (count project-groups)]
    {:mode effective-mode
     :summary (str "Running "
                   (name effective-mode)
                   " diagnostics across "
                   (count ordered-projects)
                   " stable-derived projects.")
     :projects ordered-projects
     :steps (mapv (fn [index group]
                    (let [selector (stable-derived/format-project-selector group)]
                      {:label (str (name effective-mode)
                                 " project subset "
                                 (inc index) "/"
                                 total-groups
                                 " ("
                                 (count group)
                                 " projects)")
                       :argv (cond-> ["clojure" "-M:poly" "test"]
                               selector (conj selector))}))
                  (range)
                  project-groups)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assoc-long-arg
  [acc k arg prefix]
  (let [parsed (parse-long-arg arg prefix)]
    (if (:ok? parsed)
      (assoc acc k (:data parsed))
      (reduced parsed))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-diagnostic-args
  "Parse supported stable-derived diagnostic CLI arguments.

   Supported forms:
   - `mode:subset|expand|bisect`
   - `project:proj1:proj2`
   - `start-size:N`
   - `direction:front|back`
   - `order:declared|random`
   - `seed:N`"
  [args]
  (reduce
   (fn [acc arg]
     (cond
       (str/starts-with? arg "mode:")
       (assoc acc :mode (keyword (subs arg (count "mode:"))))

       (str/starts-with? arg "project:")
       (assoc acc :projects (stable-derived/parse-project-selector arg))

       (str/starts-with? arg "start-size:")
       (assoc-long-arg acc :start-size arg "start-size:")

       (str/starts-with? arg "direction:")
       (assoc acc :direction (keyword (subs arg (count "direction:"))))

       (str/starts-with? arg "order:")
       (assoc acc :order (keyword (subs arg (count "order:"))))

       (str/starts-with? arg "seed:")
       (assoc-long-arg acc :seed arg "seed:")

       :else acc))
   {}
   args))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (parse-diagnostic-args ["mode:expand" "project:miniforge:miniforge-core" "start-size:2"])
  (diagnostic-test-plan {:mode :expand :projects ["a" "b" "c"] :start-size 1})

  :leave-this-here)
