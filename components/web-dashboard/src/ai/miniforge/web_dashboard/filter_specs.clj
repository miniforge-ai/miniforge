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

(ns ai.miniforge.web-dashboard.filter-specs
  "Filter specification loading and management."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.web-dashboard.filter-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Derived field registry

(def derived-fields
  "Registry of derived field extractors.
   Maps derived-id -> extraction function."
  {:derived/entity-status
   (fn [item]
     (or (:status item)
         (:train/status item)
         (get-in item [:execution/status])))

   :derived/repository
   (fn [item]
     (or (:repo item)
         (:pr/repo item)
         (some-> item :train/prs first :pr/repo)))

   :derived/train-name
   (fn [item]
     (or (:train/name item)
         (:train-name item)))

   :derived/has-testing
   (fn [item]
     (boolean (some #(= :test (:phase/id %))
                    (get-in item [:workflow/phases] []))))

   :derived/has-review
   (fn [item]
     (boolean (some #(= :verify (:phase/id %))
                    (get-in item [:workflow/phases] []))))

   :derived/phase-count
   (fn [item]
     (count (get-in item [:workflow/phases] [])))

   :derived/has-evidence
   (fn [item]
     (boolean (or (:train/evidence-bundle-id item)
                  (:evidence-bundle-id item))))

   :derived/is-completed
   (fn [item]
     (or (= :completed (:status item))
         (= :merged (:train/status item))
         (= :completed (get-in item [:execution/status]))))

   :derived/has-dependencies
   (fn [item]
     (boolean (or (seq (:dependencies item))
                  (seq (:pr/depends-on item))
                  (some (comp seq :pr/depends-on) (:train/prs item)))))

   :derived/has-blocking-prs
   (fn [item]
     (boolean (seq (:train/blocking-prs item))))})

;------------------------------------------------------------------------------ Layer 1
;; Spec loading

(defn load-filter-specs
  "Load filter specifications from EDN file.

   Arguments:
   - resource-path: Path to EDN file in resources

   Returns: Vector of filter specs"
  [resource-path]
  (try
    (when-let [resource (io/resource resource-path)]
      (let [specs (edn/read-string (slurp resource))]
        (if (schema/valid-filter-specs? specs)
          specs
          (do
            (println "Invalid filter specs:" (schema/explain-filter-spec specs))
            []))))
    (catch Exception e
      (println "Error loading filter specs:" (ex-message e))
      [])))

;; Atom holding loaded filter specifications
(defonce filter-specs-atom
  (atom (load-filter-specs "filters/default-filters.edn")))

(defn get-filter-specs
  "Get currently loaded filter specifications."
  []
  @filter-specs-atom)

(defn reload-filter-specs!
  "Reload filter specifications from file."
  []
  (reset! filter-specs-atom (load-filter-specs "filters/default-filters.edn")))

(defn add-custom-filter-spec!
  "Add a custom filter specification at runtime.

   Arguments:
   - spec: Filter specification map

   Returns: true if added, false if invalid"
  [spec]
  (if (schema/valid-filter-spec? spec)
    (do
      (swap! filter-specs-atom conj spec)
      true)
    (do
      (println "Invalid filter spec:" (schema/explain-filter-spec spec))
      false)))

;------------------------------------------------------------------------------ Layer 2
;; Spec queries

(defn get-applicable-filters
  "Get all filters applicable to a pane, grouped by scope.

   For global filters: Returns ALL global filters (they apply everywhere).
   For local filters: Returns only filters applicable to the specific pane.

   Returns:
   {:global [...all global filter specs...]
    :local  [...local filter specs for this pane...]}"
  [pane]
  (let [specs (get-filter-specs)]
    {:global (filter #(= :global (:filter/scope %)) specs)
     :local (filter #(and (= :local (:filter/scope %))
                         (contains? (:filter/applicable-to %) pane))
                   specs)}))

(defn get-filter-spec-by-id
  "Get a filter spec by its ID."
  [filter-id]
  (first (filter #(= filter-id (:filter/id %)) (get-filter-specs))))
