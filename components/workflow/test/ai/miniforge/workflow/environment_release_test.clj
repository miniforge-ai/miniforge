;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-release-test
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.workflow.boundary.environment :as boundary]
            [ai.miniforge.workflow.messages :as messages]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private release! boundary/release!)

(defn- ^{:stratum 0} fail! [failure & _] (throw failure))

(defn- ^{:stratum 0} record! [calls & args] (swap! calls conj args))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} release-is-nil-safe-and-forwards-owned-identity
  (let [calls (atom [])]
    (with-redefs [dag/release-environment! (partial record! calls)]
      (release! nil :environment)
      (release! :executor nil)
      (release! :executor :environment)
      (is (= [[:executor :environment]] @calls)))))

(deftest ^{:stratum 1} ordinary-release-failure-remains-a-localized-warning
  (let [error (Exception. "fixture release failure")
        expected (messages/t :env/release-failed {:error (ex-message error)})]
    (with-redefs [dag/release-environment! (partial fail! error)]
      (is (= (str expected "\n") (with-out-str (release! :executor :environment)))))))

(deftest ^{:stratum 1} release-preserves-critical-cause-and-interruption
  (doseq [cause [(Error. "fixture fatal release")
                 (InterruptedException. "fixture interrupted release")]]
    (try+
      (with-redefs [dag/release-environment! (partial fail! (Exception. "fixture wrapper" cause))]
        (let [caught (try+ (release! :executor :environment)
                       (catch Object _ (:throwable &throw-context)))
              flagged? (.isInterrupted (Thread/currentThread))]
          (is (identical? cause caught))
          (is (= (instance? InterruptedException cause) flagged?))))
      (finally (Thread/interrupted)))))

(comment ::environment-release-contract)
