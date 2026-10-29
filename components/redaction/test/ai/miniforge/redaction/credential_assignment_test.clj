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
(ns ai.miniforge.redaction.credential-assignment-test
  "Opaque secrets with no recognisable shape, caught by what names them:
   a credential-named assignment, or GitHub's x-access-token pair.
   Look-alikes that are not credentials pass through unchanged."
  (:require
   [ai.miniforge.redaction.interface :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} secret
  "An opaque value: no prefix, no fixed length, nothing shape rules know."
  "opaqueVALUE123")

(def ^{:stratum 0} ordinary-texts
  "Text that mentions tokens or looks random but carries no credential."
  ["MAX_TOKENS=4096"
   ":tokens 12"
   "token_count=3"
   "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b"
   "API_TOKEN = fetch_token()"
   "GH_TOKEN=$GH_TOKEN"
   "GH_TOKEN: ${{ secrets.GH_TOKEN }}"
   "GH_TOKEN_PATH=/tmp/token"
   "API_KEY=4096"
   "authenticate as x-access-token with the app token"
   "GH_TOKEN:\nnext line"])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} redacted-to
  "Redact TEXT; assert the secret is gone, then return the result."
  [text]
  (let [out (sut/redact text)]
    (is (not (str/includes? out secret)) (str "leaked from " (pr-str text)))
    out))

(deftest ^{:stratum 1} look-alikes-pass-through-test
  (testing "token counts, SHAs, code, variable references and bare names are untouched"
    (doseq [text ordinary-texts]
      (is (= text (sut/redact text)))
      (is (not (sut/secret-string? text)) (pr-str text))))
  (testing "a token count in data stays a number"
    (is (= {:tokens 12 :max_tokens 4096} (sut/redact {:tokens 12 :max_tokens 4096})))))

(deftest ^{:stratum 1} redacted-text-reads-clean-test
  (testing "the marker is not matched again: redaction is idempotent and its output is clean"
    (doseq [text [(str "GH_TOKEN=" secret)
                  (str "DB_PASSWORD: \"" secret "\"")
                  (str "x-access-token:" secret)]]
      (let [out (sut/redact text)]
        (is (= out (sut/redact out)))
        (is (sut/clean? out))
        (is (not (sut/secret-string? out)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} credential-named-assignment-redacts-its-value-test
  (let [marker (sut/marker)]
    (testing "env and shell style NAME=value, any case"
      (is (= (str "GH_TOKEN=" marker) (redacted-to (str "GH_TOKEN=" secret))))
      (is (= (str "export gh_token=" marker " && run")
             (redacted-to (str "export gh_token=" secret " && run"))))
      (is (= (str "CLIENT_SECRET=" marker) (redacted-to (str "CLIENT_SECRET=" secret))))
      (is (= (str "OPENAI_API_KEY=" marker) (redacted-to (str "OPENAI_API_KEY=" secret)))))
    (testing "header and YAML style NAME: value"
      (is (= (str "DB_PASSWORD: " marker) (redacted-to (str "DB_PASSWORD: " secret))))
      (is (= (str "DB_PASSWORD:" marker) (redacted-to (str "DB_PASSWORD:" secret)))))
    (testing "a quoted value is redacted whole, spaces included, and keeps its quotes"
      (is (= (str "{\"refresh_token\": \"" marker "\"}")
             (redacted-to (str "{\"refresh_token\": \"" secret "\"}"))))
      (is (= (str "DB_PASSWORD='" marker "'")
             (redacted-to (str "DB_PASSWORD='correct horse " secret "'")))))))

(deftest ^{:stratum 2} x-access-token-redacts-its-value-test
  (let [marker (sut/marker)]
    (testing "outside a URL, the host after @ survives"
      (is (= (str "auth x-access-token:" marker)
             (redacted-to (str "auth x-access-token:" secret))))
      (is (= (str "push x-access-token:" marker "@github.com/o/r")
             (redacted-to (str "push x-access-token:" secret "@github.com/o/r")))))
    (testing "inside a URL, the userinfo rule still applies"
      (is (= (str marker "github.com/o/r")
             (redacted-to (str "https://x-access-token:" secret "@github.com/o/r")))))))
