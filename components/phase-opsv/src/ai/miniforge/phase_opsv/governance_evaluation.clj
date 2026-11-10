;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance-evaluation
  (:require [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} registry-key [id] (keyword "opsv" (str (name id) "-gate")))

(defn- ^{:stratum 0} domain-result [id result]
  (let [passed? (gate/passed? result)]
    {:gate/id id
     :gate/passed? passed?}))

(defn ^{:stratum 0} refusal [id message-key]
  (let [message (msg/ts message-key)]
    {:gate id
     :passed? false
     :errors [{:message message}]}))

(defn- ^{:stratum 0} gate-context [ctx evidence policy]
  (assoc (:policy/context policy)
         :event-stream (context/stream ctx)
         :workflow/id (context/workflow-id ctx)
         :opsv/evidence evidence))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} decision [checks policy artifact-nil? refusals]
  (let [refusals (cond-> refusals
                   (nil? policy) (conj (refusal :opsv/runtime-policy :governance/missing-policy)))
        evaluated (update checks :results into refusals)
        revision (:policy/revision policy)
        ids (mapv :gate (:results checks))
        watermark (:policy/event-watermark policy)
        pins {:pins/pack-revision revision
              :pins/rule-ids ids
              :pins/event-watermark watermark}]
    (gate/gates->envelope evaluated artifact-nil? pins)))

(defn- ^{:stratum 1} evaluate-checks [ids ctx artifact evidence policy]
  (gate/check-gates (mapv registry-key ids) artifact (gate-context ctx evidence policy)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate
  ([ids ctx artifact evidence policy] (evaluate ids ctx artifact evidence policy []))
  ([ids ctx artifact evidence policy refusals]
   (let [checks (evaluate-checks ids ctx artifact evidence policy)
         results (mapv domain-result ids (:results checks))
         envelope (decision checks policy (nil? artifact) refusals)]
     {:opsv/gate-checks checks
      :opsv/gate-results results
      :opsv/decision-envelope envelope})))

(comment ::shared-real-gate-engine-and-envelope-producer)
