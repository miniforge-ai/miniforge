;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-job-creation-test
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources :as resources]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation :as observation]
            [ai.miniforge.dag-executor.result :as result]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} identity-metadata
  {:uid "observed-job-uid" :resourceVersion "observed-creation-version"})

(defn- ^{:stratum 0} requested [args]
  (json/parse-string-strict (last args) true))

(defn- ^{:stratum 0} readiness [& _]
  (result/ok {:pod-name "mock-capsule-pod"}))

(defn- ^{:stratum 0} observed [& _]
  (result/ok {:pod-name "mock-capsule-pod" :pod-uid "observed-pod-uid"}))

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} invalid-setting [options]
  (try
    (kubernetes/create-kubernetes-executor options)
    (catch clojure.lang.ExceptionInfo e (:config/key (ex-data e)))))

(deftest ^{:stratum 0} resource-projection-preserves-defaults-and-stringifies-cpu
  (is (= {:limits {:memory "1Gi" :cpu "2"}
          :requests {:memory "256Mi" :cpu "100m"}}
         (resources/build-resource-spec {:limits {:memory "1Gi" :cpu 2}}))))

(deftest ^{:stratum 0} relocated-readiness-check-keeps-running-and-terminal-results-distinct
  (doseq [[phase success?] [["Running" true] ["Failed" false] ["Succeeded" false]]]
    (with-redefs [shell/sh (fn [& [_ _ kind]]
                            {:exit 0 :out (if (= "pods" kind) "mock-capsule-pod" phase)})]
      (let [observed (client/wait-for-pod-ready nil "capsules" "job"
                                               {:kubernetes/pod-ready-timeout-ms 1000
                                                :kubernetes/pod-ready-poll-interval-ms 20})]
        (is (= success? (result/ok? observed)))
        (when-not success? (is (= :pod-failed (get-in observed [:error :code]))))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} response [args altered]
  (let [job (update (requested args) :metadata merge identity-metadata)]
    {:exit 0 :out (json/generate-string (altered job))}))

(deftest ^{:stratum 1} invalid-capsule-budgets-fail-before-provider-io
  (let [instance (kubernetes/create-kubernetes-executor {})]
    (with-redefs [shell/sh reject-io!]
      (doseq [invalid [nil false "3600" 3600.0 0 -1 (inc' Long/MAX_VALUE)]
              :let [budget {:resources/timeout-seconds invalid}]]
        (is (= :resources/timeout-seconds (invalid-setting budget)))
        (is (= :invalid-capsule-deadline
               (get-in (executor/acquire-environment! instance (random-uuid) {:resources budget})
                       [:error :code])))))))

(deftest ^{:stratum 1} invalid-transport-budgets-are-rejected-at-configuration
  (with-redefs [shell/sh reject-io!]
    (doseq [key [:kubernetes/creation-request-timeout-ms
                 :kubernetes/pod-observation-request-timeout-ms
                 :kubernetes/pod-ready-timeout-ms
                 :kubernetes/pod-ready-poll-interval-ms]
            invalid [nil false 0 1.5 (inc Integer/MAX_VALUE)]]
      (is (= key (invalid-setting {key invalid}))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} acquire-creates-once-and-retains-the-exact-job-receipt
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        submitted (atom 0)]
    (with-redefs [shell/sh (fn [& args]
                            (swap! submitted inc)
                            (is (= "create" (second args)))
                            (is (some #{"--request-timeout=30000ms"} args))
                            (is (= 3600 (get-in (requested args) [:spec :activeDeadlineSeconds])))
                            (response args identity))
                  client/wait-for-pod-ready readiness
                  observation/verify! observed
                  kubernetes/delete-job reject-io!]
      (let [acquired (executor/acquire-environment! instance (random-uuid) {})
            metadata (get-in acquired [:data :metadata])]
        (is (result/ok? acquired))
        (is (= 1 @submitted))
        (is (= (:uid identity-metadata) (:job-uid metadata)))
        (is (= (:resourceVersion identity-metadata) (:job-creation-resource-version metadata)))
        (is (= "capsules" (:namespace metadata)))
        (is (= "observed-pod-uid" (:pod-uid metadata)))))))

(deftest ^{:stratum 2} explicit-capsule-budgets-are-submitted-and-confirmed
  (let [instance (kubernetes/create-kubernetes-executor {:resources/timeout-seconds 5400})
        submitted (atom [])]
    (with-redefs [shell/sh (fn [& args]
                            (swap! submitted conj (get-in (requested args) [:spec :activeDeadlineSeconds]))
                            (response args identity))
                  client/wait-for-pod-ready readiness
                  observation/verify! observed]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {})))
      (is (result/ok? (executor/acquire-environment! instance (random-uuid)
                                                     {:resources {:resources/timeout-seconds 7200}})))
      (is (= [5400 7200] @submitted)))))

(deftest ^{:stratum 2} executor-transport-budgets-reach-their-own-boundaries
  (let [settings {:kubernetes/creation-request-timeout-ms 4500
                  :kubernetes/pod-observation-request-timeout-ms 7000
                  :kubernetes/pod-ready-timeout-ms 16000
                  :kubernetes/pod-ready-poll-interval-ms 500}
        instance (kubernetes/create-kubernetes-executor settings)]
    (with-redefs [shell/sh (fn [& args]
                            (is (some #{"--request-timeout=4500ms"} args))
                            (is (= 3600 (get-in (requested args) [:spec :activeDeadlineSeconds])))
                            (response args identity))
                  client/wait-for-pod-ready (fn [_ _ _ effective]
                                              (is (= settings (select-keys effective (keys settings))))
                                              (readiness))
                  observation/verify! (fn [_ _ timeout-ms _]
                                        (is (= (:kubernetes/pod-observation-request-timeout-ms settings)
                                               timeout-ms))
                                        (observed))]
      (is (result/ok? (executor/acquire-environment! instance (random-uuid) {}))))))

(deftest ^{:stratum 2} uncertain-job-creation-is-never-adopted-or-cleaned-up-by-name
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"
                                                         :resources/timeout-seconds 5400})
        mutations [#(update % :metadata dissoc :uid)
                   #(assoc-in % [:metadata :resourceVersion] "")
                   #(assoc-in % [:metadata :name] "another-job")
                   #(assoc-in % [:metadata :namespace] "another-namespace")
                   #(assoc-in % [:spec :activeDeadlineSeconds] 3600)
                   #(assoc % :kind "Pod")]]
    (with-redefs [client/wait-for-pod-ready reject-io!
                  observation/verify! reject-io!
                  kubernetes/delete-job reject-io!]
      (doseq [reply (concat (map (fn [altered] (fn [& args] (response args altered))) mutations)
                            [(constantly {:exit 1 :err "AlreadyExists"})
                             (constantly {:exit 0 :out "not-json"})])]
        (with-redefs [shell/sh reply]
          (is (result/err? (executor/acquire-environment! instance (random-uuid) {}))))))))

(deftest ^{:stratum 2} failed-pod-proof-does-not-delete-by-reusable-job-name
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        refused (result/err :pod-owner-unconfirmed "fixture refusal")]
    (doseq [[wait! verify!] [[readiness (constantly refused)]
                            [(constantly refused) reject-io!]]]
      (with-redefs [shell/sh (fn [& args] (response args identity))
                    client/wait-for-pod-ready wait!
                    observation/verify! verify!
                    kubernetes/delete-job reject-io!]
        (is (= refused (executor/acquire-environment! instance (random-uuid) {})))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-job-creation-test))
