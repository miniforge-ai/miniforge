;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-runtime
  "Trusted runtime contracts and idempotent PR execution admission."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.pr-authority :as authority]
            [ai.miniforge.phase-opsv.pr-model :as model]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlank [:and :string [:fn #(not (str/blank? %))]])

(defn- ^{:stratum 0} execute-new! [runtime ctx candidate verified prepared]
  (let [existing (effect/read-record (:effects-directory runtime) (:effect/id candidate))]
    (cond
      (anomaly/anomaly? existing) existing
      existing (anomaly/anomaly :conflict (msg/ts :pr/existing-effect) {:effect/transaction existing})
      :else (let [now ((:clock runtime))]
              (if (instance? Instant now)
                (authority/execute! runtime ctx candidate verified prepared now)
                (anomaly/anomaly :invalid-input (msg/ts :pr/invalid-runtime) {}))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PrRuntime
  [:map {:closed true}
   [:effects-directory NonBlank]
   [:authority-directory NonBlank]
   [:clock fn?]
   [:fence actuation/MutationFence]
   [:control {:optional true} [:fn control/run?]]
   [:provider provider/ProviderRuntime]
   [:target [:map [:opsv/policy-hash [:re #"\A[0-9a-f]{64}\z"]]]]])

(defn- ^{:stratum 1} execute-candidate! [runtime ctx verified candidate]
  (flow/continue (actuation/prepare-governed-pr candidate (:opsv/decision-envelope verified))
                 (partial execute-new! runtime ctx candidate verified)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute! [runtime ctx verified]
  (if-not (and (m/validate PrRuntime runtime)
               (or (not (contains? runtime :control))
                   (control/bound-to? (:control runtime) (context/workflow-id ctx)
                                   (:authority-directory runtime) (:fence runtime))))
    (anomaly/anomaly :invalid-input (msg/ts :pr/invalid-runtime) {})
    (actuation/at-mutation-boundary!
     (:fence runtime)
     #(flow/continue
       (model/candidate ctx verified (:target runtime))
       (partial execute-candidate! runtime ctx verified)))))

(comment
  (m/validate PrRuntime {}))
