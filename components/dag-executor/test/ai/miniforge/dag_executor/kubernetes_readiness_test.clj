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

(def ^{:stratum 0 :private true} invalid-budgets
  "Refuse absent, nonintegral, nonpositive and overflowing millisecond options."
  [nil false 0 -1 1.5 (inc Integer/MAX_VALUE)])

(def ^{:stratum 0 :private true} projection-data-resource
  "Corrupt only fixed projection data, leaving settings and message loading intact."
  "config/dag-executor/runtime/kubernetes-defaults.edn")

(defn- ^{:stratum 0} resource-override [memory cpu]
  {:limits {:memory memory
            :cpu cpu}})

(defn- ^{:stratum 0} command-reply [output]
  {:exit 0
   :out output})

(defn- ^{:stratum 0} invalid-setting [key value]
  (try+
    (kubernetes/create-kubernetes-executor {key value})
    (catch map? error (:config/key error))))

(defn- ^{:stratum 0} refuse-provider! [& _]
  (throw (AssertionError.)))

(defn- ^{:stratum 0} accepted-job [& _]
  (result/ok {:applied true}))

(defn- ^{:stratum 0} observe-readiness [expected _ namespace _ effective]
  (is (= "default" namespace))
  (is (= expected (select-keys effective (keys expected))))
  (result/ok {:pod-name "mock-capsule-pod"}))

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

(deftest ^{:stratum 1} test-invalid-polling-budgets-are-refused-at-construction
  (doseq [key (keys readiness-settings)
          value invalid-budgets]
    (is (= key (invalid-setting key value)))))

(deftest ^{:stratum 1} test-configured-polling-budgets-reach-acquisition
  (let [instance (kubernetes/create-kubernetes-executor readiness-settings)]
    (with-redefs [shell/sh refuse-provider!
                  kubernetes/apply-job accepted-job
                  client/wait-for-pod-ready (partial observe-readiness readiness-settings)]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {}))))))

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
