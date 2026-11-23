;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.runner-lifetime
  "Preserve execution outcomes while completing owned runner cleanup."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.resource-cleanup.interface :as resources]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture! [result exception operation]
  (try (vreset! result (operation))
    (catch Throwable throwable
      (vreset! exception throwable)
      (throw throwable))))

(defn- ^{:stratum 0} warn-refusal! [result]
  (when (anomaly/any-anomaly? result)
    (println (messages/t :warn/heartbeat-stop {:error (:anomaly/message result)}))))

(defn- ^{:stratum 0} post-cleanup! [opts workflow acquired session]
  (cleanup/post-workflow-cleanup! opts @(:session/output session) workflow
                                 @(:session/exception session) acquired))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stop-heartbeats! [session]
  (when-let [stop! (get-in session [:session/callbacks :stop-phase-heartbeats!])]
    (warn-refusal! (stop!))))

(defn ^{:stratum 1} protect! [session operation cleanup]
  (let [result (volatile! nil)]
    (resources/finish! (partial capture! result (:session/exception session) operation) cleanup)
    @result))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} release! [opts workflow acquired session]
  (resources/finish! (partial stop-heartbeats! session)
                     (partial post-cleanup! opts workflow acquired session)))

(comment ::runner-lifetime-boundary)
