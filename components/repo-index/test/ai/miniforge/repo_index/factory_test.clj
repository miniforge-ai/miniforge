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

(ns ai.miniforge.repo-index.factory-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.repo-index.factory :as sut]))

;------------------------------------------------------------------------------ Layer 1
;; ->file-record

(deftest file-record-shape-test
  (testing "->file-record carries every documented key with the supplied value"
    (let [r (sut/->file-record "src/a.clj" "abc1234" 1024 42 "clojure" false)]
      (is (= "src/a.clj" (:path r)))
      (is (= "abc1234"   (:blob-sha r)))
      (is (= 1024        (:size r)))
      (is (= 42          (:lines r)))
      (is (= "clojure"   (:language r)))
      (is (false?        (:generated? r))))))

(deftest file-record-allows-nil-language-test
  (testing "Language may be nil for files of unknown type"
    (let [r (sut/->file-record "f.bin" "abc1234" 0 0 nil false)]
      (is (nil? (:language r))))))

;------------------------------------------------------------------------------ Layer 1
;; ->repo-index

(deftest repo-index-shape-test
  (testing "->repo-index computes :file-count, :total-lines, and stamps :indexed-at"
    (let [files [(sut/->file-record "a.clj" "abc1234" 1 10 "clojure" false)
                 (sut/->file-record "b.py"  "def5678" 2 20 "python"  false)]
          idx (sut/->repo-index "tree-sha-aaa" "/repo" files {"clojure" 1 "python" 1})]
      (is (= "tree-sha-aaa"  (:tree-sha idx)))
      (is (= "/repo"         (:repo-root idx)))
      (is (= files           (:files idx)))
      (is (= 2               (:file-count idx)))
      (is (= 30              (:total-lines idx)))
      (is (= {"clojure" 1 "python" 1} (:languages idx)))
      (is (inst? (:indexed-at idx))))))

(deftest repo-index-empty-test
  (testing "Empty entries produces zero counts but still stamps :indexed-at"
    (let [idx (sut/->repo-index "tree-sha-aaa" "/repo" [] {})]
      (is (= 0 (:file-count idx)))
      (is (= 0 (:total-lines idx)))
      (is (= {} (:languages idx)))
      (is (inst? (:indexed-at idx))))))

;------------------------------------------------------------------------------ Layer 1
;; ->repo-map-entry

(deftest repo-map-entry-from-file-record-test
  (testing "->repo-map-entry projects path/lang/lines/size from a file record;
            :blob-sha and :generated? are dropped"
    (let [fr (sut/->file-record "src/x.clj" "abc1234" 100 5 "clojure" false)
          e  (sut/->repo-map-entry fr)]
      (is (= "src/x.clj" (:path e)))
      (is (= "clojure"   (:lang e)))
      (is (= 5           (:lines e)))
      (is (= 100         (:size e)))
      (is (not (contains? e :blob-sha)))
      (is (not (contains? e :generated?))))))

(deftest repo-map-entry-renames-language-to-lang-test
  (testing "FileRecord's :language becomes RepoMapEntry's :lang (note rename)"
    (is (= "rust" (:lang (sut/->repo-map-entry
                          (sut/->file-record "f.rs" "abc1234" 1 1 "rust" false)))))))

;------------------------------------------------------------------------------ Layer 1
;; ->repo-map-slice

(deftest repo-map-slice-shape-test
  (testing "->repo-map-slice carries every supplied positional argument as a key"
    (let [s (sut/->repo-map-slice "tree-sha" [{:path "a"}] "## map" 100 1 true 50)]
      (is (= "tree-sha"   (:tree-sha s)))
      (is (= [{:path "a"}] (:entries s)))
      (is (= "## map"     (:text s)))
      (is (= 100          (:total-files s)))
      (is (= 1            (:shown-files s)))
      (is (true?          (:truncated? s)))
      (is (= 50           (:token-estimate s))))))

;------------------------------------------------------------------------------ Layer 1
;; ->file-content

(deftest file-content-shape-test
  (testing "->file-content carries the four documented keys"
    (let [c (sut/->file-content "src/a.clj" "(ns a)" 1 false)]
      (is (= "src/a.clj" (:path c)))
      (is (= "(ns a)"    (:content c)))
      (is (= 1           (:lines c)))
      (is (false?        (:truncated? c))))))

;------------------------------------------------------------------------------ Layer 1
;; ->render-acc / render-acc-with

(deftest render-acc-initial-shape-test
  (testing "->render-acc returns the initial empty accumulator"
    (is (= {:text "" :tokens 0 :shown 0 :truncated? false}
           (sut/->render-acc)))))

(deftest render-acc-with-overrides-test
  (testing "render-acc-with produces an accumulator with the given fields"
    (is (= {:text "x" :tokens 5 :shown 1 :truncated? true}
           (sut/render-acc-with "x" 5 1 true)))))

;------------------------------------------------------------------------------ Layer 1
;; ->snippet / ->search-hit

(deftest snippet-shape-test
  (testing "->snippet carries start-line, end-line, and text"
    (is (= {:start-line 10 :end-line 12 :text "hit"}
           (sut/->snippet 10 12 "hit")))))

(deftest search-hit-shape-test
  (testing "->search-hit carries path, score, and snippets"
    (let [snip (sut/->snippet 1 1 "x")
          hit  (sut/->search-hit "src/a.clj" 0.95 [snip])]
      (is (= "src/a.clj" (:path hit)))
      (is (= 0.95        (:score hit)))
      (is (= [snip]      (:snippets hit))))))

;------------------------------------------------------------------------------ Layer 1
;; ->doc-entry / ->inverted-index / ->search-index

(deftest doc-entry-shape-test
  (testing "->doc-entry stores path, token-count, term-freqs, and content"
    (let [entry (sut/->doc-entry "src/a.clj" 5 {"a" 2 "b" 3} "(ns a)")]
      (is (= "src/a.clj"   (:path entry)))
      (is (= 5             (:token-count entry)))
      (is (= {"a" 2 "b" 3} (:term-freqs entry)))
      (is (= "(ns a)"      (:content entry))))))

(deftest inverted-index-empty-test
  (testing "->inverted-index returns the documented empty shape"
    (is (= {:term->doc-ids {} :doc-freq {}} (sut/->inverted-index)))))

(deftest search-index-shape-test
  (testing "->search-index aggregates docs, corpus stats, and posting lists"
    (let [idx (sut/->search-index {"a.clj" {:path "a.clj"}} 1 5.0
                                  {"foo" #{"a.clj"}} {"foo" 1})]
      (is (= 1            (:doc-count idx)))
      (is (= 5.0          (:avg-doc-length idx)))
      (is (= {"foo" #{"a.clj"}} (:term->doc-ids idx)))
      (is (= {"foo" 1}    (:doc-freq idx))))))
