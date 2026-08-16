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
(ns ai.miniforge.policy-pack.rules.pack-dependency-validation
  "Pack dependency validation rule for knowledge-safety policy pack.

   Implements N4 §2.4.2 requirements:
   - Circular dependency detection (A → B → A)
   - Missing dependency detection
   - Version conflict resolution
   - Trust level constraint enforcement
   - Dependency depth limit (default: 5 levels)
   - Complete dependency graph validation before loading

   Final slice (3/3) of a rule 210 split train (SL003: this namespace
   originally measured 6 real layers, max 3 -- same convention as the
   mdc-compiler split, miniforge#1729-#1743, and the workflow-runner
   split, miniforge#1662). Slice 1 (#1770) moved version
   parsing/comparison/constraint-satisfaction to
   `ai.miniforge.policy-pack.rules.pack-dependency-validation.versions`.
   Slice 2 (#1777) moved the trust-check chain to
   `ai.miniforge.policy-pack.rules.pack-dependency-validation.trust`.
   This slice moves dependency-graph construction and
   circular/missing/depth-limit detection to
   `ai.miniforge.policy-pack.rules.pack-dependency-validation.graph`:
   get-pack-dependencies, detect-circular-dependencies,
   detect-missing-dependencies, calculate-pack-depths,
   build-dependency-graph, detect-depth-violations. This file now
   measures 3 real layers -- within the rule 210 budget, closing out
   the train.

   Layer 0: detect-version-conflicts (over versions/satisfies-constraint?,
     an external call)
   Layer 1: validate-pack-dependencies (orchestrates
     dep-graph/build-dependency-graph, dep-graph/detect-circular-dependencies,
     dep-graph/detect-missing-dependencies, dep-graph/detect-depth-violations,
     trust/detect-trust-violations, and the local detect-version-conflicts
     -- all but the last are external calls)
   Layer 2: validate-single-pack (public entry point, over
     validate-pack-dependencies)"
  (:require
   [ai.miniforge.policy-pack.rules.pack-dependency-validation.graph :as dep-graph]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation.trust :as trust]
   [ai.miniforge.policy-pack.rules.pack-dependency-validation.versions :as versions]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} detect-version-conflicts
  "Detect version conflicts in the dependency tree.
   Returns vector of violation maps:
   [{:type :version-conflict
     :dependency string
     :constraints [{:pack-id string :constraint string}...]
     :message string}]"
  [graph pack-versions]
  (let [;; Collect all constraints for each dependency
        dep-constraints (reduce-kv
                         (fn [acc pack-id node]
                           (reduce (fn [a dep]
                                     (update a (:pack-id dep)
                                             (fnil conj [])
                                             {:pack-id pack-id
                                              :constraint (:version-constraint dep)}))
                                   acc
                                   (:deps node)))
                         {}
                         graph)]

    ;; Check each dependency for conflicts
    (->> dep-constraints
         (keep (fn [[dep-id constraints]]
                 (let [;; Get actual version of dependency
                       actual-version (get pack-versions dep-id)
                       ;; Check if all constraints can be satisfied
                       conflicting (when actual-version
                                     (remove (fn [{:keys [constraint]}]
                                              (or (nil? constraint)
                                                  (versions/satisfies-constraint? actual-version constraint)))
                                            constraints))]
                   (when (seq conflicting)
                     {:type :version-conflict
                      :dependency dep-id
                      :actual-version actual-version
                      :conflicts conflicting
                      :message (str "Version conflict for dependency '" dep-id "': "
                                   "version " actual-version " does not satisfy constraints from "
                                   (str/join ", " (map #(str "'" (:pack-id %) "' ("
                                                                (:constraint %) ")")
                                                              conflicting)))}))))
         vec)))

;------------------------------------------------------------------------------ Layer 1

