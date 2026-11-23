;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.observer.alert-state-test
  (:require [ai.miniforge.observer.alert-state :as state]
            [ai.miniforge.observer.boundary.alert-clock :as clock]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} outcome [value]
  {:event/type :tool/call-completed :tool/call-id :fixture/call :tool/success? value})

(deftest ^{:stratum 0} timestamp-coercion-preserves-legacy-representations
  (doseq [timestamp [1000 (java.util.Date. 1000) "1970-01-01T00:00:01Z"]]
    (is (= 1000 (clock/event-ms {:event/timestamp timestamp})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} outcome-window-keeps-latest-hundred-observations
  (let [observations (state/create)]
    (dotimes [_ 100] (state/observe! observations (outcome false)))
    (state/observe! observations (outcome true))
    (is (= 100 (count (:tool-outcomes @observations))))
    (is (= 99 (count (remove true? (:tool-outcomes @observations)))))
    (is (true? (peek (:tool-outcomes @observations))))))

(deftest ^{:stratum 1} absent-outcomes-are-not-counted-but-present-nil-is-preserved
  (let [observations (state/create)]
    (state/observe! observations (dissoc (outcome true) :tool/success?))
    (is (empty? (:tool-outcomes @observations)))
    (state/observe! observations (outcome nil))
    (is (= [nil] (:tool-outcomes @observations)))))

(comment ::legacy-tool-observation-contract)
