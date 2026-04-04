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

(ns ai.miniforge.web-dashboard.server.websocket-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.web-dashboard.server.websocket :as sut]))

(deftest ws-event-envelope-test
  (testing "browser envelope preserves wrapped payload and adds string metadata"
    (let [wf-id (random-uuid)
          event {:event/type :workflow/phase-started
                 :workflow/id wf-id
                 :workflow/phase :verify}
          envelope (sut/ws-event-envelope event)]
      (is (= "event" (get envelope "type")))
      (is (= "workflow/phase-started" (get envelope "event-type")))
      (is (= (str wf-id) (get envelope "workflow-id")))
      (is (map? (get envelope "data"))))))

(deftest normalize-workflow-event-test
  (testing "workflow websocket ingestion re-keywordizes fields and restores UUID ids"
    (let [wf-id (random-uuid)
          normalized (sut/normalize-workflow-event
                      {:event/type "workflow/completed"
                       :workflow-id (str wf-id)
                       :workflow-spec {:name "Test"}
                       :status "success"
                       :phase "release"
                       :timestamp "2026-03-28T12:00:00Z"})]
      (is (= :workflow/completed (:event/type normalized)))
      (is (= wf-id (:workflow/id normalized)))
      (is (= {:name "Test"} (:workflow/spec normalized)))
      (is (= :success (:workflow/status normalized)))
      (is (= :release (:workflow/phase normalized)))
      (is (= "2026-03-28T12:00:00Z" (:event/timestamp normalized))))))
