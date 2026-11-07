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
(ns ai.miniforge.agent-environment.work-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.fake.world :as world]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} report-path "/work/report.txt")

(def ^{:stratum 0} report-text "all checks passed")

(defn- ^{:stratum 0} leased-world
  []
  (let [w (fx/world (env/fake-provider) :isolation/shared-kernel)]
    (assoc w :lease (:environment/lease (fx/acquire w (:a w) (fx/request w (:a w)))))))

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(def ^{:stratum 0} fx-ok {:exec/exit 0 :exec/stdout "" :exec/stderr ""})

(def ^{:stratum 0} scripted-exit
  "A nonzero exit: a command that ran and failed is still a command that ran."
  3)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-fake-writes-and-exec-registration-touch-only-their-own-namespace
  (let [gone {:target/namespace "mf-env-gone" :target/uid "uid-1"}
        replaced {"mf-env-gone" {:uid "uid-2"}}]
    (is (= {} (world/with-file {} gone report-path "x")))
    (is (= {} (world/started {} gone ["true"])))
    (is (= replaced (world/with-file replaced gone report-path "x")))
    (is (= replaced (world/started replaced gone ["true"])))))

(defn- ^{:stratum 1} blocking-exec
  "An exec that signals `entered`, then waits for `release` before returning."
  [entered release]
  (fn [_command]
    (deliver entered true)
    @release
    fx-ok))

(defn- ^{:stratum 1} stdin-echo
  [command]
  {:exec/exit scripted-exit :exec/stdout (:exec/stdin command) :exec/stderr ""})

(deftest ^{:stratum 1} test-exec-and-files-run-inside-the-leased-environment
  (let [{:keys [c a lease] :as w} (leased-world)]
    (is (= {:exec/exit 0 :exec/stdout "echo hi" :exec/stderr ""}
           (env/exec! c a lease {:exec/argv ["echo" "hi"] :exec/timeout-ms fx/exec-timeout-ms})))
    (is (= {:file/path report-path} (env/write-file! c a lease report-path report-text)))
    (is (= report-text (env/read-file c a lease report-path)))
    (is (= [:outcome/applied :outcome/applied :outcome/applied]
           (fx/outcomes w :environment/exec :environment/copy)))))

(deftest ^{:stratum 1} test-artifacts-persist-through-the-controller-store
  (let [{:keys [c a lease]} (leased-world)]
    (env/write-file! c a lease report-path report-text)
    (testing "given a written file → the store receives it and its reference returns"
      (is (= [{:artifact/path report-path
               :artifact/location (str (:lease/environment-uid lease) ":" report-path)}]
             (env/persist-artifacts! c a lease [report-path]))))
    (testing "given a missing file → the provider's anomaly returns, not a partial list"
      (is (= :not-found (:anomaly/type (env/persist-artifacts! c a lease [report-path "/work/missing.txt"])))))))

(deftest ^{:stratum 1} test-partial-persist-is-receipted-as-partial
  (let [{:keys [c a lease] :as w} (leased-world)]
    (env/write-file! c a lease report-path report-text)
    (env/persist-artifacts! c a lease [report-path "/work/missing.txt"])
    (is (= [:outcome/partial] (fx/outcomes w :environment/persist-artifacts)))))

(deftest ^{:stratum 1} test-cancel-stops-new-work-but-keeps-audit-and-cleanup
  (let [{:keys [c a lease]} (leased-world)]
    (env/write-file! c a lease report-path report-text)
    (is (= {:call/outcome :outcome/applied} (env/cancel! c a lease)))
    (is (= :anomalies.agent-environment/environment-cancelled
           (reason (env/exec! c a lease {:exec/argv ["true"] :exec/timeout-ms fx/exec-timeout-ms}))))
    (is (vector? (env/persist-artifacts! c a lease [report-path])))
    (is (= {:call/outcome :outcome/applied} (env/release! c a lease)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-scripted-exec-receives-the-command-and-its-stdin
  (let [w (fx/world (env/fake-provider {:fake/exec-fn stdin-echo}) :isolation/shared-kernel)
        env-value (fx/acquire w (:a w) (fx/request w (:a w)))
        command {:exec/argv ["cat"] :exec/stdin "prompt" :exec/timeout-ms fx/exec-timeout-ms}]
    (is (= {:exec/exit scripted-exit :exec/stdout "prompt" :exec/stderr ""}
           (env/exec! (:c w) (:a w) (:environment/lease env-value) command)))
    (is (= [command] (env/fake-commands (:controller/provider (:c w)) (:environment/namespace env-value))))
    (is (= [:outcome/applied] (fx/outcomes w :environment/exec)))))

(deftest ^{:stratum 2} test-cancel-while-an-exec-runs-is-unconfirmed
  (let [entered (promise)
        release (promise)
        w (fx/world (env/fake-provider {:fake/exec-fn (blocking-exec entered release)}) :isolation/shared-kernel)
        lease (:environment/lease (fx/acquire w (:a w) (fx/request w (:a w))))
        running (future (env/exec! (:c w) (:a w) lease {:exec/argv ["sleep"] :exec/timeout-ms fx/exec-timeout-ms}))]
    @entered
    (is (= {:call/outcome :outcome/uncertain} (env/cancel! (:c w) (:a w) lease)))
    (deliver release true)
    (is (= fx-ok @running))))

(deftest ^{:stratum 2} test-an-exec-outliving-its-environment-still-returns
  (let [entered (promise)
        release (promise)
        w (fx/world (env/fake-provider {:fake/exec-fn (blocking-exec entered release)}) :isolation/shared-kernel)
        lease (:environment/lease (fx/acquire w (:a w) (fx/request w (:a w))))
        running (future (env/exec! (:c w) (:a w) lease {:exec/argv ["sleep"] :exec/timeout-ms fx/exec-timeout-ms}))]
    @entered
    (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) lease)))
    (deliver release true)
    (is (= fx-ok @running))))
