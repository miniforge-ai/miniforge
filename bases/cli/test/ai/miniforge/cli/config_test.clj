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
            [ai.miniforge.cli.config :as config]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} load-config-test
  (testing "load-config loads configuration from resources"
    (let [cfg (config/load-config)]
      (is (map? cfg))
      (is (contains? cfg :llm))
      (is (contains? cfg :workflow))
      (is (contains? cfg :meta-loop))
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
        (is (keyword? (get-in cfg [:llm :backend])))
        (is (number? (get-in cfg [:llm :timeout-ms])))
        (is (number? (get-in cfg [:llm :line-timeout-ms])))
        (is (number? (get-in cfg [:llm :max-tokens]))))

      (testing "workflow config section"
        (is (number? (get-in cfg [:workflow :max-iterations])))
        (is (number? (get-in cfg [:workflow :max-tokens])))
        (is (keyword? (get-in cfg [:workflow :failure-strategy]))))

      (testing "meta-loop config section"
        (is (boolean? (get-in cfg [:meta-loop :enabled])))
        (is (number? (get-in cfg [:meta-loop :max-convergence-iterations])))
        (is (number? (get-in cfg [:meta-loop :convergence-threshold]))))

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
      (is (some? (get-in cfg [:llm :backend])))
      (is (some? (get-in cfg [:llm :timeout-ms]))))))

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
