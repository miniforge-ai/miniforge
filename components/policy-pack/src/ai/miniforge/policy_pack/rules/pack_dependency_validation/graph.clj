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
(ns ai.miniforge.policy-pack.rules.pack-dependency-validation.graph
  "Dependency-graph construction, circular/missing-dependency
   detection, and depth-limit checking for pack dependency validation
   (N4 §2.4.2). Split out of
   `ai.miniforge.policy-pack.rules.pack-dependency-validation` (rule
   210, SL003 split train, slice 3/3 — the final slice, bringing the
   parent to 3 real layers).

   Layer 0: get-pack-dependencies, detect-circular-dependencies,
     detect-missing-dependencies, calculate-pack-depths
   Layer 1: build-dependency-graph (over get-pack-dependencies),
     detect-depth-violations (over calculate-pack-depths)"
  (:require
   [ai.miniforge.algorithms.interface :as alg]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Dependency graph construction
(defn ^{:stratum 0} get-pack-dependencies
  "Extract dependencies from a pack manifest.
   Returns vector of {:pack-id string :version-constraint string}."
  [pack]
  (get pack :pack/extends []))

;; Validation rules
(defn ^{:stratum 0} detect-circular-dependencies
  "Detect circular dependencies in the graph.
   Returns vector of violation maps:
   [{:type :circular-dependency
     :cycle [pack-id-1 pack-id-2 ... pack-id-1]
     :message string}]"
  [graph]
  (let [pack-ids (keys graph)
        cycles (alg/dfs-collect
                graph
                pack-ids
                (fn [node] (map :pack-id (:deps node)))
                ;; Collect cycle when detected
                (fn [pack-id path _visited _visiting]
                  (let [cycle-start-idx (.indexOf (vec path) pack-id)
                        cycle (if (>= cycle-start-idx 0)
                                (conj (vec (drop cycle-start-idx path)) pack-id)
                                [pack-id])]
                    cycle))
                :cycle)]
    (->> cycles
         (distinct)
         (map (fn [cycle]
                {:type :circular-dependency
                 :cycle cycle
                 :message (str "Circular dependency detected: "
                              (str/join " → " cycle))}))
         vec)))

(defn ^{:stratum 0} detect-missing-dependencies
  "Detect missing dependencies in the graph.
   Returns vector of violation maps:
   [{:type :missing-dependency
     :pack-id string
     :missing-dep string
     :message string}]"
  [graph by-id]
  (let [available-ids (set (keys by-id))]
    (->> graph
         (mapcat (fn [[pack-id node]]
                   (let [deps (map :pack-id (:deps node))
                         missing (remove available-ids deps)]
                     (map (fn [dep-id]
                            {:type :missing-dependency
                             :pack-id pack-id
                             :missing-dep dep-id
                             :message (str "Pack '" pack-id "' requires missing dependency '" dep-id "'")})
                          missing))))
         vec)))

(defn ^{:stratum 0} calculate-pack-depths
  "Calculate maximum depth for each pack using bottom-up traversal.
   Returns map of pack-id -> {:depth int :chain [pack-ids]}."
  [graph]
  (letfn [(calc-depth [pack-id visited depths]
            (cond
              ;; Already calculated
              (contains? depths pack-id)
              [depths (get depths pack-id)]

              ;; Cycle detected
              (contains? visited pack-id)
              [depths {:depth 0 :chain []}]

              ;; Calculate depth
              :else
              (let [node (get graph pack-id)
                    deps (map :pack-id (:deps node))
                    new-visited (conj visited pack-id)]
                (if (empty? deps)
                  (let [result {:depth 1 :chain [pack-id]}]
                    [(assoc depths pack-id result) result])
                  (loop [remaining-deps deps
                         d depths
                         child-results []]
                    (if (empty? remaining-deps)
                      (let [max-child (apply max-key :depth child-results)
                            depth (inc (:depth max-child))
                            chain (cons pack-id (:chain max-child))
                            result {:depth depth :chain chain}]
                        [(assoc d pack-id result) result])
                      (let [[d' child-result] (calc-depth (first remaining-deps) new-visited d)]
                        (recur (rest remaining-deps)
                               d'
                               (conj child-results child-result)))))))))]
    ;; Calculate depths for all packs
    (loop [remaining-packs (keys graph)
           depths {}]
      (if (empty? remaining-packs)
        depths
        (let [[depths' _] (calc-depth (first remaining-packs) #{} depths)]
          (recur (rest remaining-packs) depths'))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-dependency-graph
  "Build a dependency graph from a collection of packs.

   Arguments:
   - packs - Vector of pack manifests

   Returns:
   - {:graph {pack-id {:pack manifest :deps [dep...]}}
      :by-id {pack-id pack}
      :versions {pack-id version-string}}"
  [packs]
  (let [by-id (into {} (map (fn [p] [(:pack/id p) p]) packs))
        versions (into {} (map (fn [p] [(:pack/id p) (:pack/version p)]) packs))]
    {:graph (reduce (fn [g pack]
                      (let [pack-id (:pack/id pack)
                            deps (get-pack-dependencies pack)]
                        (assoc g pack-id
                               {:pack pack
                                :deps deps})))
                    {}
                    packs)
     :by-id by-id
     :versions versions}))

(defn ^{:stratum 1} detect-depth-violations
  "Detect dependency chains that exceed the depth limit.
   Returns vector of violation maps:
   [{:type :depth-limit
     :pack-id string
     :depth int
     :chain [pack-id...]
     :message string}]"
  [graph max-depth]
  (let [depths (calculate-pack-depths graph)]
    (->> depths
         (keep (fn [[pack-id {:keys [depth chain]}]]
                 (when (> depth max-depth)
                   {:type :depth-limit
                    :pack-id pack-id
                    :depth depth
                    :chain chain
                    :message (str "Pack '" pack-id "' has dependency chain of depth "
                                 depth " (exceeds limit of " max-depth "): "
                                 (str/join " → " chain))})))
         vec)))
