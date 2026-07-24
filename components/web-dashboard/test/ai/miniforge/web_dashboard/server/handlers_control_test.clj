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

(ns ai.miniforge.web-dashboard.server.handlers-control-test
  "The console's run controls now write governed intervention requests
   (Phase D D-4). These tests pin the command → intervention-verb
   mapping, the rejection of anything outside it, and that a failed
   write never reports success."
  (:require
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.web-dashboard.server.handlers :as sut]
   [ai.miniforge.web-dashboard.state.core :as state-core]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- fresh-state [] (state-core/create-state {}))

(defn- command-body [command] (json/generate-string {:command command}))

(defn- response-body [response] (json/parse-string (:body response) true))

;------------------------------------------------------------------------------ Layer 1
;; Command vocabulary

(deftest console-controls-map-to-intervention-verbs
  (testing "pause/resume/cancel are the three verbs the console offers"
    (is (= {"pause" :pause "resume" :resume "cancel" :cancel}
           sut/control-intervention-by-command))
    (testing "the legacy `stop` command name is gone with the .edn poller"
      (is (not (contains? sut/control-intervention-by-command "stop"))))))

(deftest each-command-writes-its-intervention
  (doseq [[command expected-type] sut/control-intervention-by-command]
    (testing command
      (let [requests (atom [])
            workflow-id (str (random-uuid))]
        (with-redefs [event-stream/request-intervention!
                      (fn [request]
                        (swap! requests conj request)
                        (assoc request :intervention/id (random-uuid)))]
          (let [response (sut/handle-api-workflow-command
                          (fresh-state) workflow-id (command-body command))
                request (first @requests)]
            (is (= 200 (:status response)))
            (is (= "requested" (:status (response-body response))))
            (is (= expected-type (:intervention/type request)))
            (is (= :workflow (:intervention/target-type request)))
            (is (= workflow-id (:intervention/target-id request)))
            (is (= :dashboard (:intervention/request-source request)))
            (is (= "dashboard" (:intervention/requested-by request)))))))))

(deftest unknown-commands-are-rejected-at-the-boundary
  (testing "an unrecognized command 4xxs instead of being written and dropped"
    (let [requests (atom [])]
      (with-redefs [event-stream/request-intervention!
                    (fn [request] (swap! requests conj request) request)]
        (let [response (sut/handle-api-workflow-command
                        (fresh-state) (str (random-uuid)) (command-body "stop"))]
          (is (<= 400 (:status response) 499))
          (is (empty? @requests)))))))

(deftest write-failure-is-reported-not-swallowed
  (testing "a throwing write yields an error response, never `requested`"
    (with-redefs [event-stream/request-intervention!
                  (fn [_request] (throw (ex-info "operator dir unwritable" {})))]
      (let [response (sut/handle-api-workflow-command
                      (fresh-state) (str (random-uuid)) (command-body "pause"))]
        (is (<= 500 (:status response) 599))
        (is (not= "requested" (:status (response-body response))))))))
