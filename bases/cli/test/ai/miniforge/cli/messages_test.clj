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

(ns ai.miniforge.cli.messages-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]))

(deftest message-catalog-renders-placeholders-test
  (let [english-catalog (messages/catalog "en")
        usage-template (:help/usage english-catalog)
        help-template (:main/run-help english-catalog)]
    (testing "messages are loaded from resources and interpolate placeholders"
      (is (= (str/replace usage-template "{binary}" "miniforge")
             (messages/t :help/usage {:binary "miniforge"})))
      (is (= (str/replace help-template "{command}" "miniforge help")
             (messages/t :main/run-help {:command "miniforge help"}))))))

(deftest message-catalog-falls-back-to-english-test
  (testing "unknown locales fall back to the English catalog"
    (let [english-usage (messages/t :help/usage {:binary "moteur"})]
      (with-redefs [messages/active-locale (constantly :fr)]
        (is (= english-usage
               (messages/t :help/usage {:binary "moteur"})))))))
