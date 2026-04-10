;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.correlator
  "Symptom correlation across signals.

   Groups related signals into clusters and generates correlation hypotheses.

   Layer 0: Constants and named predicates
   Layer 1: Cluster construction
   Layer 2: Greedy clustering pipeline"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.diagnosis.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and named predicates

(def ^:private config
  (-> (io/resource "config/diagnosis/defaults.edn") slurp edn/read-string))

(def ^:private confidence-per-signal
  "Confidence contribution per signal in a cluster."
  (:confidence-per-signal config))

(defn- signal-heuristic [s]  (:signal/affected-heuristic s))
(defn- signal-type      [s]  (:signal/type s))
(defn- signal-failure-class [s] (get-in s [:signal/evidence :failure-class]))

(defn- share-heuristic?
  "Both signals target the same affected heuristic."
  [s1 s2]
  (let [h1 (signal-heuristic s1)]
    (and (some? h1) (= h1 (signal-heuristic s2)))))

(defn- share-failure-class?
  "Both signals originate from the same failure class."
  [s1 s2]
  (let [c1 (signal-failure-class s1)]
    (and (some? c1) (= c1 (signal-failure-class s2)))))

(defn- slo-breach-with-failure?
  "An SLO breach paired with a recurring failure — likely systemic."
  [s1 s2]
  (and (= :slo-breach (signal-type s1))
       (= :recurring-failure (signal-type s2))))

(defn- signals-related?
  "Determine if two signals might be causally related."
  [s1 s2]
  (or (share-heuristic? s1 s2)
      (share-failure-class? s1 s2)
      (slo-breach-with-failure? s1 s2)))

;------------------------------------------------------------------------------ Layer 1
;; Cluster construction

(defn- has-signal-type?
  "Returns true if any signal in the cluster has the given type."
  [types signal-type-kw]
  (contains? types signal-type-kw))

(defn- infer-correlation-type
  "Classify a cluster by its dominant signal types."
  [types]
  (cond
    (has-signal-type? types :slo-breach)          :slo-driven
    (has-signal-type? types :recurring-failure)    :failure-pattern
    (has-signal-type? types :quality-regression)   :quality-decline
    :else                                          :mixed))

(defn- infer-hypothesis
  "Generate a localized hypothesis string from the cluster's signal types."
  [types cluster-signals]
  (cond
    (and (has-signal-type? types :slo-breach)
         (has-signal-type? types :recurring-failure))
    (msg/t :hypothesis/slo-with-failure)

    (has-signal-type? types :recurring-failure)
    (msg/t :hypothesis/recurring-failure
           {:class (name (or (signal-failure-class (first cluster-signals)) :unknown))})

    (has-signal-type? types :high-iteration-count)
    (msg/t :hypothesis/high-iteration)

    :else
    (msg/t :hypothesis/default)))

(defn- build-cluster
  "Build a correlation map from a cluster of signals."
  [cluster-signals]
  (let [types      (set (map signal-type cluster-signals))
        confidence (min 1.0 (* confidence-per-signal (count cluster-signals)))]
    {:correlation/signals    cluster-signals
     :correlation/type       (infer-correlation-type types)
     :correlation/confidence confidence
     :correlation/hypothesis (infer-hypothesis types cluster-signals)}))

;------------------------------------------------------------------------------ Layer 2
;; Greedy clustering pipeline

(defn- find-related-indices
  "Find indices of signals related to the signal at index i (excluding already-used)."
  [signals i used]
  (let [signal (nth signals i)]
    (->> signals
         (map-indexed vector)
         (keep (fn [[j s]]
                 (when (and (not= i j)
                            (not (contains? used j))
                            (signals-related? signal s))
                   j))))))

(defn- absorb-cluster
  "Absorb a signal and its related signals into a new cluster.
   Returns updated accumulator with the cluster added and indices marked used."
  [signals {:keys [clusters used]} i]
  (let [related   (find-related-indices signals i used)
        all-idx   (cons i related)
        members   (mapv #(nth signals %) all-idx)]
    {:clusters (conj clusters (build-cluster members))
     :used     (into used all-idx)}))

(defn correlate-symptoms
  "Group related signals into correlated clusters.

   Pipeline: index signals → reduce (skip used, absorb rest) → extract clusters.

   Arguments:
     signals - vector of signal maps from signal/extract-signals

   Returns: vector of correlation maps."
  [signals]
  (->> (map-indexed vector signals)
       (reduce (fn [acc [i _signal]]
                 (if (contains? (:used acc) i)
                   acc
                   (absorb-cluster signals acc i)))
               {:clusters [] :used #{}})
       :clusters))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (correlate-symptoms
   [{:signal/type :slo-breach :signal/severity :high
     :signal/evidence {:sli-name :SLI-1 :tier :standard}}
    {:signal/type :recurring-failure :signal/severity :medium
     :signal/evidence {:failure-class :failure.class/timeout :count 5}}])

  :leave-this-here)
