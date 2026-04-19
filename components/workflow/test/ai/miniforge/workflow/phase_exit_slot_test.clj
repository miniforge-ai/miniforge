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

(ns ai.miniforge.workflow.phase-exit-slot-test
  "Tests for the :exit interceptor slot added to execute-phase-lifecycle.
   :exit runs after :leave, before transition. Phase curators live here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.execution :as exec]))

(defn- test-interceptor [& {:keys [enter leave exit error]}]
  (cond-> {:config {:phase :plan}}
    enter (assoc :enter enter)
    leave (assoc :leave leave)
    exit  (assoc :exit exit)
    error (assoc :error error)))

(deftest exit-slot-runs-after-leave
  (testing "exit receives the ctx produced by leave"
    (let [trace (atom [])
          interceptor (test-interceptor
                        :enter (fn [ctx] (swap! trace conj :enter) ctx)
                        :leave (fn [ctx] (swap! trace conj :leave)
                                 (assoc-in ctx [:phase :via-leave] true))
                        :exit  (fn [ctx]
                                 (swap! trace conj :exit)
                                 (is (true? (get-in ctx [:phase :via-leave]))
                                     "exit sees leave's result")
                                 ctx))]
      (exec/execute-phase-lifecycle interceptor {})
      (is (= [:enter :leave :exit] @trace)))))

(deftest missing-exit-is-no-op
  (testing "interceptors without :exit still work (backward compat)"
    (let [interceptor (test-interceptor
                        :enter identity
                        :leave (fn [ctx] (assoc-in ctx [:phase :via-leave] true)))
          [ctx _] (exec/execute-phase-lifecycle interceptor {})]
      (is (true? (get-in ctx [:phase :via-leave]))))))

(deftest exit-can-reject-phase
  (testing "exit can set :phase/status :failed + :phase/error as a curator rejection"
    (let [reject-err {:message "malformed" :curator :plan :code :curator/test}
          interceptor (test-interceptor
                        :enter identity
                        :leave (fn [ctx] (assoc-in ctx [:phase :status] :completed))
                        :exit  (fn [ctx]
                                 (-> ctx
                                     (assoc-in [:phase :status] :failed)
                                     (assoc-in [:phase :error] reject-err))))
          [_ phase-result] (exec/execute-phase-lifecycle interceptor {})]
      (is (= :failed (:status phase-result)))
      (is (= reject-err (:error phase-result))))))

(deftest exit-exception-recorded-as-exit-error
  (testing "throwing in :exit records :type :exit-error without killing the runtime"
    (let [interceptor (test-interceptor
                        :enter identity
                        :leave identity
                        :exit  (fn [_] (throw (ex-info "curator blew up" {}))))
          [ctx _] (exec/execute-phase-lifecycle interceptor {:execution/response-chain {}
                                                             :execution/errors []})]
      (is (some #(= :exit-error (:type %)) (:execution/errors ctx))
          "exit-error should be recorded on :execution/errors"))))

(deftest exit-runs-even-when-leave-is-missing
  (testing ":exit does not require :leave"
    (let [trace (atom [])
          interceptor (test-interceptor
                        :enter identity
                        :exit  (fn [ctx] (swap! trace conj :exit) ctx))]
      (exec/execute-phase-lifecycle interceptor {})
      (is (= [:exit] @trace)))))
