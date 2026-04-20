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

(ns ai.miniforge.bb-out.core-test
  "Unit tests for bb-out. Pure formatters are checked by string
   comparison; printers are checked by capturing *out* / *err* with
   `with-out-str`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-out.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(def ^:private section-prefix "==> ")
(def ^:private step-prefix    "    ")

(defn- capture-err
  "Run `f`, return what it wrote to *err*."
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw] (f))
    (str sw)))

;------------------------------------------------------------------------------ Layer 1
;; Pure formatters

(deftest test-format-section-prefixes-with-arrow
  (testing "given a message → `==> msg`"
    (is (= (str section-prefix "hello") (sut/format-section "hello")))))

(deftest test-format-step-indents
  (testing "given a message → four-space indent"
    (is (= (str step-prefix "work") (sut/format-step "work")))))

(deftest test-format-ok-warn-fail-carry-glyphs
  (testing "given a message → ok/warn/fail each include a distinct glyph"
    (let [ok-out   (sut/format-ok "a")
          warn-out (sut/format-warn "b")
          fail-out (sut/format-fail "c")]
      (is (re-find #"\u2713" ok-out))
      (is (re-find #"\u26A0" warn-out))
      (is (re-find #"\u2717" fail-out))
      (is (= 3 (count (distinct [ok-out warn-out fail-out])))))))

;------------------------------------------------------------------------------ Layer 2
;; Printers — stream routing

(deftest test-section-step-ok-write-to-stdout
  (testing "given a message → section/step/ok write to *out*"
    (is (= (str section-prefix "h\n") (with-out-str (sut/section "h"))))
    (is (= (str step-prefix "h\n")    (with-out-str (sut/step "h"))))
    (is (re-find #"\u2713 h"          (with-out-str (sut/ok "h"))))))

(deftest test-warn-fail-write-to-stderr
  (testing "given a message → warn/fail write to *err*, not *out*"
    (let [warn-stdout (with-out-str (sut/warn "w"))
          warn-stderr (capture-err #(sut/warn "w"))
          fail-stdout (with-out-str (sut/fail "f"))
          fail-stderr (capture-err #(sut/fail "f"))]
      (is (= "" warn-stdout))
      (is (re-find #"\u26A0 w" warn-stderr))
      (is (= "" fail-stdout))
      (is (re-find #"\u2717 f" fail-stderr)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-out.core-test)

  :leave-this-here)
