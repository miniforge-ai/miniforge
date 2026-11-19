;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-pod-observation-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation :as observation]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.kubernetes-target.interface :as target]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} retained
  {:job-name "capsule" :job-uid "original-job" :namespace "capsules"})

(def ^{:stratum 0 :private true} candidate {:pod-name "candidate"})

(def ^{:stratum 0 :private true} observation-timeout-ms 6500)

(defn- ^{:stratum 0} pod [{:keys [job-name job-uid namespace]} pod-name]
  {"apiVersion" "v1"
   "kind" "Pod"
   "metadata" {"name" pod-name "namespace" namespace "uid" "pod-uid" "resourceVersion" "pod-version"
               "ownerReferences" [{"apiVersion" "batch/v1" "kind" "Job" "name" job-name
                                   "uid" job-uid "controller" true}]}
   "status" {"phase" "Running"}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reply [change]
  {:exit 0 :out (json/generate-string (change (pod retained (:pod-name candidate))))})

(deftest ^{:stratum 1} interrupted-readback-preserves-cancellation
  (with-redefs [command/run! (fn [& _] (.interrupt (Thread/currentThread)) {:exit 130})]
    (try
      (let [failure (observation/verify! nil retained observation-timeout-ms candidate)
            interrupted? (Thread/interrupted)]
        (is (= :pod-owner-unconfirmed (get-in failure [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} exact-observed-pod-identity-is-retained
  (with-redefs [command/run! (fn [until args]
                                    (is (pos? (deadline/remaining-ns until)))
                                    (is (some #{"--request-timeout=6500ms"} args))
                                    (is (= ["--" (:pod-name candidate)] (take-last 2 args)))
                                    (reply identity))]
    (is (= (assoc candidate :pod-uid "pod-uid" :pod-observed-resource-version "pod-version")
           (:data (observation/verify! nil retained observation-timeout-ms candidate))))))

(deftest ^{:stratum 2} name-and-status-cannot-substitute-for-current-ownership
  (doseq [change [#(assoc-in % ["metadata" "ownerReferences" 0 "uid"] "replacement-job")
                  #(assoc-in % ["metadata" "ownerReferences" 0 "controller"] false)
                  #(assoc-in % ["metadata" "namespace"] "another-namespace")
                  #(assoc-in % ["metadata" "name"] "another-pod")
                  #(assoc-in % ["metadata" "deletionTimestamp"] "2026-11-15T00:00:00Z")
                  #(update % "metadata" dissoc "uid")
                  #(assoc-in % ["status" "phase"] "Failed")]]
    (with-redefs [command/run! (constantly (reply change))]
      (is (= :pod-owner-unconfirmed
             (get-in (observation/verify! nil retained observation-timeout-ms candidate) [:error :code]))))))

(deftest ^{:stratum 2} late-identity-validation-does-not-admit-the-pod
  (let [expired (atom false)]
    (with-redefs [command/run! (constantly (reply identity))
                  deadline/expired? (fn [_] @expired)
                  target/owns? (fn [& _] (reset! expired true))]
      (is (= :pod-owner-unconfirmed
             (get-in (observation/verify! nil retained observation-timeout-ms candidate) [:error :code]))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-pod-observation-test))
