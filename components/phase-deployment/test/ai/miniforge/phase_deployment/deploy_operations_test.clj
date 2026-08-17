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
(ns ai.miniforge.phase-deployment.deploy-operations-test
  "Normalized Kubernetes operations available to application flows."
  (:require
   [ai.miniforge.phase-deployment.deploy-operations :as operations]
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} target
  {:kustomize-dir "/k8s" :namespace "prod" :context "gke"})

(def ^{:stratum 0} rendered-yaml "manifest")

(def ^{:stratum 0} render-result ::render-result)

(def ^{:stratum 0} dry-run-result ::dry-run-result)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} operations-preserve-provider-results-test
  (with-redefs [provider/render! (constantly render-result)
                provider/dry-run! (fn [_ _] dry-run-result)]
    (let [ops (operations/operations)]
      (is (= render-result ((:render! ops) target)))
      (is (= dry-run-result
             ((:server-dry-run! ops) target rendered-yaml))))))

(deftest ^{:stratum 1} operations-pass-explicit-bytes-to-provider-test
  (let [applied (atom nil)]
    (with-redefs [provider/apply-rendered!
                  (fn [actual-target rendered]
                    (reset! applied [actual-target rendered])
                    nil)]
      ((:apply-rendered! (operations/operations)) target rendered-yaml)
      (is (= [target rendered-yaml] @applied)
          "the adapter must neither cache nor re-render the artifact"))))
