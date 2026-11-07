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
(ns ai.miniforge.agent-environment-kubernetes.kind-integration-test
  "Runs the provider against a real local kind cluster. Opt in by setting
   MINIFORGE_KIND_CONTEXT to a context whose name starts with `kind-`; any
   other context is refused, so this never reaches a shared cluster. No CI
   job runs it; unconfigured, it fails rather than passing silently."
  (:require
   [ai.miniforge.agent-environment-kubernetes.interface :as kubernetes]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} context-env "MINIFORGE_KIND_CONTEXT")

(def ^{:stratum 0} kubeconfig-env
  "Optional kubeconfig file holding that context, so the test never reads
   or changes the operator's default kubeconfig."
  "MINIFORGE_KIND_KUBECONFIG")

(def ^{:stratum 0} absent-runtime-class
  "A RuntimeClass no kind cluster installs: the dedicated-kernel stand-in."
  "kata")

(def ^{:stratum 0} ready-attempts
  "Readiness polls before giving up: an image pull on a cold kind node."
  90)

(def ^{:stratum 0} ready-interval-ms 2000)

(defn- ^{:stratum 0} kind-context? [c] (boolean (and c (str/starts-with? c "kind-"))))

(defn- ^{:stratum 0} refusing-pods
  "Real kubectl, except that creating a Pod is refused, as admission would."
  [settings args stdin]
  (if (and (= "create" (first args)) (str/includes? (str stdin) "\"Pod\""))
    {:kubectl/exit 1 :kubectl/out "" :kubectl/err "refused by test"}
    (kubernetes/kubectl-run settings args stdin)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} context
  "The kind context to run against, read once."
  (System/getenv context-env))

(def ^{:stratum 1} kubeconfig (System/getenv kubeconfig-env))

(defn- ^{:stratum 1} await-ready
  "Poll a readiness thunk until ready or out of attempts. Real pods start
   asynchronously; this is the one place a test waits."
  [poll]
  (loop [n ready-attempts]
    (let [r (poll)]
      (if (or (:readiness/ready? r) (zero? n))
        r
        (do (Thread/sleep (long ready-interval-ms)) (recur (dec n)))))))

(defn- ^{:stratum 1} settings [ctx config-file]
  (-> (kubernetes/default-settings)
      (assoc :kubernetes/context ctx)
      (cond-> config-file (assoc :kubernetes/kubeconfig config-file))
      (assoc-in [:kubernetes/runtimes :isolation/dedicated-kernel]
                {:runtime/class absent-runtime-class :runtime/node-selector {}})))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-kind-context-is-configured
  (is (kind-context? context) (str "set " context-env " to a kind-* context to run these tests")))

(deftest ^{:stratum 2} test-provider-passes-the-contract-suite-on-kind
  (when (kind-context? context)
    (let [results (env/run-conformance {:make-provider #(kubernetes/create-provider (settings context kubeconfig))
                                        :make-partial-provider #(kubernetes/create-provider (settings context kubeconfig) refusing-pods)
                                        :await-ready await-ready})]
      (doseq [{:check/keys [id outcome detail]} results]
        (is (= :check/passed outcome) (str id " " detail))))))

(deftest ^{:stratum 2} test-dedicated-kernel-never-falls-back-to-the-node-default
  (when (kind-context? context)
    (let [provider (kubernetes/create-provider (settings context kubeconfig))
          request {:environment/isolation :isolation/dedicated-kernel
                   :environment/limits {:limits/cpu-millicores 100 :limits/memory-mib 64 :limits/ephemeral-storage-mib 64}
                   :environment/tenant-id (random-uuid)
                   :environment/run-id (random-uuid)
                   :environment/task-id (random-uuid)}
          target {:target/namespace (str "mf-env-kind-" (subs (str (random-uuid)) 0 8)) :target/uid nil :target/generation 1}]
      (testing "given no installed RuntimeClass → preflight refuses"
        (is (= [:anomalies.agent-environment-kubernetes/runtime-class-missing] (protocol/probe provider request))))
      (testing "given a provision forced past preflight → the API refuses the pod; nothing runs on runc"
        (let [provisioned (protocol/provision! provider target request)
              cleanup (protocol/deprovision! provider (assoc target :target/uid (:provision/uid provisioned)))]
          (is (= [:outcome/applied :outcome/failed] (mapv :step/outcome (:provision/steps provisioned))))
          (is (= [:outcome/applied] (mapv :step/outcome (:deprovision/steps cleanup)))))))))
