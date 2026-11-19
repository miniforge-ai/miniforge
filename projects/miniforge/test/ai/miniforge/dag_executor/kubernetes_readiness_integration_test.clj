;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-readiness-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} budget-ms 500)

(def ^{:stratum 0 :private true} excessive-poll-ms 10000)

(def ^{:stratum 0 :private true} ceiling-ms 4000)

(defn- ^{:stratum 0} late-reply [expired _ [_ _ kind]]
  (when (= "pod" kind) (reset! expired true))
  {:exit 0 :out (if (= "pods" kind) "candidate" "Running")})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} settings []
  {:kubernetes/pod-ready-timeout-ms budget-ms
   :kubernetes/pod-ready-poll-interval-ms excessive-poll-ms})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-late-running-status-cannot-be-admitted
  (let [expired (atom false)]
    (with-redefs [deadline/expired? (fn [_] @expired)
                  deadline/remaining-ns (constantly 0)
                  command/run! (partial late-reply expired)]
      (is (= :pod-timeout
             (get-in (client/wait-for-pod-ready "fixture" "capsules" "job" (settings))
                     [:error :code]))))))

(deftest ^{:stratum 2} test-readiness-cancellation-preserves-the-interrupt
  (doseq [interrupt-before? [true false]]
    (with-redefs [command/run! (fn [& _] (.interrupt (Thread/currentThread)) {:exit 130})]
      (try
        (when interrupt-before? (.interrupt (Thread/currentThread)))
        (let [reply (client/wait-for-pod-ready "fixture" "capsules" "job" (settings))
              interrupted? (Thread/interrupted)]
          (is (= :pod-interrupted (get-in reply [:error :code])))
          (is interrupted?))
        (finally (Thread/interrupted))))))

(deftest ^{:stratum 2 :integration true} test-list-status-and-long-poll-share-one-deadline
  (doseq [body ["exec sleep 30"
                "case \"$2\" in pods) printf candidate;; *) exec sleep 30;; esac"
                "exit 0"]]
    (fs/with-temp-dir [directory {}]
      (let [executable (fs/path directory "mock-kubectl")
            started (System/nanoTime)]
        (spit (str executable) (str "#!/bin/sh\n" body "\n"))
        (.setExecutable (fs/file executable) true)
        (is (= :pod-timeout
               (get-in (client/wait-for-pod-ready (str executable) "capsules" "job" (settings))
                       [:error :code])))
        (is (< (/ (- (System/nanoTime) started) deadline/nanos-per-ms) ceiling-ms))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-readiness-integration-test))
