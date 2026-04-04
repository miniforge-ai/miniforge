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

(ns ai.miniforge.tool-registry.schema-test
  "Tests for tool-registry schema validation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tool-registry.schema :as schema]))

;------------------------------------------------------------------------------ Tool validation

(deftest validate-tool-test
  (testing "valid LSP tool configuration"
    (let [tool {:tool/id :lsp/clojure
                :tool/type :lsp
                :tool/name "Clojure LSP"
                :tool/description "Language server for Clojure"
                :tool/config {:lsp/command ["clojure-lsp"]}}
          result (schema/validate-tool tool)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "valid function tool configuration"
    (let [tool {:tool/id :tools/greet
                :tool/type :function
                :tool/name "Greet"
                :tool/description "Greets users"}
          result (schema/validate-tool tool)]
      (is (:valid? result))))

  (testing "invalid tool - missing required fields"
    (let [tool {:tool/id :test/invalid}
          result (schema/validate-tool tool)]
      (is (not (:valid? result)))
      (is (some? (:errors result)))))

  (testing "invalid tool - bad type"
    (let [tool {:tool/id :test/invalid
                :tool/type :unknown-type
                :tool/name "Test"}
          result (schema/validate-tool tool)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Tool ID validation

(deftest valid-tool-id-test
  (testing "namespaced keywords are valid"
    (is (schema/valid-tool-id? :lsp/clojure))
    (is (schema/valid-tool-id? :tools/greet))
    (is (schema/valid-tool-id? :my.company/tool)))

  (testing "non-namespaced keywords are invalid"
    (is (not (schema/valid-tool-id? :clojure)))
    (is (not (schema/valid-tool-id? :test))))

  (testing "non-keywords are invalid"
    (is (not (schema/valid-tool-id? "lsp/clojure")))
    (is (not (schema/valid-tool-id? nil)))))

;------------------------------------------------------------------------------ LSP config validation

(deftest validate-lsp-config-test
  (testing "valid LSP config"
    (let [config {:lsp/command ["clojure-lsp"]
                  :lsp/languages #{"clojure"}
                  :lsp/capabilities #{:diagnostics :format}}
          result (schema/validate-lsp-config config)]
      (is (:valid? result))))

  (testing "LSP config requires command"
    (let [config {:lsp/languages #{"clojure"}}
          result (schema/validate-lsp-config config)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Capability checks

(deftest capability-checks-test
  (testing "has-capability? returns true when capability present"
    (let [tool {:tool/id :test/tool
                :tool/capabilities #{:code/diagnostics :code/format}}]
      (is (schema/has-capability? tool :code/diagnostics))
      (is (schema/has-capability? tool :code/format))))

  (testing "has-capability? returns false when capability absent"
    (let [tool {:tool/id :test/tool
                :tool/capabilities #{:code/diagnostics}}]
      (is (not (schema/has-capability? tool :code/hover)))))

  (testing "has-capability? handles nil capabilities"
    (let [tool {:tool/id :test/tool}]
      (is (not (schema/has-capability? tool :code/diagnostics)))))

  (testing "has-all-capabilities? checks all"
    (let [tool {:tool/id :test/tool
                :tool/capabilities #{:code/diagnostics :code/format :code/hover}}]
      (is (schema/has-all-capabilities? tool #{:code/diagnostics :code/format}))
      (is (not (schema/has-all-capabilities? tool #{:code/diagnostics :code/completion}))))))

;------------------------------------------------------------------------------ Type checks

(deftest type-checks-test
  (testing "lsp-tool? returns true for LSP tools"
    (is (schema/lsp-tool? {:tool/type :lsp}))
    (is (not (schema/lsp-tool? {:tool/type :function}))))

  (testing "function-tool? returns true for function tools"
    (is (schema/function-tool? {:tool/type :function}))
    (is (not (schema/function-tool? {:tool/type :lsp}))))

  (testing "enabled? defaults to true"
    (is (schema/enabled? {:tool/id :test}))
    (is (schema/enabled? {:tool/id :test :tool/enabled true}))
    (is (not (schema/enabled? {:tool/id :test :tool/enabled false})))))

;------------------------------------------------------------------------------ Normalization

(deftest normalize-tool-test
  (testing "normalizes capabilities from vector to set"
    (let [tool {:tool/id :test/tool
                :tool/type :lsp
                :tool/name "Test"
                :tool/capabilities [:code/diagnostics :code/format]}
          normalized (schema/normalize-tool tool)]
      (is (set? (:tool/capabilities normalized)))
      (is (= #{:code/diagnostics :code/format} (:tool/capabilities normalized)))))

  (testing "sets default enabled to true"
    (let [tool {:tool/id :test/tool
                :tool/type :lsp
                :tool/name "Test"}
          normalized (schema/normalize-tool tool)]
      (is (true? (:tool/enabled normalized)))))

  (testing "sets default version"
    (let [tool {:tool/id :test/tool
                :tool/type :lsp
                :tool/name "Test"}
          normalized (schema/normalize-tool tool)]
      (is (= "1.0.0" (:tool/version normalized)))))

  (testing "preserves explicit enabled false"
    (let [tool {:tool/id :test/tool
                :tool/type :lsp
                :tool/name "Test"
                :tool/enabled false}
          normalized (schema/normalize-tool tool)]
      (is (false? (:tool/enabled normalized))))))

;------------------------------------------------------------------------------ Result builders

(deftest result-builders-test
  (testing "success builds success result"
    (let [result (schema/success :tool {:tool/id :test})]
      (is (:success? result))
      (is (= {:tool/id :test} (:tool result)))))

  (testing "failure builds failure result"
    (let [result (schema/failure :tool "Something went wrong")]
      (is (not (:success? result)))
      (is (nil? (:tool result)))
      (is (= "Something went wrong" (:error result)))))

  (testing "failure-with-errors builds result with errors list"
    (let [result (schema/failure-with-errors :tool [{:error "err1"} {:error "err2"}])]
      (is (not (:success? result)))
      (is (= 2 (count (:errors result)))))))
