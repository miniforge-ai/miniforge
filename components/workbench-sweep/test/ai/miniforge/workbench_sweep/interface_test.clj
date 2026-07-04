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

(ns ai.miniforge.workbench-sweep.interface-test
  (:require [ai.miniforge.workbench-sweep.core :as core]
            [ai.miniforge.workbench-sweep.interface :as sut]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private eval-fixture
  "A minimal but schema-valid supervisory PolicyEvaluation, written to a
   temp file; the stub command copies it to {out} the way a real
   `mf workflow execute` run would leave its evaluation."
  (pr-str
   {:policy-eval/id #uuid "00000000-0000-4000-8000-0000000000aa"
    :policy-eval/passed? false
    :policy-eval/packs-applied ["miniforge/foundations"]
    :policy-eval/violations
    [{:violation/rule-id :foundations/no-todo-fixme
      :violation/severity :medium
      :violation/category :style
      :violation/message "TODO left in src/sweep_test.clj"
      :violation/remediable? true}]
    :policy-eval/evaluated-at #inst "2026-07-04T00:00:00.000-00:00"}))

(defn- tmp-dir! [prefix]
  (str (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-eval! [dir]
  (let [f (io/file dir "source-eval.edn")]
    (spit f eval-fixture)
    (str f)))

(defn- base-config [out-dir eval-path]
  {:experiment-id "miniforge.policy.sweep-test"
   :out-dir out-dir
   :registry-version "2026.07.03.1"
   :replicates 2
   :command ["cp" eval-path "{out}"]
   :variants [{:label "chain-a"
               :workflow {:id "n2-standard" :version "1"}
               :prompt {:id "implement" :version "v7"}
               :model "claude-opus-4-8"
               :method "governed"
               :bindings {:chain "n2-standard"}}
              {:label "chain-b"
               :workflow {:id "n2-fast" :version "1"}
               :method "governed"}]})

(deftest template-substitution
  (is (= ["run" "n2" "/o/x.edn" "3"]
         (core/render-command ["run" "{chain}" "{out}" "{replicate}"]
                              {:chain "n2" :out "/o/x.edn" :replicate 3})))
  (is (thrown? clojure.lang.ExceptionInfo
               (core/render-command ["{nope}"] {:chain "n2"}))
      "unresolved placeholder refuses to run"))

(deftest sweep-executes-matrix-and-emits-replicate-safe-snapshots
  (let [work (tmp-dir! "wb-sweep")
        out-dir (str (io/file work "out"))
        eval-path (write-eval! work)
        {:keys [snapshots]} (sut/run-sweep! (base-config out-dir eval-path))
        parsed (mapv #(json/parse-string (slurp %) true) snapshots)]
    (is (= 4 (count snapshots)) "2 variants x 2 replicates")
    (testing "replicates share the label, differ in run ids"
      (let [by-label (group-by #(get-in % [:variant :label]) parsed)]
        (is (= #{"chain-a" "chain-b"} (set (keys by-label))))
        (is (every? #(= 2 (count %)) (vals by-label)))
        (is (= 4 (count (distinct (map :run_id parsed)))))
        (is (= 4 (count (distinct (map :snapshot_id parsed)))))))
    (testing "workflow/prompt axes ride every replicate"
      (let [a (first (filter #(= "chain-a" (get-in % [:variant :label])) parsed))]
        (is (= {:id "n2-standard" :version "1"} (get-in a [:variant :workflow])))
        (is (= {:id "implement" :version "v7"} (get-in a [:variant :prompt])))))
    (testing "provenance: eval digest + replicate number"
      (is (every? #(= 1 (count (:source_hashes %))) parsed))
      (is (= #{1 2} (set (map #(get-in % [:metadata :replicate])
                              (filter #(= "chain-a" (get-in % [:variant :label])) parsed))))))
    (testing "evaluations flow through the adapter"
      (is (every? #(pos? (count (:evaluations %))) parsed))
      (let [a (first parsed)
            todo (first (filter #(= "miniforge.policy.foundations.no-todo-fixme"
                                    (:state_var_id %))
                                (:evaluations a)))]
        (is (= "fail" (:status todo)) "the violated rule fails in every emitted snapshot")))))

(deftest stale-snapshots-are-cleared
  (let [work (tmp-dir! "wb-sweep-stale")
        out-dir (str (io/file work "out"))
        eval-path (write-eval! work)]
    (.mkdirs (io/file out-dir))
    (spit (io/file out-dir "stale.json") "{}")
    (sut/run-sweep! (assoc (base-config out-dir eval-path) :replicates 1))
    (is (not (.exists (io/file out-dir "stale.json")))
        "earlier sweep leftovers cannot contaminate the matrix")))

(deftest policy-eval-mode-skips-execution
  (let [work (tmp-dir! "wb-sweep-direct")
        out-dir (str (io/file work "out"))
        eval-path (write-eval! work)
        config {:experiment-id "e" :out-dir out-dir :registry-version "v"
                :variants [{:label "pre-run" :policy-eval eval-path}]}
        {:keys [snapshots]} (sut/run-sweep! config)]
    (is (= 1 (count snapshots)) "no :command needed when every variant is pre-run")))

(deftest failing-command-throws-with-exit-code
  (let [work (tmp-dir! "wb-sweep-fail")
        out-dir (str (io/file work "out"))
        config (assoc (base-config out-dir "/nonexistent") :replicates 1)]
    (is (thrown? clojure.lang.ExceptionInfo (sut/run-sweep! config)))))

(deftest config-validation-rejects-commandless-executable-variants
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/run-sweep! {:experiment-id "e" :out-dir "/tmp/x"
                                :registry-version "v"
                                :variants [{:label "no-source"}]}))))
