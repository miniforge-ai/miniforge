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

(ns ai.miniforge.tui-views.command-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.effect :as effect]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update.command :as command]))

(deftest quit-command-test
  (testing ":q sets quit flag"
    (let [m (command/execute-command (model/init-model) ":q")]
      (is (:quit? m))))

  (testing ":quit sets quit flag"
    (let [m (command/execute-command (model/init-model) ":quit")]
      (is (:quit? m)))))

(deftest view-command-test
  (testing ":view evidence switches to evidence"
    (let [m (command/execute-command (model/init-model) ":view evidence")]
      (is (= :evidence (:view m)))
      (is (= 0 (:selected-idx m)))))

  (testing ":view pr-fleet switches to pr-fleet"
    (let [m (command/execute-command (model/init-model) ":view pr-fleet")]
      (is (= :pr-fleet (:view m)))))

  (testing ":view with no arg lists views"
    (let [m (command/execute-command (model/init-model) ":view")]
      (is (some? (:flash-message m)))))

  (testing ":view unknown sets error flash"
    (let [m (command/execute-command (model/init-model) ":view nonexistent")]
      (is (some? (:flash-message m))))))

(deftest refresh-command-test
  (testing ":refresh sets flash and timestamp"
    (let [m (command/execute-command (model/init-model) ":refresh")]
      (is (= "Refreshed" (:flash-message m)))
      (is (some? (:last-updated m))))))

(deftest help-command-test
  (testing ":help shows help overlay"
    (let [m (command/execute-command (model/init-model) ":help")]
      (is (:help-visible? m)))))

(deftest theme-command-test
  (testing ":theme dark switches theme"
    (let [m (command/execute-command (model/init-model) ":theme dark")]
      (is (= :dark (:theme m)))))

  (testing ":theme with no arg shows current"
    (let [m (command/execute-command (model/init-model) ":theme")]
      (is (some? (:flash-message m))))))

(deftest unknown-command-test
  (testing "Unknown command sets error flash"
    (let [m (command/execute-command (model/init-model) ":foobar")]
      (is (some? (:flash-message m))))))

(deftest command-mode-integration-test
  (testing "Full command mode flow: colon, type, enter"
    (let [m (model/init-model)
          m (assoc m :mode :command :command-buf ":q")
          result (command/execute-command m (:command-buf m))]
      (is (:quit? result)))))

;------------------------------------------------------------------------------ Layer 1
;; Control action effect tests

(deftest control-action-effect-test
  (testing "control-action creates correct map shape"
    (let [eff (effect/control-action :pause (java.util.UUID/randomUUID))]
      (is (= :control-action (:type eff)))
      (is (= :pause (:action eff)))
      (is (some? (:workflow-id eff)))))

  (testing "control-action for resume"
    (let [wf-id (java.util.UUID/randomUUID)
          eff (effect/control-action :resume wf-id)]
      (is (= :control-action (:type eff)))
      (is (= :resume (:action eff)))
      (is (= wf-id (:workflow-id eff)))))

  (testing "control-action for cancel"
    (let [eff (effect/control-action :cancel (java.util.UUID/randomUUID))]
      (is (= :cancel (:action eff))))))

;------------------------------------------------------------------------------ Layer 2
;; Pause/resume command handler tests

(deftest pause-command-test
  (testing ":pause with single workflow selected produces control-action"
    (let [wf-id (java.util.UUID/randomUUID)
          m (-> (model/init-model)
                (assoc :workflows [{:id wf-id :name "test" :status :running}])
                (assoc :selected-ids #{wf-id}))
          result (command/execute-command m ":pause")]
      (is (= :control-action (get-in result [:side-effect :type])))
      (is (= :pause (get-in result [:side-effect :action])))
      (is (= wf-id (get-in result [:side-effect :workflow-id])))
      (is (= "Pausing workflow..." (:flash-message result)))))

  (testing ":pause with no selection shows error"
    (let [m (model/init-model)
          result (command/execute-command m ":pause")]
      (is (some? (:flash-message result)))
      (is (nil? (:side-effect result))))))

(deftest resume-command-test
  (testing ":resume with single workflow selected produces control-action"
    (let [wf-id (java.util.UUID/randomUUID)
          m (-> (model/init-model)
                (assoc :workflows [{:id wf-id :name "test" :status :paused}])
                (assoc :selected-ids #{wf-id}))
          result (command/execute-command m ":resume")]
      (is (= :control-action (get-in result [:side-effect :type])))
      (is (= :resume (get-in result [:side-effect :action])))
      (is (= wf-id (get-in result [:side-effect :workflow-id])))
      (is (= "Resuming workflow..." (:flash-message result)))))

  (testing ":resume with no selection shows error"
    (let [m (model/init-model)
          result (command/execute-command m ":resume")]
      (is (some? (:flash-message result)))
      (is (nil? (:side-effect result))))))
