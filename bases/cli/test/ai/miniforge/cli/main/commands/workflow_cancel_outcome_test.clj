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
(ns ai.miniforge.cli.main.commands.workflow-cancel-outcome-test
  "`mf workflow cancel` reports what the run did with the request (#2377).
   Writing the request only queues it; the operator consumer of the
   process running that workflow applies it. These tests run that
   consumer for a registered run, with the run's events written to a temp
   events directory, and check what the command says and how it exits."
  (:require
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.commands.workflow-commands :as sut]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} cancel-with-live-run
  "Run `workflow cancel` for `target` while a run is registered with a
   consumer polling the events directory `dir`, as the run's process does. `control-state`
   is the run's; the run's lifecycle events go to `dir`. Returns
   {:out <stdout and stderr> :exit <exit code or nil>}."
  [dir run-id control-state target confirm-ms]
  (let [events-dir (fs/file dir)
        stream (es/create-event-stream
                {:config {:observability {:event-sinks [{:type :file :base-dir events-dir}]}}})
        consumer (operator/start-operator-consumer!
                  {:events-dir events-dir
                   :stream (es/create-event-stream {:sinks []})
                   :apply! operator/apply-intervention!
                   :accept? operator/live-intervention-target?
                   :stream-for operator/live-intervention-stream
                   :interval-ms 200})
        exit (atom nil)]
    (operator/register-live-runner! run-id {:control-state control-state :event-stream stream})
    (try
      (with-redefs [es/default-events-dir (constantly events-dir)
                    sut/cancel-confirm-ms confirm-ms
                    shared/exit! (fn [code] (reset! exit code))]
        (let [out (with-out-str
                    (binding [*err* *out*]
                      (sut/workflow-cancel-cmd {:id (str target)})))]
          {:out out :exit @exit}))
      (finally
        (operator/stop-operator-consumer! consumer)
        (operator/deregister-live-runner! run-id)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-cancel-the-run-applies-is-reported-applied-test
  (testing "the run's consumer applies the cancel: the command says so and exits 0"
    (fs/with-temp-dir [dir {:prefix "cancel-outcome-"}]
      (let [run-id (random-uuid)
            control-state (es/create-control-state)
            {:keys [out exit]} (cancel-with-live-run dir run-id control-state run-id 10000)]
        (is (true? (:stopped @control-state)) "the run's stop flag is set")
        (is (str/includes? out "Cancel applied"))
        (is (nil? exit))))))

(deftest ^{:stratum 1} a-cancel-no-run-picks-up-is-not-reported-as-sent-test
  (testing "an id no running workflow owns: the outcome is unknown, the command says so and exits 1"
    (fs/with-temp-dir [dir {:prefix "cancel-outcome-"}]
      (let [control-state (es/create-control-state)
            {:keys [out exit]} (cancel-with-live-run dir (random-uuid) control-state
                                                     (random-uuid) 1500)]
        (is (false? (:stopped @control-state)))
        (is (str/includes? out "Cancel outcome unknown"))
        (is (str/includes? out "may still be queued"))
        (is (not (str/includes? out "Cancel applied")))
        (is (not (str/includes? out "Cancel signal sent")))
        (is (= 1 exit))))))

(deftest ^{:stratum 1} a-cancel-the-run-fails-to-apply-is-reported-failed-test
  (testing "applying the cancel throws after the stop flag is set: the failure and
            its reason are reported without claiming the cancel had no effect, exit 1"
    (fs/with-temp-dir [dir {:prefix "cancel-outcome-"}]
      (let [run-id (random-uuid)
            control-state (doto (es/create-control-state)
                            (add-watch ::refuse (fn [& _] (throw (ex-info "refused" {})))))
            {:keys [out exit]} (cancel-with-live-run dir run-id control-state run-id 10000)]
        (is (true? (:stopped @control-state)) "the stop flag was set before the watch threw")
        (is (str/includes? out "could not apply or verify"))
        (is (str/includes? out "may already be set"))
        (is (not (str/includes? out "was not applied")))
        (is (not (str/includes? out "Cancel applied")))
        (is (= 1 exit))))))
