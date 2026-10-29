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
(ns ai.miniforge.cli.config-test
  "Tests for Aero configuration management."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.cli.app-config :as app-config]
            [ai.miniforge.cli.backends.status :as backend-status]
            [ai.miniforge.cli.config :as config]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} load-config-test
  (testing "load-config loads configuration from resources"
    (let [cfg (config/load-config)]
      (is (map? cfg))
      (is (contains? cfg :llm))
      (is (contains? cfg :artifacts))))

  (testing "load-config with :dev profile"
    (let [cfg (config/load-config {:profile :dev})]
      (is (map? cfg))
      (is (keyword? (get-in cfg [:llm :backend])))))

  (testing "load-config with :test profile"
    (let [cfg (config/load-config {:profile :test})]
      (is (map? cfg))))

  (testing "load-config with :prod profile"
    (let [cfg (config/load-config {:profile :prod})]
      (is (map? cfg))))

  (testing "load-config returns default config when config file not found"
    (let [cfg (config/load-config {:config-file (io/file "/nonexistent/config.edn")})]
      (is (map? cfg)))))

(deftest ^{:stratum 0} get-llm-backend-test
  (testing "get-llm-backend returns workflow override when provided"
    (let [cfg {:llm {:backend :anthropic}}]
      (is (= :openai (config/get-llm-backend cfg :openai)))))

  (testing "get-llm-backend returns config value when no override"
    (let [cfg {:llm {:backend :anthropic}}]
      (is (= :anthropic (config/get-llm-backend cfg nil)))))

  (testing "get-llm-backend returns :opencode as default"
    (is (= :opencode (config/get-llm-backend {} nil)))
    (is (= :opencode (config/get-llm-backend {:llm {}} nil)))))

(deftest ^{:stratum 0} get-llm-timeout-test
  (testing "get-llm-timeout returns config value"
    (let [cfg {:llm {:timeout-ms 600000}}]
      (is (= 600000 (config/get-llm-timeout cfg)))))

  (testing "get-llm-timeout returns default when not in config"
    (is (= 300000 (config/get-llm-timeout {})))
    (is (= 300000 (config/get-llm-timeout {:llm {}})))))

(deftest ^{:stratum 0} get-llm-line-timeout-test
  (testing "get-llm-line-timeout returns config value"
    (let [cfg {:llm {:line-timeout-ms 120000}}]
      (is (= 120000 (config/get-llm-line-timeout cfg)))))

  (testing "get-llm-line-timeout returns default when not in config"
    (is (= 60000 (config/get-llm-line-timeout {})))
    (is (= 60000 (config/get-llm-line-timeout {:llm {}})))))

(deftest ^{:stratum 0} config-structure-test
  (testing "config.edn has expected structure"
    (let [cfg (config/load-config)]
      (testing "llm config section"
        (is (keyword? (get-in cfg [:llm :backend]))))

      (testing "artifacts config section"
        (is (string? (get-in cfg [:artifacts :dir])))))))

(deftest ^{:stratum 0} env-overrides-test
  (testing "config respects environment variable overrides"
    ;; This test documents the behavior but doesn't test actual env vars
    ;; since that would require setting system properties
    (let [cfg (config/load-config)]
      ;; Config should be loaded successfully
      (is (map? cfg))
      ;; Values should be present (either from env or defaults)
      (is (some? (get-in cfg [:llm :backend]))))))

;; Keys the defaults used to carry and `config list` used to print, although
;; nothing in a run reads them.
(def ^{:stratum 0} ^:private unread-keys
  [[:llm :timeout-ms] [:llm :line-timeout-ms] [:llm :max-tokens]
   [:workflow :max-iterations] [:workflow :max-tokens] [:workflow :failure-strategy]
   [:meta-loop]])

