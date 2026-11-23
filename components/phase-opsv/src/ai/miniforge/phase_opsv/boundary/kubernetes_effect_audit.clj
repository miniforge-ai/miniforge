;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit
  "API surface class 1: durable native-record audit; never admission or execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.kubernetes-effect-audit-publication :as publication]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit :as model]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} refused [category id]
  (anomaly/anomaly category (msg/ts :effect/native-audit-unavailable) {:effect/id id}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-bound! [ctx directory id decision]
  (anomaly/let-ok [record (effect/read-record directory id)
                  assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                                     (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (if (and (context/stream ctx) (model/correlated? ctx id decision record assembly))
      record
      (refused :conflict id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} record! [ctx directory id decision]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [_bounded (reporting/admit [directory id decision])]
      (if (valid-arguments? [ctx directory id decision])
        (anomaly/let-ok [record (read-bound! ctx directory id decision)]
          (publication/publish! ctx record decision))
        (refused :invalid-input id)))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exceptions/nonfatal? _ (refused :unavailable id))))

(comment
  (record! {} "/tmp/example" (random-uuid) {}))
