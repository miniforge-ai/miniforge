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
(ns ai.miniforge.cli.workflow-runner.execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-recording-closes
  "Run `execute-with-events` with `overrides` merged over a minimal input,
   recording every store handed to `artifact/close-store`. Returns
   {:result ... :closed [...]} on normal return, {:thrown ... :closed [...]}
   when the call throws."
  [overrides]
  (let [closed (atom [])
        base {:run-pipeline (fn [_workflow _input _callbacks] {:success? true})
              :workflow {}
              :workflow-input {}
              :context {}
              :artifact-store ::sentinel-store
              :event-stream nil
              :workflow-id "wf-execution-test"
              :sandbox-cleanup nil
              :opts {:quiet true}}
        outcome (with-redefs-fn {#'artifact/close-store (fn [store]
                                                          (swap! closed conj store)
                                                          nil)
                                 #'lifecycle/publish-completion-event (fn [_stream _id _result] nil)
                                 #'lifecycle/publish-failure-event! (fn [_stream _id _type _msg] nil)
                                 #'display/print-result (fn [_result _opts] nil)}
                  (fn []
                    (try+
                      {:result (execution/execute-with-events (merge base overrides))}
                      (catch Object thrown
                        {:thrown thrown}))))]
    (assoc outcome :closed @closed)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-sandbox-failure
  "Drive `execute-with-events` down the sandbox-setup-failure branch.
   The pipeline stub throws — that branch must never reach it."
  []
  (execute-recording-closes
   {:context {:sandbox-error {:error "container boot failed"}}
    :run-pipeline (fn [_workflow _input _callbacks]
                    (throw (ex-info "pipeline must not run after sandbox failure" {})))}))

(deftest ^{:stratum 1} execute-with-events-closes-store-once-on-success-test
  (testing "completion path releases the artifact store exactly once"
    (let [{:keys [result closed]} (execute-recording-closes {})]
      (is (true? (:success? result)))
      (is (= [::sentinel-store] closed)))))

(deftest ^{:stratum 1} execute-with-events-closes-store-when-pipeline-throws-test
  (testing "a pipeline exception still releases the artifact store before rethrowing"
    (let [{:keys [result thrown closed]} (execute-recording-closes
                                          {:run-pipeline (fn [_workflow _input _callbacks]
                                                           (throw (ex-info "pipeline exploded" {})))})]
      (is (nil? result))
      (is (some? thrown))
      (is (= [::sentinel-store] closed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} execute-with-events-closes-store-on-sandbox-failure-test
  (testing "sandbox-setup failure reports the error and releases the artifact store"
    (let [{:keys [result thrown closed]} (run-sandbox-failure)]
      (is (nil? thrown))
      (is (false? (:success? result)))
      (is (= :sandbox-setup-failed (get-in result [:errors 0 :type])))
      (is (= [::sentinel-store] closed)))))
