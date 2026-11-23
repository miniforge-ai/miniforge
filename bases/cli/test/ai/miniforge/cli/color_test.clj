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
(ns ai.miniforge.cli.color-test
  "CLI output carries ANSI color codes only on a terminal and only when
   NO_COLOR is not set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.color :as sut]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.observability :as observability]
   [ai.miniforge.cli.workflow-runner.display-ansi :as display-ansi]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} esc "\u001b[")

(defn- ^{:stratum 0} styled-outputs
  "Every CLI styling primitive applied to \"text\"."
  []
  [(display/style "text" :foreground :red)
   (display/style "text" :bold true)
   (config/style "text" :green)
   (display-ansi/colorize :yellow "text")
   (observability/colorize :cyan "text")])

(defn- ^{:stratum 0} with-terminal
  "Call `f` with stdout a terminal or not, and NO_COLOR set to `no-color`."
  [terminal? no-color f]
  (with-redefs [sut/stdout-terminal? (constantly terminal?)
                sut/env (fn [k] (when (= "NO_COLOR" k) no-color))]
    (f)))

(deftest ^{:stratum 0} no-color-value-test
  (is (sut/no-color-value? "1"))
  (is (sut/no-color-value? "false") "any non-empty value counts, per no-color.org")
  (is (not (sut/no-color-value? "")))
  (is (not (sut/no-color-value? nil))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} color-on-a-terminal-test
  (testing "a terminal without NO_COLOR gets color"
    (with-terminal true nil
      #(is (every? (fn [s] (str/includes? s esc)) (styled-outputs))))))

(deftest ^{:stratum 1} no-color-turns-color-off-test
  (testing "NO_COLOR on a terminal: plain text"
    (with-terminal true "1"
      #(is (= (repeat 5 "text") (styled-outputs))))))

(deftest ^{:stratum 1} not-a-terminal-turns-color-off-test
  (testing "stdout not a terminal (a pipe, a file): plain text"
    (with-terminal false nil
      #(is (= (repeat 5 "text") (styled-outputs))))))

(deftest ^{:stratum 1} error-line-is-plain-when-piped-test
  (testing "display/print-error writes no escape codes when not on a terminal"
    (with-terminal false nil
      #(let [err (java.io.StringWriter.)]
         (binding [*err* err] (display/print-error "boom"))
         (is (str/includes? (str err) "boom"))
         (is (not (str/includes? (str err) esc)))))))
