;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-http :as replica-http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as request]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} dispatch! dispatch/dispatch!)

(def ^{:stratum 0} twice! dispatch/twice!)

(defn ^{:stratum 0} refuse! [_operation] (dispatch/report :failed))

(defn ^{:stratum 0} interrupting-dispatch! [operation]
  (.interrupt (Thread/currentThread))
  (operation))

(defn ^{:stratum 0} settings []
  {:kubernetes/base-url "https://cluster.invalid"
   :kubernetes/cluster-id "staging-cluster"
   :kubernetes/namespace "staging"
   :kubernetes/context "staging-context"
   :kubernetes/timeout-ms 5000
   :kubernetes/max-response-bytes 1048576})

(defn- ^{:stratum 0} record-request! [calls pending options]
  (swap! calls conj options)
  pending)

(defn- ^{:stratum 0} capture! [captured record]
  (reset! captured record)
  (dispatch/report :failed))

(defn- ^{:stratum 0} apply-record! [runtime dispatch record]
  (phase/apply-kubernetes-replica-request! runtime record dispatch))

(defn- ^{:stratum 0} prepare-with-budget! [prepared original settings record]
  (let [options (original settings record)]
    (reset! prepared true)
    options))

(defn- ^{:stratum 0} remaining-after-preparation [prepared budget original until]
  (if @prepared budget (original until)))

(defn- ^{:stratum 0} run-current! [directory proposal operation]
  (let [issued (scope/issued :effect/deploy proposal)
        options {:effect-id (:effect/id proposal) :effect-class :effect/deploy
                 :grant-id (:grant/id issued) :envelope-id (random-uuid) :proposal proposal}
        proposed (effect/propose! (str directory) options scope/now)]
    (effect/commit-current! (str directory) (:effect/id proposed) (constantly issued)
                            (constantly scope/now) operation)))

(defn ^{:stratum 0} native-response [input]
  (let [deployment (get-in input [:current :kubernetes/inventory :kubernetes/deployment])
        prepared (request/prepare input)
        desired (get (last (get-in prepared [:kubernetes/request :body])) "value")]
    (-> deployment
        (assoc-in ["metadata" "resourceVersion"] request/fresh-revision)
        (assoc-in ["spec" "replicas"] desired))))

(defn ^{:stratum 0} proposal [input]
  (let [prepared (request/prepare input)
        request (:request input)
        operation (:opsv/operation prepared)
        experiment (-> (scope/experiment-binding operation)
                       (merge (select-keys request [:verification/id :candidate/hash :experiment-pack/hash]))
                       (merge (select-keys prepared [:runtime/target :opsv/operation :opsv/payload-hash :opsv/resources]))
                       (assoc :comparison/hash (get-in request [:comparison/pin :comparison/hash])))]
    (assoc (scope/scope operation)
           :workflow-run/id (get-in input [:declaration :workflow/id])
           :opsv/experiment experiment :kubernetes/request (:kubernetes/request prepared))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-preparation-budget [budget operation]
  (let [prepared (atom false)
        original-options replica-http/request-options
        original-remaining io/remaining-ms]
    (with-redefs [replica-http/request-options (partial prepare-with-budget! prepared original-options)
                  io/remaining-ms (partial remaining-after-preparation prepared budget original-remaining)]
      (operation))))

(defn ^{:stratum 1} record [proposal]
  (let [captured (atom nil)]
    (fs/with-temp-dir [directory {:prefix "native-replica-provider-record-"}]
      (run-current! directory proposal (partial capture! captured))
      @captured)))

(defn- ^{:stratum 1} with-transport [pending operation]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial record-request! calls pending)
                  http/response-fields identity]
      [(operation) @calls])))

(defn- ^{:stratum 1} commit-and-read! [directory input dispatch]
  (let [operation (partial apply-record! (:runtime input) dispatch)
        result (run-current! directory (:proposal input) operation)
        stored (effect/read-record (str directory) (:effect/id result))]
    {:result result :stored stored}))

(defn ^{:stratum 1} response [input]
  (wire/completed (wire/response (json/generate-string (native-response input)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply! [runtime record pending dispatch]
  (with-transport pending (partial apply-record! runtime dispatch record)))

(defn ^{:stratum 2} commit! [input pending dispatch]
  (fs/with-temp-dir [directory {:prefix "native-replica-provider-commit-"}]
    (with-transport pending (partial commit-and-read! directory input dispatch))))

(defn ^{:stratum 2} input []
  (let [native (request/input)
        proposal (proposal native)]
    (assoc native :proposal proposal :record (record proposal) :runtime (settings))))

(comment ::real-native-request-and-durable-current-claim-producers-only-http-is-simulated
         ::comparison-reference-is-a-typed-fixture-not-a-retention-proof)
