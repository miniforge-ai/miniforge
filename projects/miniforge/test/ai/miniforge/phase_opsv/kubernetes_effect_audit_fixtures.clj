;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-audit-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-effect-record-fixtures :as records]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures :as authority]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as retained]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} assembly [{:keys [ctx]}]
  (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                             (get-in ctx [:execution/input :opsv/evidence-bundle-id])))

(defn ^{:stratum 0} dispositions [{:keys [ctx]}]
  (filterv #(= :opsv.actuation/disposition (:event/type %)) (events/get-events (:event-stream ctx))))

(defn ^{:stratum 0} audit [{:keys [ctx runtime decision recovery/record]}]
  (phase/record-kubernetes-experiment-disposition! ctx (:effects-directory runtime) (:effect/id record) decision))

(defn- ^{:stratum 0} context [{:keys [ctx input]}]
  (assoc-in ctx [:execution/opts :opsv/experiment-execution]
            {:experiment/enabled? true :environment/class :staging :experiment/environment "staging"
             :experiment/declaration (get-in input [:load :declaration])}))

(defn- ^{:stratum 0} respond! [calls pending options]
  (swap! calls conj options)
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} commit! [{:keys [runtime recovery/record recovery/issued-id] :as fixture} pending]
  (let [calls (atom [])
        operation #(phase/create-kubernetes-load-pod! (replica/settings) % dispatch/dispatch!)]
    (with-redefs [http/start! (partial respond! calls pending) http/response-fields identity]
      [(effect/commit-current! (:effects-directory runtime) (:effect/id record) issued-id
                               (partial grant/current (:recovery/grants-directory fixture))
                               (constantly clock/now) operation) @calls])))

(defn- ^{:stratum 1} proposed! [{:keys [directory input native] :as fixture}]
  (let [ctx (context fixture)
        evaluated (phase/evaluate-experiment-preflight ctx (get-in input [:load :request]) (:baseline native))
        decision (:opsv/experiment-decision-envelope evaluated)
        proposal (phase/prepare-governed-kubernetes-load-proposal! ctx (:load input))
        configured (authority/with-stores
                     (assoc fixture :ctx ctx :decision decision :proposal proposal :request (:load input)
                            :runtime {:effects-directory (str (fs/path directory "native-audit-effects"))}
                            :workflow-run/status :running :effect/envelope-id (:envelope/id decision)))
        issued (when (= :allow (:envelope/decision decision)) (authority/issue! configured proposal))]
    (is (= :allow (:envelope/decision decision)))
    (is (true? (get-in evaluated [:opsv/experiment-gate-checks :passed?])))
    (is (not (anomaly/anomaly? proposal)))
    (when issued (records/propose! configured proposal issued))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-proposed [f]
  (retained/with-retained #(some->> % proposed! f)))

(defn ^{:stratum 2} success! [fixture]
  (commit! fixture (provider/response fixture)))

(comment
  (with-proposed audit))
