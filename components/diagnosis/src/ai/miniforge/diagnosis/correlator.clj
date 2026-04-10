;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.correlator
  "Symptom correlation across signals.

   Groups related signals into clusters and generates correlation hypotheses.

   Layer 0: Constants
   Layer 1: Correlation logic (pure functions)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.diagnosis.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private config
  (-> (io/resource "config/diagnosis/defaults.edn") slurp edn/read-string))

(def ^:private confidence-per-signal
  "Confidence contribution per signal in a cluster."
  (:confidence-per-signal config))

;------------------------------------------------------------------------------ Layer 1
;; Correlation

(defn- signals-related?
  "Determine if two signals might be causally related."
  [s1 s2]
  (or
   ;; Same affected heuristic
   (and (:signal/affected-heuristic s1)
        (= (:signal/affected-heuristic s1) (:signal/affected-heuristic s2)))
   ;; Same failure class
   (and (get-in s1 [:signal/evidence :failure-class])
        (= (get-in s1 [:signal/evidence :failure-class])
           (get-in s2 [:signal/evidence :failure-class])))
   ;; SLO breach + recurring failure of same type
   (and (= :slo-breach (:signal/type s1))
        (= :recurring-failure (:signal/type s2)))))

(defn- build-cluster
  "Build a correlation map from a cluster of signals."
  [cluster-signals]
  (let [types (set (map :signal/type cluster-signals))
        confidence (min 1.0 (* confidence-per-signal (count cluster-signals)))]
    {:correlation/signals cluster-signals
     :correlation/type (cond
                         (contains? types :slo-breach) :slo-driven
                         (contains? types :recurring-failure) :failure-pattern
                         (contains? types :quality-regression) :quality-decline
                         :else :mixed)
     :correlation/confidence confidence
     :correlation/hypothesis
     (cond
       (and (contains? types :slo-breach)
            (contains? types :recurring-failure))
       (msg/t :hypothesis/slo-with-failure)

       (contains? types :recurring-failure)
       (msg/t :hypothesis/recurring-failure
              {:class (name (get-in (first cluster-signals) [:signal/evidence :failure-class] :unknown))})

       (contains? types :high-iteration-count)
       (msg/t :hypothesis/high-iteration)

       :else
       (msg/t :hypothesis/default))}))

(defn correlate-symptoms
  "Group related signals into correlated clusters.

   Uses greedy clustering via reduce — each signal is either absorbed
   into an existing cluster or starts a new one.

   Arguments:
     signals - vector of signal maps from signal/extract-signals

   Returns: vector of correlation maps
     {:correlation/signals [...]
      :correlation/type keyword
      :correlation/confidence float
      :correlation/hypothesis string}"
  [signals]
  (if (empty? signals)
    []
    (->> (reduce
          (fn [{:keys [clusters used]} [i signal]]
            (if (contains? used i)
              {:clusters clusters :used used}
              (let [related-indices (->> (map-indexed vector signals)
                                        (keep (fn [[j s]]
                                                (when (and (not= i j)
                                                           (not (contains? used j))
                                                           (signals-related? signal s))
                                                  j))))
                    all-indices (cons i related-indices)
                    cluster-signals (mapv #(nth signals %) all-indices)]
                {:clusters (conj clusters (build-cluster cluster-signals))
                 :used (into used all-indices)})))
          {:clusters [] :used #{}}
          (map-indexed vector signals))
         :clusters)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (correlate-symptoms
   [{:signal/type :slo-breach :signal/severity :high
     :signal/evidence {:sli-name :SLI-1 :tier :standard}}
    {:signal/type :recurring-failure :signal/severity :medium
     :signal/evidence {:failure-class :failure.class/timeout :count 5}}])

  :leave-this-here)
