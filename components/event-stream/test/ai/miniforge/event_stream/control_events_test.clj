;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-events-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} execution-records-supplied-request-metadata
  (doseq [role [:operator :unknown]]
    (let [stream (events/create-event-stream {:sinks []})
          target {:target-type :workflow :target-id (random-uuid)}
          requester {:principal "operator" :role role :listener-id (random-uuid)}
          action (events/create-control-action :pause target requester
                                                {:justification "Investigate" :parameters {:reason :audit}})]
      (events/execute-control-action! stream action (constantly nil))
      (let [requested (first (events/get-events stream {:event-type :control-action/requested}))]
        (doseq [field [:action/id :action/type :action/requester :action/target
                       :action/justification :action/parameters]]
          (is (= (get action field) (get requested field))))))))
