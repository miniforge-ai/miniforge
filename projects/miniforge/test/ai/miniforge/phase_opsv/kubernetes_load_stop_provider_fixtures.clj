;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-authority-fixtures :as authority]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as stop]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} confirmed-revision
  "A concrete acknowledged revision distinct from the precondition read." "confirmed-stop-revision")

(def ^{:stratum 0 :private true} pending-timeout-ms
  "Expire the pending PATCH wait only after ownership GET and canonical reconstruction finish." 1)

(defn- ^{:stratum 0} respond! [calls pod pending options]
  (swap! calls conj options)
  (if (= :get (:method options))
    (wire/completed (wire/response (json/generate-string pod)))
    pending))

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(defn- ^{:stratum 0} replace! [fixture proposal issued]
  (let [directory (get-in fixture [:runtime :effects-directory])
        old (:stop/record fixture)
        changed (assoc old :effect/grant-id (:grant/id issued) :effect/proposal proposal)
        stored (transactions/transition! directory old changed)]
    (is (= changed stored))
    (when (= changed stored)
      (assoc fixture :stop/proposal proposal :stop/record stored :stop/issued-id (:grant/id issued)))))

(defn- ^{:stratum 0} propose! [{:keys [ctx request runtime] :as fixture} proposal issued]
  (let [options (assoc (commit/proposal issued proposal) :proposal proposal)
        record (effect/propose! (:effects-directory runtime) options clock/now)]
    (is (= :proposed (:effect/state record)))
    (when (= :proposed (:effect/state record))
      (assoc fixture :stop/runtime {:context ctx :input request}
             :stop/proposal proposal :stop/record record :stop/issued-id (:grant/id issued)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} timeout-dispatch! [operation]
  (with-redefs [io/remaining-ms (constantly pending-timeout-ms)]
    (dispatch/dispatch! operation)))

(defn ^{:stratum 1} response [pod]
  (-> pod (assoc-in ["spec" "activeDeadlineSeconds"] 1)
      (assoc-in ["metadata" "resourceVersion"] confirmed-revision)))

(defn ^{:stratum 1} rebind! [fixture proposal]
  (when-let [issued (authority/issue! fixture proposal)]
    (replace! fixture proposal issued)))

(defn- ^{:stratum 1} proposed! [{:keys [pod] :as fixture}]
  (let [[proposal reads] (stop/prepare fixture pod)]
    (is (= 1 (count reads)))
    (when-let [issued (authority/issue! fixture proposal)]
      (propose! fixture proposal issued))))

(defn ^{:stratum 1} execute-response [{:keys [runtime pod] :as fixture} pending dispatch]
  (let [directory (:effects-directory runtime)
        calls (atom [])
        operation #(phase/stop-kubernetes-load-pod! (:stop/runtime fixture) % dispatch)]
    (with-redefs [http/start! (partial respond! calls pod pending) http/response-fields identity]
      [(effect/commit-current! directory (:effect/id (:stop/record fixture)) (:stop/issued-id fixture)
                               (partial grant/current (:stop/grants-directory fixture))
                               (constantly clock/now) operation)
       @calls])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-proposed [f]
  (stop/with-created #(some-> % authority/with-stores proposed! f)))

(defn ^{:stratum 2} execute
  ([fixture] (execute fixture (response (:pod fixture))))
  ([fixture pod]
   (execute-response fixture (wire/completed (wire/response (json/generate-string pod))) dispatch/dispatch!)))

(comment ::actual-root-recovery-issuance-and-independent-issued-pin-not-an-N4-effect-admission-or-runner-proof)
