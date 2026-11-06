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
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private path
  "A Clojure source path, so its content is read as forms."
  "src/demo/core.clj")

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

(def ^{:stratum 0} ^:private two-forms
  "Form a on line 2, form b on lines 3-5."
  "(ns demo.core)\n(def a 1)\n(defn b\n  []\n  2)\n")

(deftest ^{:stratum 0} form-spans-of-unreadable-content-is-an-anomaly-test
  (is (anomaly/anomaly? (edited-forms/form-spans "(defn f\n  [x]\n")))
  (is (anomaly/anomaly? (edited-forms/form-spans "(def m {:a 1 :a 2})\n"))
      "a duplicate map key")
  (is (anomaly/anomaly? (edited-forms/form-spans "(def s #{1 1})\n"))
      "a duplicate set element"))

(deftest ^{:stratum 0} form-spans-keeps-distinct-literals-distinct-test
  (is (= 2 (count (edited-forms/form-spans "(ns demo.core)\n(def m {:user/k 1 ::k 2})\n")))
      "::k is not :user/k")
  (is (= 1 (count (edited-forms/form-spans "(def m {:u/k 1 ::u/k 2})\n")))
      "::u/k is not :u/k")
  (is (= 1 (count (edited-forms/form-spans "(def s #{#inst \"2026-01-01\" \"2026-01-01\"})\n")))
      "a tagged literal is not its string"))

(deftest ^{:stratum 0} other-files-are-edited-on-their-added-lines-test
  (is (= #{2} (edited-forms/edited-lines "docs/notes.md" "# a\nb\nc\n" "# a\nc\n" #{2}))))

(deftest ^{:stratum 0} form-spans-carries-each-form's-own-text-test
  (is (= ["(def x 1)" "(def y 2)"]
         (map :text (edited-forms/form-spans "(def x 1) (def y 2)\n")))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} form-spans-reads-past-unresolvable-syntax-test
  (is (= [[1 1] [3 5] [7 7] [8 8]] (map (juxt :start :end) (edited-forms/form-spans source)))))

(deftest ^{:stratum 1} a-changed-form-is-edited-whole-test
  (is (= #{2} (edited-forms/edited-lines path (str/replace two-forms "(def a 1)" "(def a 2)") two-forms #{2}))
      "b, directly below, is unchanged"))

(deftest ^{:stratum 1} a-form-whose-prefix-was-removed-is-edited-test
  (is (= #{3 4 5} (edited-forms/edited-lines path two-forms
                                             (str/replace two-forms "(defn b" "^:private\n(defn b") #{}))
      "a removed ^:private")
  (is (= #{3 4 5} (edited-forms/edited-lines path two-forms
                                             (str/replace two-forms "(defn b" "#_\n(defn b") #{}))
      "a removed #_"))

(deftest ^{:stratum 1} removing-a-whole-form-above-leaves-the-next-unedited-test
  (is (= #{} (edited-forms/edited-lines path two-forms
                                        (str/replace two-forms "(defn b" "#_(def unused 1)\n(defn b") #{}))
      "a removed discarded form")
  (is (= #{} (edited-forms/edited-lines path two-forms
                                        (str/replace two-forms "(defn b" "^:private\n(def x 1)\n(defn b") #{}))
      "a removed metadata-prefixed form"))

(deftest ^{:stratum 1} an-unwrapped-comment-form-is-edited-test
  (is (= #{2 3 4} (edited-forms/edited-lines path "(ns demo.core)\n(defn f\n  []\n  1)\n"
                                             "(ns demo.core)\n(comment\n(defn f\n  []\n  1)\n)\n" #{}))))

(deftest ^{:stratum 1} a-pasted-copy-of-a-form-is-edited-test
  (is (= #{3} (edited-forms/edited-lines path "(ns demo.core)\n(def a 1)\n(def a 1)\n"
                                         "(ns demo.core)\n(def a 1)\n" #{3}))))

(deftest ^{:stratum 1} lines-outside-forms-count-only-when-added-test
  (is (= #{6} (edited-forms/edited-lines path source source #{6}))
      "an added comment line between forms")
  (is (= #{} (edited-forms/edited-lines path source source #{}))
      "an unchanged file"))

(deftest ^{:stratum 1} a-new-file-is-edited-whole-test
  (is (= #{1 2 3 4 5} (edited-forms/edited-lines path two-forms nil #{1 2 3 4 5}))))

(deftest ^{:stratum 1} an-unreadable-base-is-an-anomaly-test
  (is (anomaly/anomaly? (edited-forms/edited-lines path two-forms "(defn b\n" #{}))))
