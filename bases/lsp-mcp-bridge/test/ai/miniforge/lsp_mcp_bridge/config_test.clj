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

(ns ai.miniforge.lsp-mcp-bridge.config-test
  "Tests for configuration reader."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.config :as config]))

;------------------------------------------------------------------------------ Language routing

(deftest file-extension-test
  (testing "extracts file extensions"
    (is (= "clj" (config/file-extension "/foo/bar.clj")))
    (is (= "ts" (config/file-extension "/foo/bar.ts")))
    (is (= "py" (config/file-extension "/foo/bar.py")))
    (is (= "go" (config/file-extension "/foo/bar.go")))
    (is (= "rs" (config/file-extension "/foo/bar.rs")))
    (is (= "lua" (config/file-extension "/foo/bar.lua")))
    (is (= "java" (config/file-extension "/foo/bar.java")))))

(deftest language-id-test
  (testing "maps extensions to language IDs"
    (is (= "clojure" (config/language-id "/foo/bar.clj")))
    (is (= "clojurescript" (config/language-id "/foo/bar.cljs")))
    (is (= "clojure" (config/language-id "/foo/bar.cljc")))
    (is (= "clojure" (config/language-id "/foo/bar.edn")))
    (is (= "typescript" (config/language-id "/foo/bar.ts")))
    (is (= "typescriptreact" (config/language-id "/foo/bar.tsx")))
    (is (= "javascript" (config/language-id "/foo/bar.js")))
    (is (= "javascriptreact" (config/language-id "/foo/bar.jsx")))
    (is (= "python" (config/language-id "/foo/bar.py")))
    (is (= "go" (config/language-id "/foo/bar.go")))
    (is (= "rust" (config/language-id "/foo/bar.rs")))
    (is (= "lua" (config/language-id "/foo/bar.lua")))
    (is (= "java" (config/language-id "/foo/bar.java"))))

  (testing "returns nil for unknown extensions"
    (is (nil? (config/language-id "/foo/bar.xyz")))
    (is (nil? (config/language-id "/foo/bar.txt")))))

;------------------------------------------------------------------------------ Config loading

(deftest load-config-test
  (testing "loads built-in configs from classpath"
    (let [cfg (config/load-config)]
      (is (seq (:tools cfg))
          "Should have at least one tool config")
      (is (contains? (:tool-index cfg) :lsp/clojure)
          "Should include the built-in Clojure LSP config")
      (is (contains? (:lang-index cfg) "clojure")
          "Should have clojure in language index"))))

(deftest load-config-language-routing-test
  (testing "resolves tool for Clojure files"
    (let [cfg (config/load-config)
          tool (config/resolve-tool-for-file cfg "/foo/bar.clj")]
      (is (some? tool))
      (is (= :lsp/clojure (:tool/id tool)))))

  (testing "resolves tool for TypeScript files"
    (let [cfg (config/load-config)
          tool (config/resolve-tool-for-file cfg "/foo/bar.ts")]
      (is (some? tool))
      (is (= :lsp/typescript (:tool/id tool)))))

  (testing "resolves tool for Python files"
    (let [cfg (config/load-config)
          tool (config/resolve-tool-for-file cfg "/foo/bar.py")]
      (is (some? tool))
      (is (= :lsp/python (:tool/id tool)))))

  (testing "returns nil for unsupported files"
    (let [cfg (config/load-config)]
      (is (nil? (config/resolve-tool-for-file cfg "/foo/bar.xyz"))))))

;------------------------------------------------------------------------------ Registry

(deftest read-lsp-registry-test
  (testing "reads the LSP installation registry"
    (let [reg (config/read-lsp-registry)]
      (is (some? reg))
      (is (string? (:registry/version reg)))
      (is (map? (:servers reg)))
      (is (contains? (:servers reg) :clojure-lsp)
          "Should have clojure-lsp in registry"))))
