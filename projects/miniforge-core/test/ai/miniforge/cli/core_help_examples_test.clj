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
(ns ai.miniforge.cli.core-help-examples-test
  "On the MiniForge Core classpath, every `workflow run :<id>` example in
   Core's help names a workflow whose pipeline Core can build. Loading is
   not enough: Core ships workflows (such as :simple) whose phases (:plan,
   :implement) only the software-factory product registers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} example-workflow-ids
  "The workflow ids in the `workflow run :<id>` help examples, without the
   :workflow-id placeholder."
  []
  (->> (app-config/help-examples)
       (keep #(second (re-find #"^workflow run :([a-z0-9-]+)" %)))
       (remove #{"workflow-id"})
       (map keyword)))

(defn- ^{:stratum 0} build-error
  "nil when `id`'s pipeline builds on this classpath, else the error."
  [id]
  (try
    (workflow/build-pipeline (setup/load-and-validate-workflow workflow/load-workflow id "latest"))
    nil
    (catch Exception e (ex-message e))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} core-help-workflow-examples-build-test
  (testing "the help profile is Core's"
    (is (= "miniforge-core" (app-config/binary-name))))
  (testing "each `workflow run :<id>` example builds its pipeline in Core"
    (doseq [id (example-workflow-ids)]
      (is (nil? (build-error id)) (str "Core help example " id)))))