(deftest ^{:stratum 0} config-list-shows-no-unread-setting-test
  (let [stale-dir "/stale/artifacts/from/config.edn"
        cfg (merge config/default-config
                   {:llm {:backend :opencode :model "m"
                          :timeout-ms 600000 :line-timeout-ms 60000 :max-tokens 5}
                    :workflow {:max-iterations 7 :max-tokens 9 :failure-strategy :retry}
                    :artifacts {:dir stale-dir}})
        out (with-out-str (config/display-config cfg "/nonexistent/config.edn"))]
    (testing "config list does not print LLM or workflow limits that nothing enforces"
      (doseq [label ["timeout" "max-tokens" "max-iterations" "failure-strategy"]]
        (is (not (str/includes? out label)) label)))
    (testing "config list prints the artifacts directory the artifact commands use"
      (is (str/includes? out (app-config/artifacts-dir)))
      (is (not (str/includes? out stale-dir))))))

(deftest ^{:stratum 0} cli-defaults-name-no-model-test
  (testing "the defaults that config init writes name no model"
    ;; In the user's file a model is read as their choice for every role
    ;; on every backend.
    (is (nil? (get-in config/default-config [:llm :model]))))
  (testing "config list says when no model is set"
    (let [out (with-out-str
                (config/display-config {:llm {:backend :codex}} "/nonexistent/config.edn"))]
      (is (str/includes? out "model: not set")))))

(deftest ^{:stratum 0} parse-config-value-rejects-reader-eval-test
  (testing "parse-config-value returns the raw string for #=() reader-eval forms"
    ;; edn/read-string rejects #=(...) — the catch block returns the literal
    ;; string unchanged, not the result of evaluating the form.
    ;; If this assertion fails (returns a number), read-string regressed to
    ;; clojure.core/read-string which executes reader-eval at parse time.
    (is (= "#=(identity 42)" (config/parse-config-value "#=(identity 42)")))
    (is (string? (config/parse-config-value "#=(java.lang.Runtime/getRuntime)"))))

  (testing "parse-config-value still parses safe EDN correctly"
    (is (= :foo (config/parse-config-value ":foo")))
    (is (= 42 (config/parse-config-value "42")))
    (is (= {:a 1} (config/parse-config-value "{:a 1}")))))

(deftest ^{:stratum 0} read-config-file-rejects-reader-eval-test
  (testing "read-config-file returns nil and does not execute reader-eval forms"
    (let [tmp (java.io.File/createTempFile "reader-eval-guard" ".edn")]
      (try
        (spit tmp "#=(identity 42)")
        ;; edn/read-string throws on #= — read-config-file catches and returns nil.
        ;; A regression to clojure.core/read-string would return 42 here.
        ;; Pass a String path — slurp cannot open java.nio.file.Path on the JVM.
        (is (nil? (config/read-config-file (.getPath tmp))))
        (finally (.delete tmp)))))

  (testing "read-config-file correctly parses safe EDN maps"
    (let [tmp (java.io.File/createTempFile "safe-edn-config" ".edn")]
      (try
        (spit tmp "{:llm {:backend :anthropic}}")
        (is (= {:llm {:backend :anthropic}} (config/read-config-file (.getPath tmp))))
        (finally (.delete tmp))))))

(deftest ^{:stratum 0} writers-refuse-unparseable-config-test
  (doseq [[label run-cmd] [["config set" #(config/cmd-set {:config % :key "llm.backend" :value "codex"})]
                           ["config backend" #(config/cmd-backend {:config % :backend "claude"})]]]
    (testing (str label " leaves a config that does not parse untouched and exits 1")
      (let [tmp     (java.io.File/createTempFile "unparseable-config" ".edn")
            garbage "{:llm {:backend :claude} :policy-packs [:core"
            exits   (atom [])
            err     (java.io.StringWriter.)]
        (try
          (spit tmp garbage)
          (with-redefs [shared/exit! (fn [code] (swap! exits conj code) (throw (ex-info "exit" {})))
                        backend-status/validate-backend (constantly {:valid? true})]
            (binding [*err* err]
              (is (thrown? clojure.lang.ExceptionInfo (with-out-str (run-cmd (.getPath tmp)))))))
          (is (= garbage (slurp tmp)))
          (is (= [1] @exits))
          (is (str/includes? (str err) (.getPath tmp)))
          (finally (.delete tmp)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} defaults-carry-no-unread-keys-test
  (testing "config init/reset do not write settings that nothing reads"
    (doseq [path unread-keys]
      (is (nil? (get-in config/default-config path)) (str path)))))
