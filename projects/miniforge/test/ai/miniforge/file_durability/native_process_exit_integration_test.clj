;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.native-process-exit-integration-test
  (:require [ai.miniforge.file-durability.boundary.native-process-exit :as exit]
            [ai.miniforge.file-durability.probe-cleanup-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} native-wait
  (ns-resolve 'ai.miniforge.file-durability.boundary.native-exit-wait 'wait-native!))

(defn- ^{:stratum 0} interrupted-wait! [original deadlines completed until]
  (swap! deadlines conj until)
  (if (= 1 (count @deadlines))
    (throw (InterruptedException.))
    (original completed until)))

(defn- ^{:stratum 0} verify-interruption! [child handles]
  (try+
    (.interrupt (Thread/currentThread))
    (let [stopped? (exit/stop! (:proc child) fixtures/budget-ms)
          interrupted? (Thread/interrupted)]
      (is (true? stopped?))
      (is interrupted?)
      (is (every? fixtures/dead? handles)))
    (finally (Thread/interrupted))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-new-interruption! [child handles]
  (let [deadlines (atom [])
        replacement (partial interrupted-wait! @native-wait deadlines)]
    (try+
      (let [stopped? (with-redefs-fn {native-wait replacement}
                       #(exit/stop! (:proc child) fixtures/budget-ms))
            interrupted? (Thread/interrupted)]
        (is (true? stopped?))
        (is interrupted?)
        (is (<= 2 (count @deadlines)))
        (is (apply = @deadlines))
        (is (every? fixtures/dead? handles)))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1 :integration true} test-preexisting-interruption-is-deferred-until-native-death
  (fixtures/with-native! :tree verify-interruption!))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-new-interruption-does-not-renew-the-shutdown-budget
  (fixtures/with-native! :single verify-new-interruption!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.native-process-exit-integration-test))
