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

(ns ai.miniforge.lsp-mcp-bridge.installer-test
  "Tests for LSP auto-installer (unit tests, no actual downloads)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.installer :as installer]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Platform detection

(deftest platform-key-test
  (testing "returns a valid platform string"
    (let [pk (installer/platform-key)]
      (is (string? pk))
      (is (re-matches #"(macos|linux|windows|unknown)-(aarch64|x86_64|.+)" pk)
          (str "Unexpected platform key: " pk)))))

;------------------------------------------------------------------------------ Binary resolution

(deftest bin-dir-test
  (testing "returns a path under home directory"
    (let [bd (installer/bin-dir)]
      (is (string? bd))
      (is (.endsWith bd "/.miniforge/bin")))))

(deftest which-test
  (testing "finds existing binary"
    ;; 'bb' must exist since we're running under it
    (let [result (installer/which "bb")]
      (is (some? result))
      (is (string? result))))

  (testing "returns nil for non-existent binary"
    (is (nil? (installer/which "nonexistent-binary-xyz-12345")))))

(deftest resolve-binary-test
  (testing "resolves existing binary from PATH"
    (let [result (installer/resolve-binary "bb")]
      (is (some? result))))

  (testing "returns nil for non-existent binary"
    (is (nil? (installer/resolve-binary "nonexistent-binary-xyz-12345")))))

;------------------------------------------------------------------------------ ensure-installed (unit, no downloads)

(deftest ensure-installed-already-on-path-test
  (testing "returns command unchanged when binary is on PATH"
    (let [result (installer/ensure-installed nil :lsp/test ["bb"])]
      (is (= ["bb"] (:command result)))
      (is (not (response/anomaly-map? result))))))

(deftest ensure-installed-not-found-no-registry-test
  (testing "returns anomaly when binary not found and no registry"
    (let [result (installer/ensure-installed nil :lsp/test ["nonexistent-xyz"])]
      (is (response/anomaly-map? result))
      (is (.contains (:anomaly/message result) "not found")))))
