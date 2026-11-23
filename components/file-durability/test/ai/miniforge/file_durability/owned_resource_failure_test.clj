;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.owned-resource-failure-test
  (:require [ai.miniforge.file-durability.boundary.owned-resource :as owned]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} throw-failure! [failure _resource] (throw failure))

(defn- ^{:stratum 0} capture [operation]
  (try+ (operation) nil (catch Throwable failure failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-priority! [primary secondary expected suppressed]
  (let [operation (partial owned/call-with-release! :owned
                            (partial throw-failure! secondary)
                            (partial throw-failure! primary))
        actual (capture operation)
        interrupted? (Thread/interrupted)]
    (is (identical? expected actual))
    (is (= [suppressed] (vec (.getSuppressed ^Throwable actual))))
    interrupted?))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-failed-release-cannot-mask-an-earlier-fatal-error
  (let [primary (Error.)
        secondary (java.io.IOException.)]
    (verify-priority! primary secondary primary secondary)))

(deftest ^{:stratum 2} test-fatal-release-retains-the-independent-operation-failure
  (let [primary (java.io.IOException.)
        secondary (Error.)]
    (verify-priority! primary secondary secondary primary)))

(deftest ^{:stratum 2} test-failed-release-preserves-native-interruption-and-restores-the-flag
  (try+
    (let [primary (InterruptedException.)
          secondary (java.io.IOException.)]
      (let [interrupted? (verify-priority! primary secondary primary secondary)]
        (is interrupted?)))
    (finally (Thread/interrupted))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.owned-resource-failure-test))
