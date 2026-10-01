;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.web-dashboard.control-identity
  "Server-owned dashboard listener identity and its lifetime."
  (:require [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} requester
  "Attribute controls to the dashboard surface, never a body-supplied user.
   Authenticated session attribution is a separate integration obligation."
  {:principal "dashboard" :role :operator :capability :control})

(defn- ^{:stratum 0} receive-event [_event]
  ;; The dashboard consumes events through its polling/WebSocket paths.
  nil)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} release!
  "Release only this dashboard's listener when its server stops."
  [state]
  (let [{:keys [event-stream control/requester]} @state]
    (when-let [listener-id (:listener-id requester)]
      (events/deregister-listener! event-stream listener-id))))

(defn ^{:stratum 1} register!
  "Register once per dashboard state, not once per request or JVM."
  [stream]
  (when stream
    (let [identity {:principal (:principal requester) :roles [(:role requester)]}
          listener-id (events/register-listener!
                       stream {:listener/type :dashboard
                               :listener/capability (:capability requester)
                               :listener/identity identity
                               :listener/callback receive-event})]
      (assoc requester :listener-id listener-id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attach!
  "Acquire the listener only when startup can hand ownership to its caller."
  [state]
  (let [requester (register! (:event-stream @state))]
    (swap! state assoc :control/requester requester)
    state))
