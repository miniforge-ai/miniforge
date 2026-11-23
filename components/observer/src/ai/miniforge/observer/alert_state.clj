;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-state
  "Own tool-call observations and their bounded outcome window."
  (:require [ai.miniforge.observer.alert-outcomes :as outcomes]
            [ai.miniforge.observer.boundary.alert-clock :as clock]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create []
  (atom {:outstanding-calls {} :tool-outcomes []}))

(defn ^{:stratum 0} observe! [state event]
  (case (:event/type event)
    :agent/tool-call-started
    (when-let [call-id (:tool/call-id event)]
      (swap! state assoc-in [:outstanding-calls call-id] {:started-at (clock/event-ms event)}))
    :tool/call-completed (swap! state outcomes/complete-call event)
    nil))

(comment ::bounded-tool-observations)
