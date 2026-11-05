;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.resource-cleanup.cause-selection-test
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} io-failure? [failure] (instance? java.io.IOException failure))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} selection-preserves-cause-identity-and-terminates-on-cycles
  (let [cause (java.io.IOException. "fixture IO")
        wrapper (Exception. "fixture wrapper" cause)
        other (Exception. "fixture other")]
    (is (identical? cause (cleanup/find-cause io-failure? wrapper)))
    (is (nil? (cleanup/find-cause io-failure? nil)))
    (.initCause cause other)
    (.initCause other wrapper)
    (is (nil? (cleanup/find-cause (constantly false) wrapper)))))

(comment ::shared-cause-selection)