;; Public API
(defn ^{:stratum 1} validate-pack-dependencies
  "Validate pack dependencies according to N4 §2.4.2.

   Checks:
   1. Circular dependencies (A → B → A)
   2. Missing dependencies
   3. Version conflicts
   4. Trust level constraints (when PR14 merged)
   5. Dependency depth limit

   Arguments:
   - packs - Vector of pack manifests to validate
   - opts  - Options map:
             :max-depth - Maximum dependency depth (default: 5)
             :check-trust? - Enable trust validation (default: false, requires PR14)

   Returns:
   - {:valid? boolean
      :violations [{:type keyword :message string ...}]
      :warnings [{:type keyword :message string ...}]}

   Example:
     (validate-pack-dependencies [pack-a pack-b pack-c]
                                 {:max-depth 5})"
  [packs & [{:keys [max-depth check-trust?]
             :or {max-depth 5
                  check-trust? false}}]]
  (let [{:keys [graph by-id versions]} (dep-graph/build-dependency-graph packs)

        ;; Run all validation checks
        circular (dep-graph/detect-circular-dependencies graph)
        missing (dep-graph/detect-missing-dependencies graph by-id)
        version-conflicts (detect-version-conflicts graph versions)
        trust-violations (when check-trust?
                          (trust/detect-trust-violations graph by-id))
        depth-violations (dep-graph/detect-depth-violations graph max-depth)

        ;; Separate hard failures from warnings
        failures (concat circular missing version-conflicts trust-violations)
        warnings depth-violations

        valid? (and (empty? failures)
                   (empty? warnings))]

    {:valid? valid?
     :violations failures
     :warnings warnings}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-single-pack
  "Validate a single pack's dependencies against a registry of available packs.

   Arguments:
   - pack - Pack manifest to validate
   - registry - Vector of available pack manifests
   - opts - Options map (see validate-pack-dependencies)

   Returns:
   - Same as validate-pack-dependencies but filtered to violations involving the target pack"
  [pack registry & [opts]]
  (let [all-packs (conj registry pack)
        result (validate-pack-dependencies all-packs opts)
        pack-id (:pack/id pack)

        ;; Filter violations to those involving this pack
        relevant? (fn [v]
                   (or (= (:pack-id v) pack-id)
                       (some #(= (:pack-id %) pack-id) (:constraints v))
                       (some #(= % pack-id) (:cycle v))))

        filtered-violations (filter relevant? (:violations result))
        filtered-warnings (filter relevant? (:warnings result))]

    {:valid? (and (empty? filtered-violations)
                 (empty? filtered-warnings))
     :violations filtered-violations
     :warnings filtered-warnings}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test circular dependency detection
  (def pack-a {:pack/id "pack-a"
               :pack/version "2026.01.25"
               :pack/extends [{:pack-id "pack-b"}]})

  (def pack-b {:pack/id "pack-b"
               :pack/version "2026.01.25"
               :pack/extends [{:pack-id "pack-c"}]})

  (def pack-c {:pack/id "pack-c"
               :pack/version "2026.01.25"
               :pack/extends [{:pack-id "pack-a"}]})

  (validate-pack-dependencies [pack-a pack-b pack-c])
  ;; => {:valid? false
  ;;     :violations [{:type :circular-dependency
  ;;                   :cycle ["pack-a" "pack-b" "pack-c" "pack-a"]}]
  ;;     :warnings []}

  ;; Test missing dependency detection
  (def pack-with-missing
    {:pack/id "pack-x"
     :pack/version "2026.01.25"
     :pack/extends [{:pack-id "nonexistent-pack"}]})

  (validate-pack-dependencies [pack-with-missing])
  ;; => {:valid? false
  ;;     :violations [{:type :missing-dependency
  ;;                   :pack-id "pack-x"
  ;;                   :missing-dep "nonexistent-pack"}]
  ;;     :warnings []}

  ;; Test version conflict detection
  (def pack-dep {:pack/id "shared-dep"
                 :pack/version "2026.01.25"})

  (def pack-1 {:pack/id "pack-1"
               :pack/version "2026.01.25"
               :pack/extends [{:pack-id "shared-dep"
                              :version-constraint ">=2026.01.01"}]})

  (def pack-2 {:pack/id "pack-2"
               :pack/version "2026.01.25"
               :pack/extends [{:pack-id "shared-dep"
                              :version-constraint "<2026.01.20"}]})

  (validate-pack-dependencies [pack-1 pack-2 pack-dep])
  ;; => {:valid? false
  ;;     :violations [{:type :version-conflict
  ;;                   :dependency "shared-dep"}]
  ;;     :warnings []}

  ;; Test depth limit
  (def deep-1 {:pack/id "deep-1" :pack/version "1.0.0" :pack/extends []})
  (def deep-2 {:pack/id "deep-2" :pack/version "1.0.0"
               :pack/extends [{:pack-id "deep-1"}]})
  (def deep-3 {:pack/id "deep-3" :pack/version "1.0.0"
               :pack/extends [{:pack-id "deep-2"}]})
  (def deep-4 {:pack/id "deep-4" :pack/version "1.0.0"
               :pack/extends [{:pack-id "deep-3"}]})
  (def deep-5 {:pack/id "deep-5" :pack/version "1.0.0"
               :pack/extends [{:pack-id "deep-4"}]})
  (def deep-6 {:pack/id "deep-6" :pack/version "1.0.0"
               :pack/extends [{:pack-id "deep-5"}]})

  (validate-pack-dependencies [deep-1 deep-2 deep-3 deep-4 deep-5 deep-6]
                               {:max-depth 5})
  ;; => {:valid? false
  ;;     :violations []
  ;;     :warnings [{:type :depth-limit
  ;;                 :pack-id "deep-6"
  ;;                 :depth 6}]}

  :leave-this-here)
