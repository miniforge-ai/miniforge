;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance
  "Evaluate OPSV gates from trusted runtime policy, never caller verdicts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.messages :as msg]
            [clojure.string :as str]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RuntimePolicy
  [:map {:closed true}
   [:policy/context [:map-of :keyword any?]]
   [:policy/revision [:and :string [:fn #(not (str/blank? %))]]]
   [:policy/event-watermark [:int {:min 0}]]])

(defn- ^{:stratum 0} registry-key [id]
  (keyword "opsv" (str (name id) "-gate")))

(defn- ^{:stratum 0} domain-result [id result]
  {:gate/id id :gate/passed? (true? (:passed? result))})

(defn- ^{:stratum 0} decision [checks policy artifact-nil?]
  (let [evaluated (cond-> checks
                    (nil? policy)
                    (update :results conj
                            {:gate :opsv/runtime-policy :passed? false
                             :errors [{:message (msg/ts :governance/missing-policy)}]}))]
    (gate/gates->envelope evaluated artifact-nil?
                          {:pins/pack-revision (:policy/revision policy)
                           :pins/rule-ids (mapv :gate (:results checks))
                           :pins/event-watermark (:policy/event-watermark policy)})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} evaluate-checks [pack verified policy]
  (gate/check-gates (mapv registry-key opsv/opsv-gate-ids) pack
                    (assoc (:policy/context policy) :opsv/evidence verified)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate
  "Only :execution/opts may supply policy. Missing policy denies; invalid policy
   returns an anomaly. Evidence always comes from the verified pipeline output."
  [ctx verified]
  (let [policy (get-in ctx [:execution/opts :opsv/governance])
        pack (get-in ctx [:execution/input :opsv/experiment-pack])]
    (if (and (some? policy) (not (m/validate RuntimePolicy policy)))
      (anomaly/anomaly :invalid-input (msg/ts :governance/invalid-policy) {})
      (let [checks (evaluate-checks pack verified policy)]
        {:opsv/gate-checks checks
         :opsv/gate-results (mapv domain-result opsv/opsv-gate-ids (:results checks))
         :opsv/decision-envelope (decision checks policy (nil? pack))}))))

(comment
  (evaluate {} {}))
