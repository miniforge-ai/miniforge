;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.correlator
  "Symptom correlation across signals.

   Groups related signals into clusters and generates correlation hypotheses.

   Layer 0: Correlation logic (pure functions)")

;------------------------------------------------------------------------------ Layer 0
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
        confidence (min 1.0 (* 0.3 (count cluster-signals)))]
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
       "SLO breach correlated with recurring failure pattern — likely systemic issue"

       (contains? types :recurring-failure)
       (str "Recurring "
            (get-in (first cluster-signals) [:signal/evidence :failure-class] "unknown")
            " failures suggest targeted fix needed")

       (contains? types :high-iteration-count)
       "High iteration counts suggest prompt/heuristic refinement needed"

       :else
       "Correlated signals detected — investigate for common root cause")}))

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
