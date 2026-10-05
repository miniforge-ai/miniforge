;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-delivery-test
  (:require [ai.miniforge.event-stream.commit-test-support :as support]
            [ai.miniforge.event-stream.current-delivery :as delivery]
            [ai.miniforge.event-stream.publication-test-support :as publication]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-value! [throwable _] (throw throwable))

(defn- ^{:stratum 0} observe! [seen target event] (swap! seen conj [target event]))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} ordinary-listener-and-filter-errors-do-not-block-other-listeners
  (let [seen (atom [])
        event (support/draft)
        fail! (partial throw-value! (ex-info "fixture delivery failure" {}))
        stream (atom {:sinks [fail! (partial observe! seen :sink)]
                      :subscribers {:bad identity :good (partial observe! seen :subscriber)}
                      :filters {:bad fail!}})]
    (delivery/deliver! stream event)
    (is (= [[:sink event] [:subscriber event]] @seen))))

(deftest ^{:stratum 1} critical-listener-causes-escape
  (doseq [cause [(AssertionError.) (InterruptedException.)]]
    (let [wrapped (ex-info "fixture wrapped listener failure" {} cause)
          stream (atom {:sinks [(partial throw-value! wrapped)]})
          result (publication/observe-critical! (partial delivery/deliver! stream (support/draft)))]
      (is (identical? cause (first result)))
      (is (= (instance? InterruptedException cause) (second result))))))

(comment
  ::listener-isolation)
