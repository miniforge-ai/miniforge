;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-job-creation-test
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes :as kubernetes]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation :as creation]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources :as resources]
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

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(deftest ^{:stratum 0} resource-projection-preserves-defaults-and-stringifies-cpu
  (is (= {:limits {:memory "1Gi" :cpu "2"}
          :requests {:memory "256Mi" :cpu "100m"}}
         (resources/build-resource-spec {:limits {:memory "1Gi" :cpu 2}}))))

(deftest ^{:stratum 0} relocated-readiness-check-keeps-running-and-terminal-results-distinct
  (doseq [[phase success?] [["Running" true] ["Failed" false] ["Succeeded" false]]]
    (with-redefs [shell/sh (fn [& [_ _ kind]]
                            {:exit 0 :out (if (= "pods" kind) "mock-capsule-pod" phase)})]
      (let [observed (client/wait-for-pod-ready nil "capsules" "job" 1000)]
        (is (= success? (result/ok? observed)))
        (when-not success? (is (= :pod-failed (get-in observed [:error :code]))))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} response [args altered]
  (let [job (update (requested args) :metadata merge identity-metadata)]
    {:exit 0 :out (json/generate-string (altered job))}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} acquire-creates-once-and-retains-the-exact-job-receipt
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        submitted (atom 0)]
    (with-redefs [shell/sh (fn [& args]
                            (swap! submitted inc)
                            (is (= "create" (second args)))
                            (is (= creation/default-deadline-seconds
                                   (get-in (requested args) [:spec :activeDeadlineSeconds])))
                            (response args identity))
                  client/wait-for-pod-ready readiness
                  kubernetes/delete-job reject-io!]
      (let [acquired (executor/acquire-environment! instance (random-uuid) {})
            metadata (get-in acquired [:data :metadata])]
        (is (result/ok? acquired))
        (is (= 1 @submitted))
        (is (= (:uid identity-metadata) (:job-uid metadata)))
        (is (= (:resourceVersion identity-metadata) (:job-creation-resource-version metadata)))
        (is (= "capsules" (:namespace metadata)))))))

(deftest ^{:stratum 2} uncertain-job-creation-is-never-adopted-or-cleaned-up-by-name
  (let [instance (kubernetes/create-kubernetes-executor {:namespace "capsules"})
        mutations [#(update % :metadata dissoc :uid)
                   #(assoc-in % [:metadata :resourceVersion] "")
                   #(assoc-in % [:metadata :name] "another-job")
                   #(assoc-in % [:metadata :namespace] "another-namespace")
                   #(assoc-in % [:spec :activeDeadlineSeconds] 7200)
                   #(assoc % :kind "Pod")]]
    (with-redefs [client/wait-for-pod-ready reject-io! kubernetes/delete-job reject-io!]
      (doseq [reply (concat (map (fn [altered] (fn [& args] (response args altered))) mutations)
                            [(constantly {:exit 1 :err "AlreadyExists"})
                             (constantly {:exit 0 :out "not-json"})])]
        (with-redefs [shell/sh reply]
          (is (result/err? (executor/acquire-environment! instance (random-uuid) {}))))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-job-creation-test))
