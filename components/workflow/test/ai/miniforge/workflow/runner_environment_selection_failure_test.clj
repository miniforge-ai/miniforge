;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.workflow.runner-environment-selection-failure-test
  "A governed run whose container-runtime selection failed reports that
   failure (runtime kind, reason), not the generic no-capsule message.
   The acquisition tests use the real executor registry; the configs
   chosen fail selection without a container runtime on the host."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner-environment :as env])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} governed-acquisition-failure
  "Run governed acquisition with `runtime-config` against the real
   registry. Returns the thrown ex-data, or nil when nothing was thrown."
  [runtime-config]
  (try
    (env/acquire-execution-environment! :wf {:execution-mode :governed
                                             :runtime-config runtime-config})
    nil
    (catch ExceptionInfo e (ex-data e))))

(deftest ^{:stratum 0} runtime-selection-failure-test
  (let [fns (fn [error] {:executor-type (constantly :unavailable)
                         :available?    (constantly {:data {:available? false :error error}})})]
    (testing "an auto-probe failure lists each probed runtime's reason"
      (is (= "No OCI-compatible container runtime is available. (podman: not installed; docker: daemon down)"
             (:detail (env/runtime-selection-failure
                       {:docker :slot}
                       (fns {:code :runtime/none-available
                             :message "No OCI-compatible container runtime is available."
                             :data {:probed [{:kind :podman :available? false :reason "not installed"}
                                             {:kind :docker :available? false :reason "daemon down"}]}}))))))
    (testing "an error without per-runtime reasons is its message alone"
      (is (= "Runtime kind nerdctl is not supported."
             (:detail (env/runtime-selection-failure
                       {:docker :slot}
                       (fns {:code :runtime/explicit-unsupported
                             :message "Runtime kind nerdctl is not supported."
                             :data {:kind :nerdctl}}))))))
    (testing "no :unavailable slot, or no registry map: nil"
      (is (nil? (env/runtime-selection-failure {:docker :slot}
                                               {:executor-type (constantly :docker)
                                                :available?    (constantly {:data {:available? true}})})))
      (is (nil? (env/runtime-selection-failure :stub-registry (fns {})))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} explicit-runtime-unavailable-is-reported-test
  (testing "an explicit runtime whose probe fails: kind and reason in the message and data"
    (let [data (governed-acquisition-failure {:runtime-kind :podman
                                              :executable "/nonexistent/miniforge-test/podman"})
          selection (:runtime-selection data)]
      (is (= :anomalies.executor/unavailable (:anomaly/category data)))
      (is (str/includes? (:anomaly/message data) "Runtime :podman is configured but unavailable."))
      (is (str/includes? (:anomaly/message data) "(podman: "))
      (is (= :runtime/explicit-unavailable (:code selection)))
      (is (= :podman (get-in selection [:data :kind])))
      (is (string? (get-in selection [:data :reason]))))))

(deftest ^{:stratum 1} executable-without-kind-is-reported-test
  (testing "an executable override without a runtime kind names that rejection"
    (let [data (governed-acquisition-failure {:executable "/opt/bin/docker"})]
      (is (str/includes? (:anomaly/message data) "requires an explicit :runtime-kind"))
      (is (= :runtime/executable-requires-kind (get-in data [:runtime-selection :code]))))))
