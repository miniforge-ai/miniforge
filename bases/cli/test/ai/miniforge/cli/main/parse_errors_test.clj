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
(ns ai.miniforge.cli.main.parse-errors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.parse-errors :as sut]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} help-unless-unknown-test
  (let [code (atom nil)
        help (sut/help-unless-unknown (constantly :help))]
    (with-redefs [shared/exit! (fn [c] (reset! code c) nil)]
      (binding [*err* (java.io.StringWriter.)]
        (testing "no word after the command: its help"
          (is (= :help (help {:dispatch ["workflow"] :args nil})))
          (is (nil? @code)))
        (testing "a word after it: an unknown command"
          (help {:dispatch ["workflow"] :args ["nosuch"]})
          (is (= 2 @code)))))))

(deftest ^{:stratum 0} unknown-subcommand-exits-2-test
  (let [code (atom nil)
        err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (with-redefs [shared/exit! (fn [c] (reset! code c) nil)]
                  (main/-main "fleet" "nosuch"))))]
    (is (= 2 @code))
    (is (.contains (str err) "fleet nosuch"))
    (is (.contains (str err) "fleet watch") "lists the group's commands")
    (is (= "" out))))

(deftest ^{:stratum 0} option-flag-test
  (is (= "--port" (sut/option-flag :port)))
  (is (= "-p" (sut/option-flag :p))))

(deftest ^{:stratum 0} error-message-test
  (is (.contains (sut/error-message {:cause :restrict :option :backnd}) "--backnd"))
  (is (.contains (sut/error-message {:cause :require :option :artifact-dir}) "--artifact-dir"))
  (is (.contains (sut/error-message {:cause :coerce :option :port :msg "cannot coerce"}) "cannot coerce"))
  (is (= "raw" (sut/error-message {:cause :other :msg "raw"}))))

(deftest ^{:stratum 0} guard-entry-test
  (testing "a parse error carries the entry's command and positional keys"
    (let [error-fn (:error-fn (sut/guard-entry {:cmds ["web"] :fn identity :args->opts [:x]}))
          data (try+ (error-fn {:msg "boom" :cause :coerce :option :port})
                     (catch map? thrown thrown))]
      (is (= ["web"] (:dispatch data)))
      (is (= [:x] (:positional data))))))
