;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.changed-files-test
  "Tests for the changed-files analysis scope, against a mock LLM."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.changed-files :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} analyze-rule-on-files-test
  (testing "judges only the supplied changed files, filters by glob + size"
    (let [rule    {:rule/id :std/test
                   :rule/title "Test Rule"
                   :rule/category "001"
                   :rule/severity :low
                   :rule/knowledge-content "Check it."
                   :rule/applies-to {:file-globs ["src/*.clj"]}}
          judged  (atom [])
          big     (apply str (repeat 60000 "x"))
          mock    (fn [_client request]
                    (swap! judged conj (-> request :messages first :content))
                    {:success true
                     :content "[{:line 1 :message \"semantic issue\"}]"})
          files   [{:path "src/core.clj" :content "(def x 1)"}
                   {:path "docs/readme.md" :content "skip — not a glob match"}
                   {:path "src/huge.clj" :content big}]
          result  (sut/analyze-rule-on-files :mock mock rule files)]
      (is (= 1 (:files-analyzed result)) "only src/core.clj matches glob + size")
      (is (= 1 (count (:violations result))))
      (is (= 1 (count @judged)))
      (is (re-find #"\(def x 1\)" (first @judged))
          "the judged file's content reached the prompt"))))

(deftest ^{:stratum 0} analyze-rules-on-files-batched-test
  (testing "one LLM call per file across many rules; violations distributed by rule-id"
    (let [rule-a {:rule/id :std/a :rule/title "A" :rule/category "001" :rule/severity :high
                  :rule/knowledge-content "no a" :rule/applies-to {:file-globs ["src/*.clj"]}}
          rule-b {:rule/id :std/b :rule/title "B" :rule/category "002" :rule/severity :high
                  :rule/knowledge-content "no b" :rule/applies-to {:file-globs ["src/*.clj"]}}
          calls  (atom 0)
          mock   (fn [_client _request]
                   (swap! calls inc)
                   {:success true
                    :content (str "[{:rule-id :std/a :line 1 :message \"a bad\"} "
                                  "{:rule-id :std/b :line 2 :message \"b bad\"}]")})
          files  [{:path "src/core.clj" :content "(def x 1)"}]
          result (sut/analyze-rules-on-files :mock mock [rule-a rule-b] files)]
      (is (= 1 @calls) "complete-fn actually invoked exactly once (not just the reported counter)")
      (is (= 1 (:calls result)) "one batched call for two rules on one file")
      (is (= 1 (:files-analyzed result)))
      (is (= 1 (count (get-in result [:by-rule :std/a]))))
      (is (= 1 (count (get-in result [:by-rule :std/b]))))
      (is (= :std/a (:rule/id (first (get-in result [:by-rule :std/a])))))))
  (testing "a rule whose glob does not match a file is not judged against it"
    (let [clj-rule {:rule/id :std/clj :rule/title "Clj" :rule/category "001" :rule/severity :low
                    :rule/knowledge-content "k" :rule/applies-to {:file-globs ["src/*.clj"]}}
          calls    (atom 0)
          mock     (fn [_client _request] (swap! calls inc) {:success true :content "[]"})
          files    [{:path "docs/readme.md" :content "text"}]
          result   (sut/analyze-rules-on-files :mock mock [clj-rule] files)]
      (is (= 0 @calls) "complete-fn never invoked when no rule applies to the file")
      (is (= 0 (:calls result)) "no applicable rule for the file -> no call"))))

(deftest ^{:stratum 0} analyze-rules-on-files-untagged-fails-closed-test
  (testing "an untagged violation is attributed to EVERY rule in the batch
            (fail-closed), never dropped — the model forgetting :rule-id must not
            silently lose a real finding"
    (let [rule-a {:rule/id :std/a :rule/title "A" :rule/category "001" :rule/severity :high
                  :rule/knowledge-content "k" :rule/applies-to {:file-globs ["src/*.clj"]}}
          rule-b {:rule/id :std/b :rule/title "B" :rule/category "002" :rule/severity :high
                  :rule/knowledge-content "k" :rule/applies-to {:file-globs ["src/*.clj"]}}
          mock   (fn [_client _request]
                   {:success true :content "[{:line 1 :message \"untagged finding\"}]"})
          result (sut/analyze-rules-on-files :mock mock [rule-a rule-b]
                                             [{:path "src/core.clj" :content "(def x 1)"}])]
      (is (= 1 (count (get-in result [:by-rule :std/a]))) "untagged attributed to rule-a")
      (is (= 1 (count (get-in result [:by-rule :std/b]))) "and to rule-b — fail-closed"))))
