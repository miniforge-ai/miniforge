;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures :as authority]
            [ai.miniforge.phase-opsv.kubernetes-effect-record-fixtures :as records]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as creation]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} pending-timeout-ms
  "Expire only the pending mutation wait after ownership reconstruction has finished." 1)

(defn- ^{:stratum 0} respond! [calls pod pending options]
  (swap! calls conj options)
  (if (= :get (:method options))
    (wire/completed (wire/response (json/generate-string pod)))
    pending))

(def ^{:stratum 0} rebind! records/rebind!)

(defn- ^{:stratum 0} proposed! [prepare fixture]
  (let [[proposal reads] (prepare fixture (:pod fixture))
        issued (authority/issue! fixture proposal)]
    (is (= 1 (count reads)))
    (when issued (records/propose! fixture proposal issued))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} timeout-dispatch! [operation]
  (with-redefs [io/remaining-ms (constantly pending-timeout-ms)] (dispatch/dispatch! operation)))

(defn ^{:stratum 1} execute-response [provider {:keys [runtime pod] :as fixture} pending dispatch]
  (let [directory (:effects-directory runtime)
        calls (atom [])
        operation #(provider (:recovery/runtime fixture) % dispatch)]
    (with-redefs [http/start! (partial respond! calls pod pending) http/response-fields identity]
      [(effect/commit-current! directory (:effect/id (:recovery/record fixture)) (:recovery/issued-id fixture)
                               (partial grant/current (:recovery/grants-directory fixture))
                               (constantly clock/now) operation)
       @calls])))

(defn ^{:stratum 1} with-proposed
  ([prepare f] (with-proposed prepare identity f))
  ([prepare transform f]
   (creation/with-created #(some->> % transform authority/with-stores (proposed! prepare) f))))

(comment
  (fn [fixture] (rebind! fixture (:recovery/proposal fixture))))
