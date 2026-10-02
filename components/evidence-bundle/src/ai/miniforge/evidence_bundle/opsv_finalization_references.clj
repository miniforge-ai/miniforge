;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-finalization-references
  "Canonical ordering and correlation checks for OPSV evidence references."
  (:require [clojure.set :as cset]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reference-set
  [value]
  (cond
    (nil? value) #{}
    (set? value) value
    (or (vector? value) (list? value)) (set value)
    :else #{}))

(defn- ^{:stratum 0} detailed-artifact-refs
  [evidence]
  (set (concat
        [(:opsv/experiment-pack-artifact-id evidence)]
        (map :artifact-id (:opsv/policy-proposals evidence))
        (get-in evidence [:opsv/actuation :postcondition-artifact-refs])
        (get-in evidence [:opsv/actuation :rollback :artifact-refs])
        (:opsv/metric-query-artifact-refs evidence)
        (:opsv/metric-snapshot-artifact-refs evidence)
        (:opsv/diff-artifact-refs evidence))))

(def ^{:stratum 0} ^:private reference-paths
  [[:opsv/event-refs]
   [:opsv/artifact-refs]
   [:opsv/grant-refs]
   [:opsv/actuation :pr-refs]
   [:opsv/actuation :apply-refs]
   [:opsv/actuation :postcondition-artifact-refs]
   [:opsv/actuation :rollback :artifact-refs]
   [:opsv/metric-query-artifact-refs]
   [:opsv/metric-snapshot-artifact-refs]
   [:opsv/diff-artifact-refs]])

(defn- ^{:stratum 0} governed-effect-sort-key
  [effect]
  [(str (:evidence/effect-id effect))
   (str (:evidence/grant-id effect))
   (str (:evidence/envelope-id effect))])

(defn- ^{:stratum 0} ordered [values] (vec (sort values)))

(defn- ^{:stratum 0} missing-artifacts [referenced available]
  (vec (sort (cset/difference referenced available))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} order-path [evidence path]
  (update-in evidence path ordered))

(defn- ^{:stratum 1} ordered-effects [effects]
  (vec (sort-by governed-effect-sort-key effects)))

(defn ^{:stratum 1} errors
  [record evidence available-artifact-ids]
  (let [event-refs (reference-set (:opsv/event-refs evidence))
        artifact-refs (reference-set (:opsv/artifact-refs evidence))
        grant-refs (reference-set (:opsv/grant-refs evidence))
        available-artifact-refs (reference-set available-artifact-ids)
        effects (reference-set (get-in evidence [:opsv/actuation
                                                 :governed-effects]))
        effect-grants (set (map :evidence/grant-id effects))
        missing (missing-artifacts artifact-refs available-artifact-refs)]
    (cond-> []
      (not= (:opsv/event-refs record) event-refs)
      (conj {:code :event-reference-mismatch})
      (not= (:opsv/artifact-refs record) artifact-refs)
      (conj {:code :artifact-reference-mismatch})
      (not= (:opsv/grant-refs record) grant-refs)
      (conj {:code :grant-reference-mismatch})
      (not= (:opsv/governed-effects record) effects)
      (conj {:code :governed-effect-mismatch})
      (not (cset/subset? (detailed-artifact-refs evidence) artifact-refs))
      (conj {:code :detailed-artifact-reference-missing})
      (not (cset/subset? artifact-refs available-artifact-refs))
      (conj {:code :referenced-artifact-not-found
             :missing missing})
      (not (cset/subset? effect-grants grant-refs))
      (conj {:code :uncorrelated-governed-effect}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} canonicalize [evidence]
  (-> (reduce order-path evidence reference-paths)
      (update-in [:opsv/actuation :governed-effects] ordered-effects)))

(comment
  (canonicalize {}))
