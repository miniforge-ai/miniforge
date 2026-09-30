;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.content-identity-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} content-identity-is-order-independent-and-type-sensitive-test
  (let [first-map (array-map :a [1 2] :b #{:x :y})
        second-map (array-map :b #{:y :x} :a [1 2])]
    (is (string? (artifact/content-digest first-map)))
    (is (= (artifact/content-digest first-map) (artifact/content-digest second-map)))
    (is (not= (artifact/content-digest {:steps '(1 2)})
              (artifact/content-digest {:steps [1 2]}))))
  (doseq [[left right] [[1 "1"] [:a "a"] [nil false]
                       [#inst "2026-09-29" (java.time.Instant/parse "2026-09-29T00:00:00Z")]]]
    (is (string? (artifact/content-digest left)))
    (is (string? (artifact/content-digest right)))
    (is (not= (artifact/content-digest left) (artifact/content-digest right)))))

(deftest ^{:stratum 0} deferred-and-unbounded-values-are-rejected-without-realization-test
  (let [realized (atom 0)
        deferred (lazy-seq (swap! realized inc) (repeat 1))]
    (is (anomaly/anomaly? (artifact/content-digest {:steps deferred})))
    (is (zero? @realized)))
  (is (anomaly/anomaly? (artifact/content-digest (Object.))))
  (is (anomaly/anomaly? (artifact/content-digest (nth (iterate vector nil) 130)))))

(comment
  (artifact/content-digest {:steps [1 2 3]}))
