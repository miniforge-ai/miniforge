;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop
  "Construct an exact conditional deadline reduction; never claim it stopped the worker."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-json-patch :as patch]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-proposal :as proposal]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} stop-deadline-seconds
  "Kubernetes accepts positive shortened deadlines; elapsed time is not termination proof." 1)

(defn- ^{:stratum 0} usable? [input plan owned]
  (let [deadline (get-in owned [:load/pod "spec" "activeDeadlineSeconds"])]
    (and (not= (:effect/id input) (get-in plan [:recovery/load :creation-effect/id]))
         (pos-int? deadline))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request [owned]
  (let [pod (:load/pod owned)
        metadata (get pod "metadata")
        collection (get-in owned [:load/creation-record :effect/proposal :kubernetes/request :path])
        path (str collection "/" (get metadata "name"))
        tests (patch/metadata-tests metadata)
        deadline (patch/replace-value "/spec/activeDeadlineSeconds" stop-deadline-seconds)
        body (conj tests deadline)]
    {:method :patch
     :path path
     :media-type patch/media-type
     :body body}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [ctx input plan owned]
  (if-not (usable? input plan owned)
    (anomaly/anomaly :conflict (msg/ts :load/stop-request-refused) {})
    (let [payload (request owned)
          intent {:opsv/operation :stop-load
                  :kubernetes/request payload}
          duration (:recovery/duration-ms plan)]
      (proposal/proposal ctx input plan owned intent duration))))

(comment ::current-effect-admission-exact-recovery-grant-and-pinned-commit-remain-mandatory)
