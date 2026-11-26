;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.qualified-root-boundary-test
  (:require [ai.miniforge.file-durability.core :as core]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record-dispatch! [calls & _]
  (swap! calls inc)
  :unexpected-dispatch)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-invalid-handles-never-reach-the-implementation
  (let [calls (atom 0)
        reject! (partial record-dispatch! calls)]
    (with-redefs [core/resolve-qualified-root reject!
                  core/release-qualified-root! reject!]
      (doseq [value [nil {} [] :root "root" (java.io.File. ".")]]
        (is (= :invalid-input (:anomaly/type (durability/resolve-qualified-root value))))
        (is (= :invalid-input (:anomaly/type (durability/release-qualified-root! value))))))
    (is (zero? @calls))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.qualified-root-boundary-test))
