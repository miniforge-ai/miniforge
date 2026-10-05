;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-options-test
  (:require [ai.miniforge.event-stream.boundary.current-options :as options]
            [ai.miniforge.event-stream.current-query-spec :as spec]
            [ai.miniforge.event-stream.publication-test-support :as support]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} unreadable-options-fail-closed
  (is (options/valid? spec/Options {:limit 1}))
  (is (false? (options/valid? spec/Options (sorted-map 1 :bad)))))

(deftest ^{:stratum 0} critical-validation-causes-are-preserved
  (doseq [cause [(AssertionError.) (InterruptedException.)]]
    (with-redefs [m/validate (fn [_ _] (throw (ex-info "fixture validation failure" {} cause)))]
      (let [[actual interrupted?] (support/observe-critical! (partial options/valid? spec/Options {}))]
        (is (identical? cause actual))
        (is (= (instance? InterruptedException cause) interrupted?))))))

(comment
  ::current-options)
