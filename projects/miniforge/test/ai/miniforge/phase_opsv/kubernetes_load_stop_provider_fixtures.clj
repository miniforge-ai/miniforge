;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-provider-fixtures
  (:require [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-provider-fixtures :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as stop]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} confirmed-revision
  "A concrete acknowledged revision distinct from the precondition read." "confirmed-stop-revision")

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(def ^{:stratum 0} with-proposed
  "Real retained creation and independently granted stop proposal."
  (partial recovery/with-proposed stop/prepare))

(def ^{:stratum 0} rebind! "Replace a proposed payload only with newly issued authority." recovery/rebind!)

(def ^{:stratum 0} execute-response
  "Execute a separately pinned durable stop through the real native provider."
  (partial recovery/execute-response phase/stop-kubernetes-load-pod!))

(def ^{:stratum 0} timeout-dispatch! "Timeout only the mutation after current ownership." recovery/timeout-dispatch!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} response [pod]
  (-> pod (assoc-in ["spec" "activeDeadlineSeconds"] 1)
      (assoc-in ["metadata" "resourceVersion"] confirmed-revision)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute
  ([fixture] (execute fixture (response (:pod fixture))))
  ([fixture pod]
   (execute-response fixture (wire/completed (wire/response (json/generate-string pod))) dispatch/dispatch!)))

(comment ::actual-root-recovery-issuance-and-independent-issued-pin-not-an-N4-effect-admission-or-runner-proof)
