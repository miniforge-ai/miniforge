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
(ns ai.miniforge.gate.messages-test
  "Every gate message key the gate sources can render has a catalog entry.

   A missing key does not throw: the translator renders the bare key name
   (\"skipped\", \"description\") and warns once on stderr. A gate result
   carrying a key name instead of a sentence reads as a word, not as a
   defect, so this test makes the gap fail loudly instead.

   Two collectors, because keys reach the translator two ways:
   - in the call itself: (t :k), and selected inline, (t (if c :a :b)).
     A reader walk takes every keyword literal in a translator call's
     first argument.
   - through a data table: opsv's gate-copy maps each gate to its
     :description / :failure / :remediation keys, and the call site
     passes a looked-up value. The test reads that table from the
     namespace itself, so the inventory is the one the gate renders."
  (:require [ai.miniforge.gate.opsv.core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} catalog
  [resource section]
  (-> (io/resource resource) slurp edn/read-string (get section)))

(defn- ^{:stratum 0} gate-sources
  "The gate component's .clj sources, found next to a known namespace on
   the classpath."
  []
  (let [root (-> (io/resource "ai/miniforge/gate/registry.clj") io/file .getParentFile)]
    (->> (file-seq root)
         (filter #(.endsWith (.getName ^java.io.File %) ".clj")))))

(def ^{:stratum 0} ^:private permissive-resolver
  ;; Sources are read, not loaded: ::alias keywords resolve to a
  ;; placeholder instead of needing each file's ns aliases.
  (reify clojure.lang.LispReader$Resolver
    (currentNS [_] 'user)
    (resolveClass [_ s] s)
    (resolveAlias [_ s] s)
    (resolveVar [_ s] s)))

(defn- ^{:stratum 0} read-forms
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (binding [*read-eval* false
              *reader-resolver* permissive-resolver]
      (into [] (take-while #(not= ::eof %))
            (repeatedly #(read {:eof ::eof :read-cond :allow} r))))))

(def ^{:stratum 0} ^:private translators
  "Translator call heads -> which catalog they read."
  {'messages/t :user 'msg/t :user
   'messages/system-t :system 'msg/system-t :system})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} call-keys
  "[{:file :catalog :key}] for every namespace-qualified keyword literal
   in the first argument of every translator call in the gate sources.
   Catalog keys are all qualified (:gate-area/name); an unqualified
   keyword there is a lookup path, as in
   (t (get-in gate-copy [gate-id :description])), not a message key."
  []
  (for [f (gate-sources)
        form (read-forms f)
        node (tree-seq coll? seq form)
        :when (and (seq? node) (contains? translators (first node)))
        k (filter qualified-keyword? (tree-seq coll? seq (second node)))]
    {:file (.getName ^java.io.File f) :catalog (translators (first node)) :key k}))

(defn- ^{:stratum 1} opsv-table-keys
  "[{:file :catalog :key}] for every message key in opsv's gate-copy."
  []
  (for [[gate copy] @#'ai.miniforge.gate.opsv.core/gate-copy
        field [:description :failure :remediation]]
    {:file (str "opsv/core.clj gate-copy " gate) :catalog :user :key (get copy field)}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} every-renderable-key-has-a-catalog-entry
  (let [catalogs {:user   (catalog "config/gate/messages/en-US.edn" :gate/messages)
                  :system (catalog "config/gate/messages/system.edn" :gate/system)}
        calls    (call-keys)
        table    (opsv-table-keys)]
    (testing "the collectors see the sources (an empty scan would pass vacuously)"
      (is (< 60 (count calls)))
      (is (= 18 (count table)) "six opsv gates x three copy fields"))
    (testing "keys selected inline in a call are collected"
      (is (some #(= :tests-pass/failed-one (:key %)) calls)
          "(t (if (= 1 n) :tests-pass/failed-one :tests-pass/failed))"))
    (testing "each key resolves in the catalog its translator reads"
      (doseq [{:keys [file catalog key]} (concat calls table)]
        (is (string? (get-in catalogs [catalog key]))
            (str file " renders " key " from the " (name catalog)
                 " catalog, which has no entry"))))))
