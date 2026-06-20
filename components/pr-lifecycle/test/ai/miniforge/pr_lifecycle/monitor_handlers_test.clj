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

(ns ai.miniforge.pr-lifecycle.monitor-handlers-test
  (:require
   [ai.miniforge.pr-lifecycle.monitor-handlers :as handlers]
   [ai.miniforge.pr-lifecycle.monitor-state :as state]
   [clojure.test :refer [deftest is testing]]))

(deftest decision-action-maps-categories-test
  (testing "each comment category maps to the PR-monitor's action verb"
    (is (= :fix     (#'handlers/decision-action :change-request)))
    (is (= :answer  (#'handlers/decision-action :question)))
    (is (= :approve (#'handlers/decision-action :approval)))
    (is (= :skip    (#'handlers/decision-action :bot-comment)))
    (is (= :skip    (#'handlers/decision-action :noise)))
    (is (= :skip    (#'handlers/decision-action :anything-unrecognised)))))

(deftest route-comment-records-typed-decision-test
  (testing "route-comment emits one :pr-monitor/decision-recorded act with provenance"
    (let [emitted (atom [])]
      (with-redefs [state/emit! (fn [_ ev] (swap! emitted conj ev))
                    handlers/handle-question (fn [_ _ _] {:success? true :type :answered})]
        (handlers/route-comment (atom {:config {}})
                                {:pr/number 7}
                                {:category :question :confidence :high
                                 :comment {:comment/id 99}}))
      (let [d (first @emitted)]
        (is (= :pr-monitor/decision-recorded (:event/type d)))
        (is (= :answer (:decision/action d)))
        (is (= :question (:decision/category d)))
        (is (= :high (:decision/confidence d)))
        (is (= 99 (:comment/id d)))
        (is (= 7 (:pr/id d)))))))
