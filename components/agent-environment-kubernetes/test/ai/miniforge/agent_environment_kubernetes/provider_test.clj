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
(ns ai.miniforge.agent-environment-kubernetes.provider-test
  (:require
   [ai.miniforge.agent-environment-kubernetes.fixtures :as fx]
   [ai.miniforge.agent-environment-kubernetes.interface :as kubernetes]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} provider
  [calls answers]
  (kubernetes/create-provider (fx/settings) (fx/scripted calls answers)))

(def ^{:stratum 0} read-only "sh: can't create /etc/report.txt: Read-only file system")

(def ^{:stratum 0} healthy
  [[["get" "--raw"] (fx/result 0 "ok")]
   [["get" "runtimeclass"] (fx/result 0 "runtimeclass.node.k8s.io/kata-test")]
   [["get" "nodes"] (fx/result 0 "node/sandbox-1")]
   [["get" "namespace"] (fx/result 0 fx/namespace-json)]
   [["create"] (fx/result 0 fx/namespace-json)]])

(deftest ^{:stratum 0} test-settings-must-name-a-context-and-a-pinned-image
  (is (= :anomalies.agent-environment-kubernetes/invalid-settings
         (anomaly/subtype (kubernetes/create-provider (kubernetes/default-settings)))))
  (is (= :anomalies.agent-environment-kubernetes/invalid-settings
         (anomaly/subtype (kubernetes/create-provider (assoc (fx/settings) :kubernetes/image "busybox:latest")))))
  (is (= :anomalies.agent-environment-kubernetes/invalid-settings
         (anomaly/subtype (kubernetes/create-provider
                           (assoc-in (fx/settings) [:kubernetes/runtimes :isolation/dedicated-kernel :runtime/class] nil))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-preflight-requires-the-runtime-class-and-its-nodes
  (testing "given a healthy cluster → no failures"
    (is (= [] (protocol/probe (provider (atom []) healthy) fx/request))))
  (testing "given each missing piece → its own reason, never a weaker runtime"
    (is (= [:anomalies.agent-environment-kubernetes/cluster-unreachable]
           (protocol/probe (provider (atom []) (into [[["get" "--raw"] fx/not-found]] healthy)) fx/request)))
    (is (= [:anomalies.agent-environment-kubernetes/runtime-class-missing]
           (protocol/probe (provider (atom []) (into [[["get" "runtimeclass"] fx/not-found]] healthy)) fx/request)))
    (is (= [:anomalies.agent-environment-kubernetes/no-eligible-nodes]
           (protocol/probe (provider (atom []) (into [[["get" "nodes"] (fx/result 0 "")]] healthy)) fx/request)))))

(deftest ^{:stratum 1} test-capabilities-offer-only-mapped-isolation
  (let [unmapped (kubernetes/create-provider (assoc (fx/settings) :kubernetes/runtimes
                                                    {:isolation/shared-kernel {:runtime/class nil :runtime/node-selector {}}})
                                             (fx/scripted (atom []) healthy))]
    (is (= #{:isolation/shared-kernel} (:capabilities/isolation (protocol/capabilities unmapped))))
    (is (= #{:isolation/shared-kernel :isolation/dedicated-kernel}
           (:capabilities/isolation (protocol/capabilities (provider (atom []) healthy)))))))

(deftest ^{:stratum 1} test-refused-pod-leaves-a-partial-provision
  (let [runner (fx/refusing-pods (fx/scripted (atom []) healthy))
        result (protocol/provision! (kubernetes/create-provider (fx/settings) runner) fx/target fx/request)]
    (is (= fx/uid (:provision/uid result)))
    (is (= [:outcome/applied :outcome/failed] (mapv :step/outcome (:provision/steps result))))))

(deftest ^{:stratum 1} test-a-lost-namespace-create-is-uncertain-and-a-refused-one-failed
  (let [steps #(mapv :step/outcome (:provision/steps (protocol/provision! (provider (atom []) (into % healthy)) fx/target fx/request)))
        lost (anomaly/anomaly :timeout "kubectl deadline" {})]
    (is (= [:outcome/uncertain] (steps [[["create"] lost]])))
    (is (= [:outcome/failed] (steps [[["create"] lost] [["get" "namespace"] fx/not-found]])))
    (is (= [:outcome/failed] (steps [[["create"] (fx/refusal "AlreadyExists")]])))))

(deftest ^{:stratum 1} test-a-failed-file-command-keeps-its-path-and-output
  (let [failing (provider (atom []) (into [[["exec"] (assoc (fx/result 1 "") :kubectl/err read-only)]] healthy))
        written (protocol/write-file! failing fx/target "/etc/report.txt" "text")
        read (protocol/read-file failing fx/target "/work/missing.txt")]
    (is (= :anomalies.agent-environment-kubernetes/file-write-failed (anomaly/subtype written)))
    (is (= read-only (get-in written [:anomaly/data :exec/stderr])))
    (is (= :anomalies.agent-environment-kubernetes/file-read-failed (anomaly/subtype read)))
    (is (= "/work/missing.txt" (get-in read [:anomaly/data :file/path])))))

(deftest ^{:stratum 1} test-cancel-is-applied-only-when-nothing-is-left-running
  (let [cancel-with #(protocol/cancel! (provider (atom []) (into [[["exec"] %]] healthy)) fx/target)]
    (is (= [:outcome/applied] (mapv :step/outcome (:cancel/steps (cancel-with (fx/result 0 ""))))))
    (is (= [:outcome/uncertain] (mapv :step/outcome (:cancel/steps (cancel-with (fx/result 3 ""))))))
    (is (= [:outcome/failed] (mapv :step/outcome (:cancel/steps (cancel-with (fx/result 1 ""))))))))

(deftest ^{:stratum 1} test-observe-reports-the-labelled-generation
  (is (= {:observed/uid fx/uid :observed/generation 1}
         (select-keys (protocol/observe (provider (atom []) healthy) fx/target) [:observed/uid :observed/generation]))))

(deftest ^{:stratum 1} test-calls-on-a-recreated-namespace-are-refused
  (let [calls (atom [])
        replaced (provider calls (into [[["get" "namespace"] (fx/result 0 "{\"metadata\":{\"uid\":\"other\"}}")]] healthy))]
    (is (= :anomalies.agent-environment-kubernetes/target-missing
           (anomaly/subtype (protocol/exec! replaced fx/target {:exec/argv ["true"] :exec/timeout-ms 1000}))))
    (is (= [:outcome/failed] (mapv :step/outcome (:deprovision/steps (protocol/deprovision! replaced fx/target)))))
    (is (not-any? #(= "delete" (first (:args %))) @calls))))

(deftest ^{:stratum 1} test-release-is-confirmed-only-when-the-namespace-is-gone
  (let [absent (provider (atom []) (into [[["get" "namespace"] fx/not-found]] healthy))
        lingering (provider (atom []) healthy)]
    (is (= [:outcome/applied] (mapv :step/outcome (:deprovision/steps (protocol/deprovision! absent fx/target)))))
    (is (= [:outcome/uncertain] (mapv :step/outcome (:deprovision/steps (protocol/deprovision! lingering fx/target)))))
    (testing "given a delete whose wait timed out → uncertain; one the API refused → failed"
      (is (= [:outcome/uncertain] (mapv :step/outcome (:deprovision/steps (protocol/deprovision! (provider (atom []) (into [[["delete"] (fx/result 1 "")]] healthy)) fx/target)))))
      (is (= [:outcome/failed] (mapv :step/outcome (:deprovision/steps (protocol/deprovision! (provider (atom []) (into [[["delete"] (fx/refusal "Forbidden")]] healthy)) fx/target))))))))
