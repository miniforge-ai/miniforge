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
(ns ai.miniforge.cli.command-exit-code-test
  "Commands that fail exit 1 and usage errors exit 2, so scripts can tell
   a failure from success. Each command runs against temp directories and
   a temp config file, never ~/.miniforge, with exit! recorded instead of
   called."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.main.commands.init :as init]
   [ai.miniforge.cli.main.commands.monitoring :as monitoring]
   [ai.miniforge.cli.main.commands.scan :as scan]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-recording
  "Call `f` with exit! recorded and stdout/stderr captured. Returns
   {:exits [code ...] :out stdout :err stderr}."
  [f]
  (let [exits (atom [])
        out   (java.io.StringWriter.)
        err   (java.io.StringWriter.)]
    (with-redefs [shared/exit!     (fn [code] (swap! exits conj code) nil)
                  monitoring/exit! (fn [code] (swap! exits conj code) nil)]
      (binding [*out* out
                *err* err]
        (f)))
    {:exits @exits :out (str out) :err (str err)}))

(defn- ^{:stratum 0} missing-path []
  (str (fs/path (System/getProperty "java.io.tmpdir") (str "mf-missing-" (random-uuid)))))

(defn- ^{:stratum 0} with-temp-config
  "Call `f` with the path of a temp config.edn holding `config`."
  [config f]
  (let [dir (fs/create-temp-dir {:prefix "mf-config-"})
        path (str (fs/path dir "config.edn"))]
    (try
      (spit path (pr-str config))
      (f path)
      (finally (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} missing-repo-exits-1-test
  (testing "scan and init on a repository path that does not exist"
    (is (= [1] (:exits (run-recording #(scan/scan-cmd {:repo (missing-path)})))) "scan")
    (is (= [1] (:exits (run-recording #(init/init-cmd {:repo (missing-path)})))) "init")))

(deftest ^{:stratum 1} status-of-unknown-workflow-exits-1-test
  (testing "status <id> for a workflow with no events"
    (let [events (fs/create-temp-dir {:prefix "mf-events-"})]
      (try
        (with-redefs [app-config/events-dir (constantly (str events))]
          (let [{:keys [exits err]} (run-recording #(main/status-cmd {:opts {:workflow-id (str (random-uuid))}}))]
            (is (= [1] exits))
            (is (not (str/blank? err)) "the error is on stderr")))
        (finally (fs/delete-tree events))))))

(deftest ^{:stratum 1} tui-that-fails-to-start-exits-1-test
  (testing "tui whose launcher throws"
    (binding [monitoring/*tui-available?* true
              monitoring/*start-standalone-tui!* (fn [_] (throw (ex-info "no terminal" {})))]
      (with-redefs [app-config/events-dir (constantly (missing-path))]
        (is (= [1] (:exits (run-recording #(monitoring/tui-cmd {})))))))))

(deftest ^{:stratum 1} config-get-missing-key-exits-1-test
  (testing "config get for a key with no value: error on stderr, nothing on stdout"
    (with-temp-config {}
      (fn [path]
        (let [{:keys [exits out err]} (run-recording #(config/cmd-get {:key "made.up.key" :config path}))]
          (is (= [1] exits))
          (is (= "" out))
          (is (str/includes? err "made.up.key")))))))

(deftest ^{:stratum 1} config-get-false-value-is-found-test
  (testing "a key set to false is printed, not reported missing"
    (with-temp-config {:feature {:enabled false}}
      (fn [path]
        (let [{:keys [exits out]} (run-recording #(config/cmd-get {:key "feature.enabled" :config path}))]
          (is (= [] exits))
          (is (= "false" (str/trim out))))))))

(deftest ^{:stratum 1} config-backend-unknown-exits-1-test
  (testing "config backend bogus: exits 1 and leaves the config file as it was"
    (with-temp-config {:llm {:backend :claude}}
      (fn [path]
        (let [{:keys [exits out]} (run-recording #(config/cmd-backend {:backend "bogus" :config path}))]
          (is (= [1] exits))
          (is (= "" out) "the explanation goes to stderr")
          (is (= {:llm {:backend :claude}} (read-string (slurp path)))))))))

(deftest ^{:stratum 1} usage-errors-exit-2-test
  (testing "a missing argument is a usage error"
    (with-temp-config {}
      (fn [path]
        (is (= [2] (:exits (run-recording #(config/cmd-get {:config path})))) "config get")
        (is (= [2] (:exits (run-recording #(config/cmd-set {:key "llm.model" :config path})))) "config set")
        (is (= [2] (:exits (run-recording #(config/cmd-backend {:config path})))) "config backend")
        (is (= {} (read-string (slurp path))) "nothing written")))))

(deftest ^{:stratum 1} config-errors-go-to-stderr-test
  (testing "config's print-error writes to stderr"
    (let [{:keys [out err]} (run-recording #(config/print-error "boom"))]
      (is (= "" out))
      (is (str/includes? err "boom")))))
