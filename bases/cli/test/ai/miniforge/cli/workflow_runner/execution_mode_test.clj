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
(ns ai.miniforge.cli.workflow-runner.execution-mode-test
  "[:execution :require-governed?] makes governed the default and refuses a
   run that would execute locally, before workflow setup; `mf pr respond`,
   which cannot run governed yet, refuses a governed request before it
   checks anything out."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.commands.pr :as pr]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.workflow-runner.execution-mode :as sut]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.setup :as setup]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private governed-only
  "A user config that requires governed execution."
  {:execution {:require-governed? true}})

(defn- ^{:stratum 0} refusal
  "The anomaly `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- ^{:stratum 0} respond
  "Run `pr respond` with `opts` under user config `cfg`. Returns
   {:exit code-or-nil :checkouts n}; `shared/exit!` is stubbed to stop."
  [cfg opts]
  (let [checkouts (atom 0)
        exit      (atom nil)]
    (with-redefs [config/load-config (constantly cfg)
                  pr/checkout-pr!    (fn [_] (swap! checkouts inc) nil)
                  shared/exit!       (fn [code] (reset! exit code) (throw (ex-info "exit" {::exit code})))]
      (try (with-out-str (pr/pr-respond-cmd (merge {:url "https://github.com/o/r/pull/7"} opts)))
           (catch clojure.lang.ExceptionInfo _ nil)))
    {:exit @exit :checkouts @checkouts}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} resolve-mode-test
  (testing "without the key, runs stay local unless asked for governed"
    (is (= {:execution-mode :local} (sut/resolve-mode {} {})))
    (is (= {:execution-mode :governed} (sut/resolve-mode {:execution-mode :governed} {})))
    (is (= {:execution-mode :governed} (sut/resolve-mode {:execution-mode "governed"} {}))))
  (testing "with the key, governed is the default and anything else is refused"
    (is (= {:execution-mode :governed} (sut/resolve-mode {} governed-only)))
    (is (= {:execution-mode :governed} (sut/resolve-mode {:execution-mode "governed"} governed-only)))
    (is (= {:refused :local} (sut/resolve-mode {:execution-mode :local} governed-only))))
  (testing "a value outside local and governed is unknown, not a mode"
    (is (= {:unknown :docker} (sut/resolve-mode {:execution-mode :docker} {})))
    (is (= {:unknown 7} (sut/resolve-mode {:execution-mode 7} governed-only)))))

(deftest ^{:stratum 1} runs-refuse-local-before-setup-test
  (let [setups (atom 0)]
    (with-redefs [config/load-config                (constantly governed-only)
                  gc-hooks/run-gc-pass-best-effort! (fn [& _] nil)
                  setup/resolve-workflow-interface  (fn [] (swap! setups inc) {})]
      (testing "a spec run asked to run locally is refused"
        (is (= :anomalies/forbidden
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow-from-spec!
                           {:spec/title "t"} {:execution-mode :local :quiet true}))))))
      (testing "a named workflow, which always runs locally, is refused"
        (is (= :anomalies/forbidden
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow! :canonical-sdlc {:quiet true :output :pretty}))))))
      (testing "an unknown mode is refused as incorrect"
        (is (= :anomalies/incorrect
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow-from-spec!
                           {:spec/title "t"} {:execution-mode :docker :quiet true}))))))
      (is (zero? @setups) "refused before resolving the workflow"))))

(deftest ^{:stratum 1} pr-respond-refuses-governed-before-checkout-test
  (testing "--execution-mode governed exits 1 without checking the PR out"
    (is (= {:exit 1 :checkouts 0} (respond {} {:execution-mode :governed}))))
  (testing "a config that requires governed execution does the same"
    (is (= {:exit 1 :checkouts 0} (respond governed-only {}))))
  (testing "a local run goes on to check the PR out"
    (is (= 1 (:checkouts (respond {} {:execution-mode :local}))))))
