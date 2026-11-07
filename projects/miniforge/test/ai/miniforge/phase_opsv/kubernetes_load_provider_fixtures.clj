;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as load]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} created-uid "Provider-assigned identity, never inferred from the planned Pod name." "created-load-uid")

(def ^{:stratum 0} revision "Provider revision returned by the successful creation response." "created-load-revision")

(defn- ^{:stratum 0} capture! [captured record]
  (reset! captured record)
  (dispatch/report :failed))

(defn- ^{:stratum 0} record-request! [calls pending options]
  (swap! calls conj options)
  pending)

(defn- ^{:stratum 0} run-current! [directory proposal operation]
  (let [issued (grant/issue-for-effect directory (load/issuance-request proposal) clock/now)
        _ (grant/register! directory issued)
        options (assoc (commit/proposal issued proposal) :proposal proposal)
        proposed (effect/propose! directory options clock/now)]
    (effect/commit-current! directory (:effect/id proposed) (:grant/id issued)
                            (partial grant/current directory) (constantly clock/now) operation)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} native-response [input]
  (-> (get-in input [:proposal :kubernetes/request :body])
      (assoc-in ["metadata" "uid"] created-uid)
      (assoc-in ["metadata" "resourceVersion"] revision)))

(defn- ^{:stratum 1} with-transport [pending operation]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial record-request! calls pending) http/response-fields identity]
      [(operation) @calls])))

(defn ^{:stratum 1} with-input [f]
  (load/with-input
    (fn [{:keys [ctx input directory] :as fixture}]
      (let [proposal (phase/prepare-governed-kubernetes-load-proposal! ctx input)
            captured (atom nil)
            _ (run-current! (str directory "/capture") proposal (partial capture! captured))]
        (f (assoc fixture :proposal proposal :record @captured :runtime (replica/settings)
                  :authority-directory (str directory "/commit")))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} response [input]
  (wire/completed (wire/response (json/generate-string (native-response input)) {:status http/created-status})))

(defn ^{:stratum 2} apply! [input record pending dispatch]
  (with-transport pending #(phase/create-kubernetes-load-pod! (:runtime input) record dispatch)))

(defn ^{:stratum 2} commit! [input pending dispatch]
  (with-transport pending
    #(run-current! (:authority-directory input) (:proposal input)
                    (fn [record] (phase/create-kubernetes-load-pod! (:runtime input) record dispatch)))))

(comment ::real-retained-original-root-issuance-and-six-arity-current-commit-only-http-is-simulated)
