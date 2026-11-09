;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-ownership
  "API surface class 1: uncached durable receipt and bounded native Pod identity reads."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.bounded-io :as io]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-settings :as settings]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} refused [category]
  (anomaly/anomaly category (msg/ts :load/ownership-unconfirmed) {}))

(defn- ^{:stratum 0} check-interruption! []
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-owned! [settings record]
  (let [proposal (:effect/proposal record)
        path (str (get-in proposal [:kubernetes/request :path]) "/"
                  (get-in record [:effect/observed :kubernetes/resource :name]))
        until (io/deadline (:kubernetes/timeout-ms settings))]
    (anomaly/let-ok [pod (http/read-resource! settings until path nil)]
      (check-interruption!)
      (if (ownership/observed? record pod)
        {:load/creation-record record :load/pod pod}
        (refused :conflict)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read! [runtime plan]
  (try+
    (check-interruption!)
    (anomaly/let-ok [_bounded (reporting/admit [runtime plan])]
      (if-not (valid-arguments? [runtime plan])
        (refused :invalid-input)
        (anomaly/let-ok [configured (settings/load-settings (:provider runtime) native/Settings
                                                          [:kubernetes/node-pool-label :kubernetes/max-resources])
                        record (effect/read-record (:effects-directory runtime)
                                                   (get-in plan [:recovery/load :creation-effect/id]))]
          (check-interruption!)
          (if (ownership/recorded? configured plan record)
            (read-owned! configured record)
            (refused :conflict)))))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exceptions/nonfatal? _ (refused :unavailable))))

(comment ::read-only-no-create-retry-gate-release-stop-delete-or-inferred-termination)
