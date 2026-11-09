;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start
  "Prepare gate release for an unchanged, owned, never-started worker; never execution permission."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-load-container :as worker]
            [ai.miniforge.phase-opsv.kubernetes-json-patch :as patch]
            [ai.miniforge.phase-opsv.kubernetes-load-outcome :as creation]
            [ai.miniforge.phase-opsv.kubernetes-load-start-profile :as profile]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-proposal :as proposal]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} gates-path "/spec/schedulingGates")

(defn- ^{:stratum 0} usable? [input plan owned]
  (and (not= (:effect/id input) (get-in plan [:recovery/load :creation-effect/id]))
       (= :not-started (:load/quiescence owned))
       (nil? (get-in owned [:load/pod "metadata" "deletionTimestamp"]))
       (creation/matches-request? (:load/creation-record owned) (:load/pod owned))
       (profile/matches? (:load/creation-record owned) (:load/pod owned))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request [owned]
  (let [metadata (get-in owned [:load/pod "metadata"])
        collection (get-in owned [:load/creation-record :effect/proposal :kubernetes/request :path])
        path (str collection "/" (get metadata "name"))
        tests (patch/metadata-tests metadata)
        expected-gates (patch/test-value gates-path worker/scheduling-gates)
        release-gates (patch/remove-value gates-path)
        body (conj tests expected-gates release-gates)]
    {:method :patch
     :path path
     :media-type patch/media-type
     :body body}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [ctx input plan owned]
  (if-not (usable? input plan owned)
    (anomaly/anomaly :conflict (msg/ts :load/start-request-refused) {})
    (let [payload (request owned)
          intent {:opsv/operation :start-load
                  :kubernetes/request payload}
          duration (get-in input [:recovery/input :load :limits :opsv/duration-ms])]
      (proposal/proposal ctx input plan owned intent duration))))

(comment ::original-forward-load-bounds-not-recovery-duration-or-creation-authority)
