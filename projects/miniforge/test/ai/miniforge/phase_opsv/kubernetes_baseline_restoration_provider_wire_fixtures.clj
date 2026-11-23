;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-wire-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} mutation! [calls pending options]
  (swap! calls conj options)
  pending)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} respond! [fixture calls pending options]
  (if (= :get (:method options))
    (wire/reply! (:restoration/routes fixture) calls options)
    (mutation! calls pending options)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-response [{:keys [runtime] :as fixture} pending dispatch]
  (let [directory (:effects-directory runtime)
        calls (atom [])
        operation #(phase/restore-kubernetes-baseline-replicas! (:recovery/runtime fixture) % dispatch)]
    (with-redefs [http/start! (partial respond! fixture calls pending) http/response-fields identity]
      [(effect/commit-current! directory (:effect/id (:recovery/record fixture)) (:recovery/issued-id fixture)
                               (partial grant/current (:recovery/grants-directory fixture))
                               (constantly clock/now) operation)
       @calls])))

(comment
  (fn [fixture pending dispatch] (execute-response fixture pending dispatch)))
