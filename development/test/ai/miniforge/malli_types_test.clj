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
(ns ai.miniforge.malli-types-test
  "clj-kondo types come from declared function schemas, the scan finds
   brick sources that declare them, and the check compares data."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.malli-types :as malli-types]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} gate-name?
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [k]
  (keyword? k))

(defn ^{:stratum 0} temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "malli-types-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn ^{:stratum 0} write-source!
  [base path text]
  (let [f (io/file base path)]
    (io/make-parents f)
    (spit f text)))

(defn ^{:stratum 0} delete-tree!
  [dir]
  (doseq [f (reverse (file-seq dir))]
    (.delete ^java.io.File f)))

(deftest ^{:stratum 0} declares-function-schema-matches-every-declaration-form-test
  (testing "metadata, and => under any alias, spacing, or referred bare"
    (doseq [text ["(defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                  "(m/=> f [:=> [:cat :int] :int])"
                  "(malli.core/=> f [:=> [:cat :int] :int])"
                  "(mc/=> f [:=> [:cat :int] :int])"
                  "( m/=>\n  f [:=> [:cat :int] :int])"
                  "(=> f [:=> [:cat :int] :int])"]]
      (is (malli-types/declares-function-schema? text) text)))
  (testing "a schema value that only uses :=> declares nothing"
    (is (not (malli-types/declares-function-schema? "(def S [:=> [:cat :int] :int])")))))

(deftest ^{:stratum 0} kondo-config-without-namespaces-test
  (testing "no declaring namespaces yields a config with no types, not an error"
    (is (empty? (get-in (malli-types/kondo-config []) [:linters :type-mismatch])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} schema-namespaces-scans-brick-src-trees-test
  (let [base (temp-dir)]
    (try
      (write-source! base "components/a/src/x/declares.clj"
                     "(ns x.declares)\n(m/=> f [:=> [:cat :int] :int])\n")
      (write-source! base "components/a/src/x/plain.clj"
                     "(ns x.plain)\n(defn g [] 1)\n")
      (write-source! base "components/a/test/x/declares_test.clj"
                     "(ns x.declares-test)\n(defn h {:malli/schema [:=> [:cat] :int]} [] 1)\n")
      (write-source! base "bases/b/src/y/meta.cljc"
                     "(ns y.meta)\n(defn h {:malli/schema [:=> [:cat] :int]} [] 1)\n")
      (testing "declaring src namespaces of components and bases; no test trees, no plain sources"
        (is (= '[x.declares y.meta] (malli-types/schema-namespaces (str base)))))
      (finally
        (delete-tree! base)))))

(deftest ^{:stratum 1} kondo-config-carries-declared-types-test
  (testing "a :malli/schema becomes a clj-kondo type-mismatch entry for its arity"
    (let [config (malli-types/kondo-config '[ai.miniforge.malli-types-test])]
      (is (= {:args [:keyword] :ret :boolean}
             (get-in config [:linters :type-mismatch :namespaces
                             'ai.miniforge.malli-types-test 'gate-name?
                             :arities 1]))))))

(deftest ^{:stratum 1} current?-compares-committed-data-test
  (let [base (temp-dir)
        path (str (io/file base ".clj-kondo" "types" "config.edn"))
        config (malli-types/kondo-config '[ai.miniforge.malli-types-test])]
    (try
      (testing "a missing file is not current"
        (is (not (malli-types/current? path config))))
      (malli-types/write-config! path config)
      (testing "the written config reads back as current"
        (is (malli-types/current? path config)))
      (testing "a config without those types is not current"
        (is (not (malli-types/current? path (malli-types/kondo-config [])))))
      (finally
        (delete-tree! base)))))
