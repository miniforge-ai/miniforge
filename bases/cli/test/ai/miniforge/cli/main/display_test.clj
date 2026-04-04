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

(ns ai.miniforge.cli.main.display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.display :as sut]))

(deftest print-error-uses-message-catalog-test
  (testing "generic error output reads its prefix from the message catalog"
    (with-redefs [messages/t (fn [k params]
                               (case k
                                 :classified-error/error-prefix (str "CAT:" (:message params))
                                 (str "UNEXPECTED:" k)))]
      (let [output (with-out-str (sut/print-error "boom"))]
        (is (.contains output "CAT:boom"))))))
