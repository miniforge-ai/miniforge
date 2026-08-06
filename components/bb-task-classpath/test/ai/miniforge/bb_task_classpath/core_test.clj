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
(ns ai.miniforge.bb-task-classpath.core-test
  (:require [ai.miniforge.bb-task-classpath.core :as sut]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; Mirrors the real bb.edn shape: top-level :requires/:init keyword
;; entries alongside symbol-keyed tasks, root :paths/:deps, and a task
;; carrying :extra-paths + :extra-deps + :requires.
(def ^{:stratum 0} sample-bb-edn
  '{:paths ["tasks"]
    :deps  {ai.miniforge/bb-utils {:local/root "projects/bb-utils"}}
    :tasks
    {:requires ([babashka.fs :as fs])
     :init     (do)
     lint:clj  {:requires ([lint])
                :task (lint/clj-staged)}
     mcp-context-server
     {:extra-paths ["bases/mcp-context-server/src"
                    "components/codex/src"]
      :extra-deps  {cheshire/cheshire {:mvn/version "5.13.0"}}
      :requires    ([ai.miniforge.mcp-context-server.main :as mcp])
      :task        (apply mcp/-main *command-line-args*)}
     extract:artifact
     {:extra-paths ["components/evidence-bundle/src"]
      :requires    ([ai.miniforge.evidence-bundle.extraction-bulk :as eb]
                    [babashka.fs :as fs])
      :task        (eb/extract-artifact-from-file nil)}}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} check-specs-test
  (let [specs (sut/check-specs sample-bb-edn)]
    (testing "only tasks with both :extra-paths and :requires are checked"
      (is (= '[extract:artifact mcp-context-server] (mapv :task specs))))
    (testing "root paths precede the task's extra paths"
      (is (= ["tasks" "bases/mcp-context-server/src" "components/codex/src"]
             (:paths (second specs)))))
    (testing "root deps merge with the task's extra deps"
      (is (= '{ai.miniforge/bb-utils {:local/root "projects/bb-utils"}
               cheshire/cheshire {:mvn/version "5.13.0"}}
             (:deps (second specs)))))
    (testing "entry namespaces come from each libspec head"
      (is (= '[ai.miniforge.evidence-bundle.extraction-bulk babashka.fs]
             (:namespaces (first specs)))))))

(deftest ^{:stratum 1} load-program-test
  (let [spec  (second (sut/check-specs sample-bb-edn))
        forms (read-string (str "[" (sut/load-program spec) "]"))]
    (testing "adds the merged deps first"
      (is (= (list 'babashka.deps/add-deps (list 'quote {:deps (:deps spec)}))
             (first forms))))
    (testing "adds the full path list with the child's path separator"
      (is (= (list 'babashka.classpath/add-classpath
                   (list 'clojure.string/join
                         '(System/getProperty "path.separator")
                         (:paths spec)))
             (second forms))))
    (testing "requires every entry namespace"
      (is (= '(require (quote ai.miniforge.mcp-context-server.main))
             (nth forms 2))))))
