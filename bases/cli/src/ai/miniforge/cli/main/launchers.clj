;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.launchers
  "Compose optional dashboards with governed event producers."
  (:require [ai.miniforge.cli.main.util :as util]
            [ai.miniforge.cli.messages :as messages]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.pr-train.interface :as pr-train]
            [ai.miniforge.repo-dag.interface :as repo-dag]
            [ai.miniforge.supervisory-state.interface :as supervisory]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-pr-train-manager
  ([] (create-pr-train-manager nil))
  ([event-stream]
   (try+
     (if event-stream
       (pr-train/create-manager {:event-stream event-stream})
       (pr-train/create-manager))
     (catch InterruptedException interrupted
       (.interrupt (Thread/currentThread))
       (throw interrupted))
     (catch Error fatal (throw fatal))
     (catch Object failure
       (println (messages/t :web/pr-train-warning
                            {:error (util/caught-message failure (:throwable &throw-context))}))
       nil))))

(defn ^{:stratum 0} create-repo-dag-manager []
  (try+
    (repo-dag/create-manager)
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Error fatal (throw fatal))
    (catch Object failure
      (println (messages/t :web/repo-dag-warning
                           {:error (util/caught-message failure (:throwable &throw-context))}))
      nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} start-dashboard! [start! {:keys [port]}]
  (let [event-stream (events/create-event-stream)
        _ (supervisory/ensure-attached! event-stream)
        pr-train-manager (create-pr-train-manager event-stream)
        repo-dag-manager (create-repo-dag-manager)]
    (start! {:port port
             :event-stream event-stream
             :pr-train-manager pr-train-manager
             :repo-dag-manager repo-dag-manager})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} optional-web-launcher []
  (when-let [start! (util/optional-composition-var
                     'ai.miniforge.web-dashboard.interface 'start!)]
    (partial start-dashboard! start!)))

(comment
  ::optional-web-launcher)
