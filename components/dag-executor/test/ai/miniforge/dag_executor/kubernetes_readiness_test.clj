;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-readiness-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.config.interface :as config]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-acquisition :as acquisition]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-defaults :as defaults]
            [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources :as resources]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as fixtures]
            [ai.miniforge.dag-executor.kubernetes-pod-fixtures :as pods]
            [ai.miniforge.dag-executor.kubernetes-native-fixtures :as native]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} expected-default-resources
  "Pin the legacy defaults independently of their new packaged representation."
  {:limits {:memory "512Mi"
            :cpu "500m"}
   :requests {:memory "256Mi"
              :cpu "100m"}})

(def ^{:stratum 0 :private true} custom-memory
  "A task memory override must replace only its corresponding default."
  "1Gi")

(def ^{:stratum 0 :private true} custom-cpu
  "A numeric task CPU request must retain its value as provider text."
  2)

(def ^{:stratum 0 :private true} environment-value
  "An arbitrary non-string payload exposes missing environment stringification."
  42)

(def ^{:stratum 0 :private true} readiness-settings
  "Mocked commands return immediately; keep this packet away from incidental expiry."
  {:kubernetes/pod-ready-timeout-ms 1000
   :kubernetes/pod-ready-poll-interval-ms 20})

(def ^{:stratum 0 :private true} phase-cases
  "A completed or failed Pod is not the interactive Running outcome."
  [["Running" true] ["Failed" false] ["Succeeded" false]])

(def ^{:stratum 0 :private true} shipped-readiness-settings
  "Keep the existing fixed 120-second deadline and two-second polling defaults."
  {:kubernetes/pod-ready-timeout-ms 120000
   :kubernetes/pod-ready-poll-interval-ms 2000})

(def ^{:stratum 0 :private true} projection-data-resource
  "Corrupt only fixed projection data, leaving settings and message loading intact."
  "config/dag-executor/runtime/kubernetes-defaults.edn")

(defn- ^{:stratum 0} resource-override [memory cpu]
  {:limits {:memory memory
            :cpu cpu}})

(defn- ^{:stratum 0} command-reply [output]
  (fixtures/provider-reply 0 output))

(defn- ^{:stratum 0} refuse-provider! [& _]
  (throw (AssertionError.)))

(defn- ^{:stratum 0} accepted-job [& _]
  (result/ok {:applied true}))

(defn- ^{:stratum 0} interrupted-submission [& _]
  (throw (InterruptedException.)))

(defn- ^{:stratum 0} interrupted-observation [reply]
  (.interrupt (Thread/currentThread))
  reply)

(defn- ^{:stratum 0} observe-readiness [expected _ namespace _ effective]
  (is (= "default" namespace))
  (is (= expected (select-keys effective (keys expected))))
  (pods/ready))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} malformed-defaults [loader path & options]
  (if (= projection-data-resource path) {} (apply loader path options)))

(defn- ^{:stratum 1} observed-phase [phase _ [_ _ kind]]
  (command-reply (if (= "pods" kind) "mock-capsule-pod" phase)))

(deftest ^{:stratum 1} test-resource-defaults-and-projection-are-preserved
  (is (= expected-default-resources (resources/build-resource-spec nil)))
  (is (= expected-default-resources (resources/build-resource-spec {})))
  (is (= (-> expected-default-resources
              (assoc-in [:limits :memory] custom-memory)
              (assoc-in [:limits :cpu] (str custom-cpu)))
         (resources/build-resource-spec (resource-override custom-memory custom-cpu))))
  (is (= (assoc expected-default-resources :limits {})
         (resources/build-resource-spec (resource-override nil false)))))

(deftest ^{:stratum 1} test-environment-projection-and-default-workdir-are-preserved
  (is (= [{:name "APP_VALUE"
           :value (str environment-value)}]
         (resources/build-env-vars {:APP_VALUE environment-value})))
  (is (= [] (resources/build-env-vars nil)))
  (is (= "/workspace" (resources/default-workdir))))

(deftest ^{:stratum 1} test-rejected-submission-never-observes-cleans-or-retains
  (let [rejected (result/err :job-apply-failed (messages/t :kubernetes/pod-timeout))]
    (is (= rejected (acquisition/acquire! (constantly rejected)
                                          refuse-provider! refuse-provider! refuse-provider!)))))

(deftest ^{:stratum 1} test-failed-readiness-preserves-legacy-cleanup-without-retention
  (let [actions (atom [])
        rejected (result/err :pod-timeout (messages/t :kubernetes/pod-timeout))]
    (is (= rejected (acquisition/acquire! accepted-job
                                          (constantly rejected)
                                          (partial swap! actions conj :cleanup)
                                          refuse-provider!)))
    (is (= [:cleanup] @actions))))

(deftest ^{:stratum 1} test-cancelled-submission-never-observes-cleans-or-retains
  (doseq [[already? submit!] [[true refuse-provider!] [false interrupted-submission]]]
    (try+
      (when already? (.interrupt (Thread/currentThread)))
      (let [reply (acquisition/acquire! submit! refuse-provider! refuse-provider! refuse-provider!)
            interrupted? (Thread/interrupted)]
        (is (= :pod-interrupted (get-in reply [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} test-cancelled-observation-never-cleans-or-retains
  (doseq [observed [(result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted))
                    (pods/ready)]]
    (try+
      (let [reply (acquisition/acquire! accepted-job (partial interrupted-observation observed)
                                         refuse-provider! refuse-provider!)
            interrupted? (Thread/interrupted)]
        (is (= :pod-interrupted (get-in reply [:error :code])))
        (is interrupted?))
      (finally (Thread/interrupted)))))

(deftest ^{:stratum 1} test-cancelled-successful-submission-never-starts-observation
  (try+
    (let [submit! (partial interrupted-observation (accepted-job))
          reply (acquisition/acquire! submit! refuse-provider! refuse-provider! refuse-provider!)
          interrupted? (Thread/interrupted)]
      (is (= :pod-interrupted (get-in reply [:error :code])))
      (is interrupted?))
    (finally (Thread/interrupted))))

(deftest ^{:stratum 1} test-shipped-polling-budgets-reach-acquisition
  (let [instance (kubernetes/create-kubernetes-executor {})
        created (atom nil)]
    (with-redefs [shell/sh refuse-provider!
                  command/run! (partial native/dispatch! created (partial fixtures/response identity) pods/read-pod!)
                  client/wait-for-pod-ready (partial observe-readiness shipped-readiness-settings)]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {}))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-malformed-fixed-defaults-are-refused
  (let [loader config/load-config-resource]
    (with-redefs [config/load-config-resource (partial malformed-defaults loader)]
      (is (= projection-data-resource
             (try+
               (defaults/data)
               (catch map? error (:config/resource error))))))))

(deftest ^{:stratum 2} test-running-and-terminal-outcomes-stay-distinct
  (doseq [[phase success?] phase-cases]
    (with-redefs [command/run! (partial observed-phase phase)]
      (let [observed (client/wait-for-pod-ready nil "capsules" "job" readiness-settings)]
        (is (= success? (result/ok? observed)))
        (when-not success?
          (is (= :pod-failed (get-in observed [:error :code]))))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-readiness-test))
