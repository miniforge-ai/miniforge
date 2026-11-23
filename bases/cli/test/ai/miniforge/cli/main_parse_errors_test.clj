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
(ns ai.miniforge.cli.main-parse-errors-test
  "Command-line errors seen through `-main`: a bad, unknown or missing
   option prints that command's usage on stderr and exits 2, an unknown
   command exits 2, and a command group named alone lists its own
   subcommands instead of the global help."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.commands.resume :as cmd-resume]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-main
  "Run `-main` with `args` and exit! stubbed. Returns the exit code (nil
   when none), stdout, stderr, and, when something was thrown out of
   -main, its message."
  [& args]
  (let [code (atom nil)
        thrown (atom nil)
        err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (with-redefs [shared/exit! (fn [c] (reset! code c) nil)]
                  (try+
                    (apply main/-main args)
                    (catch Object _
                      (reset! thrown (or (:message &throw-context) "thrown, no message")))))))]
    {:exit @code :out out :err (str err) :thrown @thrown}))

(defn- ^{:stratum 0} usage-for
  "The usage line `-main` prints for a command."
  [& words]
  (str "Usage: " (apply app-config/command-string words)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} bad-option-values-exit-2-with-usage-test
  (testing "a value that does not coerce names the option and prints the command's usage"
    (let [{:keys [exit out err thrown]} (run-main "web" "--port" "abc")]
      (is (nil? thrown))
      (is (= 2 exit))
      (is (.contains err "--port"))
      (is (.contains err (usage-for "web" "[options]")))
      (is (= "" out) "nothing on stdout")))
  (testing "the short alias of a coerced option too"
    (let [{:keys [exit err]} (run-main "events" "show" "-g" "x")]
      (is (= 2 exit))
      (is (.contains err "--gap-threshold"))
      (is (.contains err (usage-for "events" "show" "<workflow-id>" "[options]"))))))

(deftest ^{:stratum 1} missing-required-option-exits-2-test
  (let [{:keys [exit err thrown]} (run-main "context-server")]
    (is (nil? thrown))
    (is (= 2 exit))
    (is (.contains err "--artifact-dir"))))

(deftest ^{:stratum 1} unknown-option-is-refused-test
  (testing "a misspelt option on a restricted command is an error, not silently ignored"
    (let [{:keys [exit err]} (run-main "run" "x.edn" "--backnd" "claude")]
      (is (= 2 exit))
      (is (.contains err "--backnd"))
      (is (.contains err (usage-for "run" "<spec>" "[options]")))))
  (testing "the positional argument and declared options still pass"
    (let [{:keys [exit out err]} (run-main "run" "/nonexistent/x.edn" "-b" "claude")]
      (is (= 1 exit) "reaches run, which reports the missing file")
      (is (.contains (str out err) "/nonexistent/x.edn")))))

(deftest ^{:stratum 1} help-on-a-restricted-command-prints-its-usage-test
  (let [{:keys [exit out]} (run-main "run" "--help")]
    (is (nil? exit))
    (is (.contains out (usage-for "run" "<spec>" "[options]")))))

(deftest ^{:stratum 1} legacy-resume-alias-reaches-resume-test
  (let [resumed (atom nil)]
    (with-redefs [cmd-resume/resume-workflow (fn [workflow-id _opts]
                                               (reset! resumed workflow-id)
                                               {:execution/status :completed})]
      (run-main "resume" "-r" "wf-1"))
    (is (= "wf-1" @resumed) "-r is the documented short form of --resume")))

(deftest ^{:stratum 1} run-accepts-the-options-it-forwards-test
  (testing "`--quiet` reaches the plan executor"
    (let [plan (str (fs/create-temp-file {:suffix ".edn"}))
          seen (atom nil)]
      (spit plan (pr-str {:plan/id "p" :plan/tasks []}))
      (with-redefs [plan-executor/execute-plan (fn [_parsed opts]
                                                 (reset! seen opts)
                                                 {:success? true})]
        (let [{:keys [exit]} (run-main "run" plan "--quiet")]
          (is (nil? exit))
          (is (true? (:quiet @seen)))))))
  (testing "the resume options reach resume-workflow, `--from-phase` as a keyword"
    (let [run-id (str (random-uuid))
          correlation-id (str (random-uuid))
          seen (atom nil)]
      (with-redefs [cmd-resume/resume-workflow (fn [workflow-id opts]
                                                 (reset! seen [workflow-id opts])
                                                 {:execution/status :completed})]
        (let [{:keys [exit]} (run-main "run" "--resume" "wf-1" "--run-id" run-id
                                       "--correlation-id" correlation-id
                                       "--from-phase" "plan" "-q")]
          (is (nil? exit))
          (is (= "wf-1" (first @seen)))
          (is (= {:run-id run-id :correlation-id correlation-id :from-phase :plan :quiet true}
                 (select-keys (second @seen) [:run-id :correlation-id :from-phase :quiet]))))))))

(deftest ^{:stratum 1} command-groups-list-their-own-subcommands-test
  (testing "a group with no handler of its own"
    (let [{:keys [exit out thrown]} (run-main "fleet")]
      (is (nil? thrown))
      (is (nil? exit))
      (is (.contains out (app-config/command-string "fleet" "watch")))))
  (testing "a group that used to print the global help"
    (let [{:keys [exit out]} (run-main "config")]
      (is (nil? exit))
      (is (.contains out (app-config/command-string "config" "backend"))))))

(deftest ^{:stratum 1} unknown-commands-exit-2-test
  (testing "at the top level, where the global help used to swallow the word"
    (let [{:keys [exit err]} (run-main "nosuch")]
      (is (= 2 exit))
      (is (.contains err "nosuch"))))
  (testing "after a group whose handler prints its help"
    (let [{:keys [exit err]} (run-main "workflow" "nosuch")]
      (is (= 2 exit))
      (is (.contains err "workflow nosuch")))))
