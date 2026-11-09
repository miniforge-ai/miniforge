;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures :as authority]
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

(defn- ^{:stratum 0} record! [{:keys [ctx request runtime] :as fixture} proposal issued]
  (let [options (assoc (commit/proposal issued proposal) :proposal proposal)
        record (effect/propose! (:effects-directory runtime) options clock/now)]
    (is (= :proposed (:effect/state record)))
    (when (= :proposed (:effect/state record))
      (assoc fixture :recovery/runtime {:context ctx :input request}
             :recovery/proposal proposal :recovery/record record :recovery/issued-id (:grant/id issued)))))

(defn- ^{:stratum 0} replace! [fixture proposal issued]
  (let [directory (get-in fixture [:runtime :effects-directory])
        old (:recovery/record fixture)
        changed (assoc old :effect/grant-id (:grant/id issued) :effect/proposal proposal)
        stored (transactions/transition! directory old changed)]
    (is (= changed stored))
    (when (= changed stored)
      (assoc fixture :recovery/proposal proposal :recovery/record stored :recovery/issued-id (:grant/id issued)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} timeout-dispatch! [operation]
  (with-redefs [io/remaining-ms (constantly pending-timeout-ms)] (dispatch/dispatch! operation)))

(defn- ^{:stratum 1} proposed! [prepare fixture]
  (let [[proposal reads] (prepare fixture (:pod fixture))
        issued (authority/issue! fixture proposal)]
    (is (= 1 (count reads)))
    (when issued (record! fixture proposal issued))))

(defn ^{:stratum 1} rebind! [fixture proposal]
  (when-let [issued (authority/issue! fixture proposal)]
    (replace! fixture proposal issued)))

(defn ^{:stratum 1} execute-response [provider {:keys [runtime pod] :as fixture} pending dispatch]
  (let [directory (:effects-directory runtime)
        calls (atom [])
        operation #(provider (:recovery/runtime fixture) % dispatch)]
    (with-redefs [http/start! (partial respond! calls pod pending) http/response-fields identity]
      [(effect/commit-current! directory (:effect/id (:recovery/record fixture)) (:recovery/issued-id fixture)
                               (partial grant/current (:recovery/grants-directory fixture))
                               (constantly clock/now) operation)
       @calls])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-proposed [prepare f]
  (creation/with-created #(some->> % authority/with-stores (proposed! prepare) f)))

(comment ::real-issued-authority-independent-pins-and-durable-records-for-each-recovery-operation)
