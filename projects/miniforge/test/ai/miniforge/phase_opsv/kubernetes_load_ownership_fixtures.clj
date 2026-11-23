;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as provider]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-request! [calls pending options]
  (swap! calls conj options)
  pending)

(defn ^{:stratum 0} replace-record! [{:keys [runtime record]} replacement]
  (let [result (transactions/transition! (:effects-directory runtime) record replacement)]
    (is (= replacement result))
    result))

(defn ^{:stratum 0} with-created [f]
  (provider/with-input
    (fn [{:keys [ctx input authority-directory runtime] :as fixture}]
      (let [prepared (phase/prepare-kubernetes-recovery-plan! ctx
                       {:load input :recovery/duration-ms (get-in input [:limits :opsv/duration-ms])})
            [record calls] (provider/commit! fixture (provider/response fixture) dispatch/dispatch!)
            stored (effect/read-record authority-directory (:effect/id record))
            pod (assoc-in (provider/native-response fixture) ["metadata" "resourceVersion"] "fresh-read-revision")]
        (is (map? (:recovery/plan prepared)))
        (is (= :succeeded (:effect/state record)))
        (is (= record stored))
        (is (= 1 (count calls)))
        (f {:runtime {:effects-directory authority-directory :provider runtime}
            :plan (:recovery/plan prepared) :record record :pod pod})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-response [{:keys [runtime plan]} pending]
  (let [calls (atom [])]
    (with-redefs [http/start! (partial record-request! calls pending) http/response-fields identity]
      [(phase/read-kubernetes-load-ownership! runtime plan) @calls])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe
  ([fixture] (observe fixture (:pod fixture)))
  ([fixture pod]
   (observe-response fixture (wire/completed (wire/response (json/generate-string pod))))))

(comment ::real-native-construction-root-issuance-pinned-commit-and-durable-store-only-http-is-simulated)
