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
(ns ai.miniforge.gate.edited-forms-test
  "A change edits whole top-level forms in Clojure files and exact lines
   elsewhere; forms are found in any source a namespace can hold."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.gate.edited-forms :as edited-forms]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private source
  "Forms on lines 1, 3-5, 7 and 8, with reader syntax that needs no alias
   map, reader function or platform to locate."
  (str "(ns demo.core (:require [demo.util :as u]))\n"
       "\n"
       "(defn f\n"
       "  [x]\n"
       "  [::u/k ::unknown/k #inst \"2026-01-01\" #demo/tag 1 `(a ~x) #\"re\" #(inc %)])\n"
       ";; a comment between forms\n"
       "#?(:cljs (def only-cljs 1))\n"
       "(def g 2)\n"))

(deftest ^{:stratum 0} form-spans-of-unreadable-content-is-an-anomaly-test
  (is (anomaly/anomaly? (edited-forms/form-spans "(defn f\n  [x]\n")))
  (is (anomaly/anomaly? (edited-forms/form-spans "(def m {:a 1 :a 2})\n"))
      "a duplicate map key")
  (is (anomaly/anomaly? (edited-forms/form-spans "(def s #{1 1})\n"))
      "a duplicate set element"))

(deftest ^{:stratum 0} other-files-are-edited-on-their-added-lines-test
  (is (= #{2} (edited-forms/edited-lines "docs/notes.md" "# a\nb\nc\n" #{2} [1]))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} form-spans-reads-past-unresolvable-syntax-test
  (is (= [[1 1] [3 5] [7 7] [8 8]] (edited-forms/form-spans source))))

(deftest ^{:stratum 1} an-added-line-edits-its-whole-form-test
  (is (= #{3 4 5} (edited-forms/edited-lines "src/demo/core.clj" source #{4} []))))

(deftest ^{:stratum 1} an-added-line-between-forms-edits-only-itself-test
  (is (= #{6} (edited-forms/edited-lines "src/demo/core.clj" source #{6} []))))

(deftest ^{:stratum 1} a-deletion-point-edits-the-form-around-it-test
  (is (= #{3 4 5} (edited-forms/edited-lines "src/demo/core.clj" source #{} [4])))
  (is (= #{3 4 5} (edited-forms/edited-lines "src/demo/core.clj" source #{} [2]))
      "a deletion directly before a form, such as a removed ^:private line, edits it")
  (is (= #{} (edited-forms/edited-lines "src/demo/core.clj" source #{} [5]))
      "a deletion after a form's last line is between forms"))
