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
(ns ai.miniforge.phase-deployment.deploy-flow-test
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase-deployment.deploy :as deploy]
   [ai.miniforge.phase-deployment.deploy-flow :as flow]
   [ai.miniforge.phase-deployment.deploy-provider :as provider]
   [ai.miniforge.phase-deployment.shell :as shell]
   [ai.miniforge.phase-deployment.shell.exec :as exec]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} deployment-config
  []
  {:phase-config {}
   :kustomize-dir "/deploy"
   :namespace "production"
   :context "cluster-1"
   :app-label "api"
   :deployment-name "api"})

(def ^{:stratum 0} rollback-info {:revision "7" :image "api:v7" :replicas 3})

(defn ^{:stratum 0} apply-failure
  "A refused apply in the shape `kustomize-apply!` actually produces, taken
   from that producer with only the process boundary stubbed. Hand-writing
   this map is how a fixture drifts from what deploys really return — the
   previous one had no `:rendered-yaml` at all, and passed only because the
   flow guessed at `[:build-result :stdout]`."
  []
  (with-redefs [exec/sh-with-timeout
                (fn [command _args & _]
                  (if (= "kustomize" command)
                    (schema/success :stdout "image: api:v8")
                    (schema/failure :stdout "apply refused" {:stderr "apply refused"})))]
    (shell/kustomize-apply! "/deploy")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} rollout-failure
  []
  {:deploy/status :failed
   :deploy/stage :observe
   :deploy/rollback-info rollback-info
   :deploy/rendered-yaml "image: api:v8"
   :deploy/failure "rollout timed out"})

(deftest ^{:stratum 1} apply-failure-preserves-rollback-test
  (with-redefs [provider/rollback-info! (constantly rollback-info)
                provider/apply! (constantly (apply-failure))]
    (let [deployment (flow/execute! (deployment-config))]
      (is (= :failed (:deploy/status deployment)))
      (is (= :apply (:deploy/stage deployment)))
      (is (= "image: api:v8" (:deploy/rendered-yaml deployment))
          "the manifest reaches the flow from :rendered-yaml, not a guessed key")
      (is (= rollback-info (:deploy/rollback-info deployment))))))

(deftest ^{:stratum 1} invalid-rollback-stops-apply-test
  (with-redefs [provider/rollback-info! (constantly (schema/validate-anomaly [:map [:valid? true?]] {}))
                provider/apply! #(throw (ex-info "apply must not run" %))]
    (is (= :capture (:deploy/stage (flow/execute! (deployment-config)))))))

(deftest ^{:stratum 1} unavailable-pod-observation-does-not-match-test
  (with-redefs [shell/kubectl-rollout-status! (constantly (schema/success :stdout "ready"))
                shell/kubectl-get-pods! (constantly (schema/failure :parsed "unavailable"))]
    (is (false? (:provider/matched?
                 (provider/observe! (deployment-config)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} phase-failure-retains-rollback-evidence-test
  (let [[logger entries] (log/collecting-logger)]
    (with-redefs [flow/execute!
                  (constantly (rollout-failure))]
      (let [ctx (deploy/enter-deploy
                 {:execution/logger logger
                  :execution/input (select-keys (deployment-config)
                                                [:kustomize-dir :namespace
                                                 :context :app-label])})
          evidence-types (mapv :evidence/type (:execution/evidence ctx))]
        (is (= :failed (get-in ctx [:phase :status])))
        (is (= :rollout-failed (get-in ctx [:phase :result :status])))
        (is (= [:deploy/applied :deploy/rollout-failed]
               (mapv :log/event @entries)))
        (is (some #{:evidence/rollback-info} evidence-types))))))
