;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop
  "Construct an exact conditional deadline reduction; never claim it stopped the worker."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.governed-experiment-proposal :as proposal]
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
        collection (get-in owned [:load/creation-record :effect/proposal :kubernetes/request :path])]
    {:method :patch :path (str collection "/" (get metadata "name"))
     :media-type "application/json-patch+json"
     :body [{"op" "test" "path" "/metadata/uid" "value" (get metadata "uid")}
            {"op" "test" "path" "/metadata/resourceVersion" "value" (get metadata "resourceVersion")}
            {"op" "replace" "path" "/spec/activeDeadlineSeconds" "value" stop-deadline-seconds}]}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [ctx input plan owned]
  (if-not (usable? input plan owned)
    (anomaly/anomaly :conflict (msg/ts :load/stop-request-refused) {})
    (let [payload (request owned)
          resource (assoc (get-in plan [:recovery/load :resource]) :uid (get-in owned [:load/pod "metadata" "uid"]))
          native (-> (:recovery/input input)
                     :load
                     (assoc-in [:limits :effect/id] (:effect/id input))
                     (assoc-in [:limits :opsv/duration-ms] (:recovery/duration-ms plan)))
          prepared {:effect/class :effect/load-test :opsv/operation :stop-load
                    :runtime/target (get-in plan [:recovery/binding :runtime/target])
                    :opsv/resources [resource] :opsv/payload-hash (hash/content-hash payload)
                    :kubernetes/request payload}]
      (proposal/proposal ctx native prepared))))

(comment ::current-effect-admission-exact-recovery-grant-and-pinned-commit-remain-mandatory)
