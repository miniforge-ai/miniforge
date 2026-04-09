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

   Layer 0: Version parsing and comparison
   Layer 1: Dependency graph construction
   Layer 2: Validation rules
   Layer 3: Public API"
  (:require
   [ai.miniforge.algorithms.interface :as alg]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Version parsing and comparison

(defn parse-version
  "Parse a DateVer version string (YYYY.MM.DD or YYYY.MM.DD.N).
   Returns {:year int :month int :day int :patch int} or nil if invalid."
  [version-str]
  (when version-str
    (when-let [match (re-matches #"(\d{4})\.(\d{2})\.(\d{2})(?:\.(\d+))?" version-str)]
      (let [[_ year month day patch] match]
        {:year (Integer/parseInt year)
         :month (Integer/parseInt month)
         :day (Integer/parseInt day)
         :patch (if patch (Integer/parseInt patch) 0)}))))

(defn compare-versions
  "Compare two version strings.
   Returns negative if v1 < v2, positive if v1 > v2, 0 if equal."
  [v1 v2]
  (let [p1 (parse-version v1)
        p2 (parse-version v2)]
    (if (and p1 p2)
      (let [year-cmp (compare (:year p1) (:year p2))]
        (if (not= 0 year-cmp)
          year-cmp
          (let [month-cmp (compare (:month p1) (:month p2))]
            (if (not= 0 month-cmp)
              month-cmp
              (let [day-cmp (compare (:day p1) (:day p2))]
                (if (not= 0 day-cmp)
                  day-cmp
                  (compare (:patch p1) (:patch p2))))))))
      (compare v1 v2))))

(defn parse-version-constraint
  "Parse a version constraint string.
   Supports:
   - Exact: '2026.01.25' or '=2026.01.25'
   - Greater: '>2026.01.25', '>=2026.01.25'
   - Less: '<2026.01.25', '<=2026.01.25'
   - Range: '>=2026.01.01,<2026.02.01'
   - Wildcard: '2026.01.*'

   Returns {:type :exact/:range/:wildcard :constraints [...]}"
  [constraint-str]
  (cond
    ;; Range constraint (comma-separated)
    (str/includes? constraint-str ",")
    (let [parts (str/split constraint-str #",")
          trimmed (map str/trim parts)]
      {:type :range
       :constraints (mapv parse-version-constraint trimmed)})

    ;; Wildcard (e.g., "2026.01.*")
    (str/includes? constraint-str "*")
    {:type :wildcard
     :prefix (str/replace constraint-str #"\.\*$" "")}

    ;; Greater than or equal
    (str/starts-with? constraint-str ">=")
    {:type :gte
     :version (subs constraint-str 2)}

    ;; Greater than
    (str/starts-with? constraint-str ">")
    {:type :gt
     :version (subs constraint-str 1)}

    ;; Less than or equal
    (str/starts-with? constraint-str "<=")
    {:type :lte
     :version (subs constraint-str 2)}

    ;; Less than
    (str/starts-with? constraint-str "<")
    {:type :lt
     :version (subs constraint-str 1)}

    ;; Exact (with or without '=' prefix)
    :else
    {:type :exact
     :version (if (str/starts-with? constraint-str "=")
               (subs constraint-str 1)
               constraint-str)}))

(defn satisfies-constraint?
  "Check if a version satisfies a constraint.
   Returns true if version satisfies the constraint."
  [version constraint]
  (when (and version constraint)
    (let [parsed (parse-version-constraint constraint)]
      (case (:type parsed)
        :exact (= version (:version parsed))
        :gt (pos? (compare-versions version (:version parsed)))
        :gte (>= (compare-versions version (:version parsed)) 0)
        :lt (neg? (compare-versions version (:version parsed)))
        :lte (<= (compare-versions version (:version parsed)) 0)
        :wildcard (str/starts-with? version (:prefix parsed))
        :range (every? #(satisfies-constraint? version (str (:version %)))
                       (:constraints parsed))
        false))))

;------------------------------------------------------------------------------ Layer 1
;; Dependency graph construction

(defn get-pack-dependencies
  "Extract dependencies from a pack manifest.
   Returns vector of {:pack-id string :version-constraint string}."
  [pack]
  (get pack :pack/extends []))

(defn build-dependency-graph
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

;------------------------------------------------------------------------------ Layer 2
;; Validation rules

(defn detect-circular-dependencies
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

(defn detect-missing-dependencies
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

(defn detect-version-conflicts
  "Detect version conflicts in the dependency tree.
   Returns vector of violation maps:
   [{:type :version-conflict
     :dependency string
     :constraints [{:pack-id string :constraint string}...]
     :message string}]"
  [graph versions]
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
                       actual-version (get versions dep-id)
                       ;; Check if all constraints can be satisfied
                       conflicting (when actual-version
                                     (remove (fn [{:keys [constraint]}]
                                              (or (nil? constraint)
                                                  (satisfies-constraint? actual-version constraint)))
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

(defn- tainted-dependency?
  "True when a non-tainted pack depends on a tainted pack."
  [pack dep-pack]
  (and dep-pack
       (= :tainted (get dep-pack :pack/trust-level))
       (not= :tainted (get pack :pack/trust-level))))

(defn- untrusted-instruction-escalation?
  "True when an untrusted pack with instruction authority depends on a trusted pack."
  [pack dep-pack]
  (and dep-pack
       (= :untrusted (get pack :pack/trust-level :untrusted))
       (= :authority/instruction (get pack :pack/authority :authority/data))
       (= :trusted (get dep-pack :pack/trust-level))))

(defn- check-dependency-trust
  "Check a single pack→dependency pair for trust violations.
   Returns a violation map or nil."
  [pack-id pack dep-id dep-pack]
  (cond
    (tainted-dependency? pack dep-pack)
    {:type       :trust-violation
     :pack-id    pack-id
     :dependency dep-id
     :message    (str "Tainted dependency " dep-id
                      " cannot be used by non-tainted pack " pack-id)}

    (untrusted-instruction-escalation? pack dep-pack)
    {:type       :trust-violation
     :pack-id    pack-id
     :dependency dep-id
     :message    (str "Untrusted pack " pack-id
                      " with instruction authority cannot depend on "
                      "trusted pack " dep-id)}))

(defn detect-trust-violations
  "Detect trust level constraint violations per N4 §2.4.2.

   Checks:
   1. Untrusted pack with instruction authority depending on trusted pack
   2. Tainted pack as dependency of any non-tainted pack

   Returns vector of violation maps."
  [_graph by-id]
  (->> (vals by-id)
       (mapcat (fn [pack]
                 (let [pack-id (get pack :pack/id)]
                   (->> (get pack :pack/extends [])
                        (keep (fn [dep]
                                (let [dep-id (get dep :pack-id)]
                                  (check-dependency-trust
                                   pack-id pack dep-id (get by-id dep-id)))))))))
       vec))

(defn calculate-pack-depths
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

(defn detect-depth-violations
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

;------------------------------------------------------------------------------ Layer 3
;; Public API

(defn validate-pack-dependencies
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
  (let [{:keys [graph by-id versions]} (build-dependency-graph packs)

        ;; Run all validation checks
        circular (detect-circular-dependencies graph)
        missing (detect-missing-dependencies graph by-id)
        version-conflicts (detect-version-conflicts graph versions)
        trust-violations (when check-trust?
                          (detect-trust-violations graph by-id))
        depth-violations (detect-depth-violations graph max-depth)

        ;; Separate hard failures from warnings
        failures (concat circular missing version-conflicts trust-violations)
        warnings depth-violations

        valid? (and (empty? failures)
                   (empty? warnings))]

    {:valid? valid?
     :violations failures
     :warnings warnings}))

(defn validate-single-pack
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
  ;; Test version parsing
  (parse-version "2026.01.25")
  ;; => {:year 2026 :month 1 :day 25 :patch 0}

  (parse-version "2026.01.25.2")
  ;; => {:year 2026 :month 1 :day 25 :patch 2}

  ;; Test version comparison
  (compare-versions "2026.01.25" "2026.01.26")
  ;; => -1

  ;; Test version constraints
  (satisfies-constraint? "2026.01.25" ">=2026.01.20")
  ;; => true

  (satisfies-constraint? "2026.01.25" "2026.01.*")
  ;; => true

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
