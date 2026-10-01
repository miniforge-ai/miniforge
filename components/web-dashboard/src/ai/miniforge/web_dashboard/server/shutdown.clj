;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.server.shutdown
  "Drain HTTP traffic before releasing the control identity."
  (:require [org.httpkit.server :as http]
            [ai.miniforge.web-dashboard.server.boundary.cleanup :as cleanup]
            [ai.miniforge.web-dashboard.control-identity :as control-identity]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stop-http! [server]
  (locking server
    (when-let [completion (http/server-stop! server)]
      @completion)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stop! [{:keys [server watcher-cleanup state]}]
  (cleanup/complete! nil [#(some-> server stop-http!)
                          #(when watcher-cleanup (watcher-cleanup))
                          #(when state (control-identity/release! state))]))
