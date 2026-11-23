;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-fence-test
  (:require [ai.miniforge.event-stream.heartbeat-observation :as observation]
            [ai.miniforge.event-stream.heartbeat-progress :as progress]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} closed-state []
  (let [identity {:workflow/id (random-uuid) :workflow/phase :opsv/verify
                  :heartbeat/activation-id (random-uuid)}
        model (progress/start identity (java.util.Date.) 0 :unknown)]
    (atom {:progress model :closed? true :dropped 0})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} closed-activation-refuses-effects-and-progress-mutations
  (let [state (closed-state)
        before @state]
    (is (nil? (observation/tick! nil state 1)))
    (is (nil? (observation/observe! state :fixture/work 1)))
    (is (= before @state))))

(comment ::closed-activation-fence)
