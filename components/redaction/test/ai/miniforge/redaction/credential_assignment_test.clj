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

   One table, every row run through both `redact` and `secret-string?`:
   the two must agree, so a row that redaction changes is one detection
   reports, and a row it leaves alone is one detection passes."
  (:require
   [ai.miniforge.redaction.interface :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rows
  "[input expected-redaction why]. `opaqueVALUE123` is the secret in
   every row that has one; a row whose input equals its expectation
   carries no secret."
  [;; credential-named assignment, unquoted
   ["GH_TOKEN=opaqueVALUE123" "GH_TOKEN=[REDACTED]" "env style"]
   ["export gh_token=opaqueVALUE123 && run" "export gh_token=[REDACTED] && run" "any case"]
   ["CLIENT_SECRET=opaqueVALUE123" "CLIENT_SECRET=[REDACTED]" "_SECRET"]
   ["OPENAI_API_KEY=opaqueVALUE123" "OPENAI_API_KEY=[REDACTED]" "_API_KEY"]
   ["DB_PASSWORD: opaqueVALUE123" "DB_PASSWORD: [REDACTED]" "header style"]
   ["DB_PASSWORD:opaqueVALUE123" "DB_PASSWORD:[REDACTED]" "no space after colon"]
   ["DB_PASSWORD=\"opaqueVALUE123" "DB_PASSWORD=\"[REDACTED]" "closing quote lost"]
   ;; credential-named assignment, quoted
   ["{\"refresh_token\": \"opaqueVALUE123\"}" "{\"refresh_token\": \"[REDACTED]\"}" "JSON keeps its quotes"]
   ["DB_PASSWORD='correct horse opaqueVALUE123'" "DB_PASSWORD='[REDACTED]'" "spaces inside quotes"]
   ;; review 4409406742: the closing quote must match the opening one
   ["DB_PASSWORD=\"prefix'opaqueVALUE123\"" "DB_PASSWORD=\"[REDACTED]\"" "single quote inside double"]
   ["DB_PASSWORD='prefix\"opaqueVALUE123'" "DB_PASSWORD='[REDACTED]'" "double quote inside single"]
   ["DB_PASSWORD=\"pre\\\"fix opaqueVALUE123\"" "DB_PASSWORD=\"[REDACTED]\"" "escaped quote inside"]
   ["DB_PASSWORD=\"opaqueVALUE123\\\\\" next" "DB_PASSWORD=\"[REDACTED]\" next" "escaped backslash before the close"]
   ;; review 4409406791: aligned values, any run of spaces or tabs
   ["GH_TOKEN:     opaqueVALUE123" "GH_TOKEN:     [REDACTED]" "five spaces"]
   ["GH_TOKEN:\t\t   \"opaque VALUE123\"" "GH_TOKEN:\t\t   \"[REDACTED]\"" "tabs and spaces, quoted"]
   ;; review 4409406839: only a value that IS the marker is exempt
   ["DB_PASSWORD=\"sk-abcdefghijklmnopqrst opaqueVALUE123\"" "DB_PASSWORD=\"[REDACTED]\"" "shape rule ran first, quoted"]
   ["GH_TOKEN=[REDACTED]opaqueVALUE123" "GH_TOKEN=[REDACTED]" "marker prefix, unquoted"]
   ["x-access-token:[REDACTED]opaqueVALUE123" "x-access-token:[REDACTED]" "marker prefix, x-access-token"]
   ["GH_TOKEN=[REDACTED]" "GH_TOKEN=[REDACTED]" "exactly the marker, unquoted"]
   ["DB_PASSWORD: \"[REDACTED]\"" "DB_PASSWORD: \"[REDACTED]\"" "exactly the marker, quoted"]
   ["x-access-token:[REDACTED]@github.com" "x-access-token:[REDACTED]@github.com" "exactly the marker, x-access-token"]
   ;; a quoted literal assigned in code (moved from the :no-secrets gate)
   ["password = \"hunter2hunter2\"" "password = \"[REDACTED]\"" "code assignment, spaces around ="]
   ["self.api_key = 'opaqueVALUE123'" "self.api_key = '[REDACTED]'" "attribute assignment"]
   ["client-secret='opaque value 123'" "client-secret='[REDACTED]'" "hyphenated name, spaces in value"]
   ["password == \"hunter2hunter2\"" "password == \"hunter2hunter2\"" "a comparison, not an assignment"]
   ["token = \"short\"" "token = \"short\"" "fewer than 8 characters"]
   ["password = \"$DB_PASSWORD_VAR\"" "password = \"$DB_PASSWORD_VAR\"" "quoted variable reference"]
   ;; x-access-token
   ["auth x-access-token:opaqueVALUE123" "auth x-access-token:[REDACTED]" "outside a URL"]
   ["push x-access-token:opaqueVALUE123@github.com/o/r" "push x-access-token:[REDACTED]@github.com/o/r" "host survives"]
   ["https://x-access-token:opaqueVALUE123@github.com/o/r" "[REDACTED]github.com/o/r" "URL userinfo rule"]
   ;; look-alikes that carry no credential
   ["MAX_TOKENS=4096" "MAX_TOKENS=4096" "plural name"]
   [":tokens 12" ":tokens 12" "token count"]
   ["token_count=3" "token_count=3" "name does not end in _TOKEN"]
   ["3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b" "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b" "SHA"]
   ["API_TOKEN = fetch_token()" "API_TOKEN = fetch_token()" "code assignment"]
   ["GH_TOKEN=$GH_TOKEN" "GH_TOKEN=$GH_TOKEN" "variable reference"]
   ["GH_TOKEN: \"${GH_TOKEN}\"" "GH_TOKEN: \"${GH_TOKEN}\"" "quoted variable reference"]
   ["GH_TOKEN: ${{ secrets.GH_TOKEN }}" "GH_TOKEN: ${{ secrets.GH_TOKEN }}" "CI secret reference"]
   ["GH_TOKEN_PATH=/tmp/token" "GH_TOKEN_PATH=/tmp/token" "name ends in _PATH"]
   ["API_KEY=4096" "API_KEY=4096" "bare name, no prefix"]
   ["DB_PASSWORD=\"\"" "DB_PASSWORD=\"\"" "empty value"]
   ["authenticate as x-access-token with the app token" "authenticate as x-access-token with the app token" "the word alone"]
   ["GH_TOKEN:\nnext line" "GH_TOKEN:\nnext line" "never crosses a newline"]])

(deftest ^{:stratum 0} token-counts-in-data-stay-numbers-test
  (is (= {:tokens 12 :max_tokens 4096} (sut/redact {:tokens 12 :max_tokens 4096}))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} redaction-and-detection-agree-on-every-row-test
  (is (= "[REDACTED]" (sut/marker)) "the rows spell out the marker")
  (doseq [[input expected why] rows]
    (testing (str why ": " (pr-str input))
      (is (= expected (sut/redact input)) "redact")
      (is (= (not= input expected) (sut/secret-string? input)) "secret-string? on the input")
      (is (= expected (sut/redact expected)) "redaction is idempotent")
      (is (not (sut/secret-string? expected)) "redacted text reads clean"))))

(deftest ^{:stratum 1} pattern-secret-string-skips-card-checksums-test
  (testing "ordinary numbers in code can pass the card checksum; the pattern-only check ignores them"
    (let [code "(def retention-ms (* 20 24 60 60 1000))"]
      (is (sut/secret-string? code) "the card checksum fires on this arithmetic")
      (is (not (sut/pattern-secret-string? code)))))
  (testing "it still answers for every pattern row"
    (doseq [[input expected] rows]
      (is (= (not= input expected) (sut/pattern-secret-string? input)) (pr-str input)))))
