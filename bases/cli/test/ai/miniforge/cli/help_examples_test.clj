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
(ns ai.miniforge.cli.help-examples-test
  "`mf help` examples name workflows that exist and installs that exist,
   and `mf workflow list` shows each real workflow once."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.workflow-runner.listing :as listing]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} example-workflow-ids
  "The workflow ids in the `workflow run :<id>` help examples."
  []
  (->> (app-config/help-examples)
       (keep #(second (re-find #"^workflow run :([a-z0-9-]+)" %)))
       (remove #{"workflow-id"})
       (map keyword)))

(deftest ^{:stratum 0} listed-workflows-test
  (let [wf (fn [id v & {:as extra}] (merge {:workflow/id id :workflow/version v} extra))]
    (testing "one entry per id and version, test-only workflows dropped, sorted"
      (is (= [(wf :a "1.0.0") (wf :a "2.0.0") (wf :b "1.0.0")]
             (listing/listed-workflows [(wf :b "1.0.0") (wf :a "2.0.0") (wf :a "1.0.0")
                                        (wf :a "2.0.0") (wf :t "1.0.0" :workflow/metadata {:test-only? true})]))))))

(deftest ^{:stratum 0} help-names-no-unpublished-package-test
  (testing "mf help does not tell the user to brew install a formula that is not published"
    (is (not (str/includes? (with-out-str (main/help-cmd {})) "brew install")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} workflow-run-examples-name-real-workflows-test
  (testing "each `workflow run :<id>` example loads a workflow with that id"
    (is (seq (example-workflow-ids)))
    (doseq [id (example-workflow-ids)]
      (let [loaded (setup/load-and-validate-workflow workflow/load-workflow id "latest")]
        (is (= id (:workflow/id loaded)) (str "help example names " id))))))

(deftest ^{:stratum 1} workflow-list-shows-each-real-workflow-once-test
  (let [listed (listing/listed-workflows (workflow/list-workflows))
        ids    (map :workflow/id listed)]
    (testing "no workflow is listed twice"
      (is (= (count listed) (count (distinct (map (juxt :workflow/id :workflow/version) listed))))))
    (testing "test workflows are not listed"
      (is (not-any? #{:minimal-test-v1 :simple-test-v1} ids)))
    (testing "the help examples' workflows are listed"
      (is (every? (set ids) (example-workflow-ids))))))
