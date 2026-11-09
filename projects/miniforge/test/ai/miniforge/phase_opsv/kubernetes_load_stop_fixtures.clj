;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-fixtures :as replica]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as retention]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} respond! [calls pending options]
  (swap! calls conj options)
  pending)

(defn- ^{:stratum 0} create! [{:keys [ctx input directory] :as fixture}]
  (let [native (assoc fixture :input (:load input) :runtime (replica/settings)
                      :authority-directory (str (fs/path directory "stop-load-creation"))
                      :proposal (phase/prepare-governed-kubernetes-load-proposal! ctx (:load input)))
        [record calls] (provider/commit! native (provider/response native) dispatch/dispatch!)
        stored (effect/read-record (:authority-directory native) (:effect/id record))]
    (is (= :succeeded (:effect/state record)))
    (is (= record stored))
    (is (= 1 (count calls)))
    {:runtime {:effects-directory (:authority-directory native) :provider (:runtime native)}
     :record record :pod (provider/native-response native)}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-created [f]
  (retention/with-retained
    (fn [{:keys [input plan-id] :as fixture}]
      (let [{:keys [runtime] :as created} (create! fixture)
            request {:recovery/input input :recovery/artifact-ref plan-id
                     :ownership/runtime runtime :effect/id (random-uuid)}]
        (f (merge fixture created {:request request}))))))

(defn ^{:stratum 1} prepare [{:keys [ctx request]} pod]
  (let [calls (atom [])
        pending (wire/completed (wire/response (json/generate-string pod)))]
    (with-redefs [http/start! (partial respond! calls pending) http/response-fields identity]
      [(phase/prepare-kubernetes-load-stop-proposal! ctx request) @calls])))

(comment ::real-retained-admission-canonical-creation-root-grant-six-argument-commit-and-durable-storage)
