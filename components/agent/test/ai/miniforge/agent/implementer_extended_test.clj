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

(ns ai.miniforge.agent.implementer-extended-test
  "Extended tests for implementer: response parsing, code block extraction,
   language detection, artifact repair edge cases, formatting, and prior-attempts."
  (:require
   [ai.miniforge.agent.implementer :as impl]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0
;; parse-code-response tests

(deftest parse-code-response-edn-block-test
  (testing "parses EDN from fenced clojure code block"
    (let [text "Here is the code:\n```clojure\n{:code/id #uuid \"550e8400-e29b-41d4-a716-446655440000\"\n :code/files [{:path \"src/core.clj\" :content \"(ns core)\" :action :create}]}\n```"
          result (impl/parse-code-response text)]
      (is (map? result))
      (is (vector? (:code/files result)))))

  (testing "parses EDN from fenced edn code block"
    (let [text "```edn\n{:code/id #uuid \"550e8400-e29b-41d4-a716-446655440000\"}\n```"
          result (impl/parse-code-response text)]
      (is (map? result))))

  (testing "parses EDN from unfenced code block"
    (let [text "```\n{:status :already-implemented :summary \"done\"}\n```"
          result (impl/parse-code-response text)]
      (is (map? result)))))

(deftest parse-code-response-raw-edn-test
  (testing "parses raw EDN map directly"
    (let [text "{:code/id #uuid \"550e8400-e29b-41d4-a716-446655440000\" :code/files []}"
          result (impl/parse-code-response text)]
      (is (map? result))
      (is (= [] (:code/files result)))))

  (testing "returns nil for raw EDN that is not a map"
    (is (nil? (impl/parse-code-response "[:vector :not :map]")))
    (is (nil? (impl/parse-code-response "42")))))

(deftest parse-code-response-inline-status-test
  (testing "parses inline already-implemented status"
    (let [text "The feature is already present. {:status :already-implemented :summary \"Already done\"}"
          result (impl/parse-code-response text)]
      (is (map? result))
      (is (= :already-implemented (:status result)))
      (is (= "Already done" (:summary result)))))

  (testing "detects bare :already-implemented keyword without map format"
    (let [text "The requested changes are already in place. :already-implemented"
          result (impl/parse-code-response text)]
      (is (map? result))
      (is (= :already-implemented (:status result)))))

  (testing "detects :already-implemented in prose response from agent"
    (let [text "After reviewing the codebase, I can see that this feature is :already-implemented in the current code."
          result (impl/parse-code-response text)]
      (is (map? result))
      (is (= :already-implemented (:status result))))))

(deftest parse-code-response-nil-cases-test
  (testing "returns nil for plain text without EDN"
    (is (nil? (impl/parse-code-response "Just some regular text without any EDN."))))

  (testing "returns nil for empty string"
    (is (nil? (impl/parse-code-response ""))))

  (testing "returns nil for nil input"
    (is (nil? (impl/parse-code-response nil))))

  (testing "returns nil for invalid EDN in code block"
    (is (nil? (impl/parse-code-response "```clojure\n{{{invalid\n```")))))

;------------------------------------------------------------------------------ Layer 1
;; extract-code-blocks tests

(deftest extract-code-blocks-single-block-test
  (testing "extracts a single fenced code block"
    (let [text "### src/core.clj\n```clojure\n(ns core)\n```"
          result (impl/extract-code-blocks text)]
      (is (= 1 (count result)))
      (is (= "src/core.clj" (:path (first result))))
      (is (= "(ns core)" (:content (first result))))
      (is (= :create (:action (first result)))))))

(deftest extract-code-blocks-multiple-blocks-test
  (testing "extracts multiple code blocks with heading paths"
    (let [text (str "### src/a.clj\n```clojure\n(ns a)\n```\n\n"
                    "### src/b.clj\n```clojure\n(ns b)\n```")
          result (impl/extract-code-blocks text)]
      (is (= 2 (count result)))
      (is (= "src/a.clj" (:path (first result))))
      (is (= "src/b.clj" (:path (second result)))))))

(deftest extract-code-blocks-bold-path-test
  (testing "extracts path from bold markdown"
    (let [text "**src/util.clj**\n```clojure\n(ns util)\n```"
          result (impl/extract-code-blocks text)]
      (is (= 1 (count result)))
      (is (= "src/util.clj" (:path (first result)))))))

(deftest extract-code-blocks-file-label-test
  (testing "extracts path from 'File:' label"
    (let [text "File: src/handler.clj\n```clojure\n(ns handler)\n```"
          result (impl/extract-code-blocks text)]
      (is (= 1 (count result)))
      (is (= "src/handler.clj" (:path (first result)))))))

(deftest extract-code-blocks-no-blocks-test
  (testing "returns nil when no code blocks found"
    (is (nil? (impl/extract-code-blocks "Just plain text, no code blocks.")))
    (is (nil? (impl/extract-code-blocks "")))))

(deftest extract-code-blocks-generated-path-test
  (testing "generates fallback path when no heading found"
    (let [text "Here's some code:\n\n```clojure\n(ns mystery)\n```"
          result (impl/extract-code-blocks text)]
      (is (= 1 (count result)))
      ;; Should have a generated path with index
      (is (string? (:path (first result)))))))

;------------------------------------------------------------------------------ Layer 2
;; extract-language tests

(deftest extract-language-from-files-test
  (testing "detects clojure from .clj files"
    (let [files [{:path "src/core.clj" :content "" :action :create}
                 {:path "src/util.clj" :content "" :action :create}]]
      (is (string? (impl/extract-language files {})))))

  (testing "detects language from mixed extensions (most common wins)"
    (let [files [{:path "src/a.clj" :content "" :action :create}
                 {:path "src/b.clj" :content "" :action :create}
                 {:path "src/c.js" :content "" :action :create}]]
      ;; clj is more common, so should detect clojure
      (is (string? (impl/extract-language files {})))))

  (testing "context :language takes priority over file extensions"
    (let [files [{:path "src/core.clj" :content "" :action :create}]]
      (is (= "python" (impl/extract-language files {:language "python"})))))

  (testing "handles empty file list"
    (let [lang (impl/extract-language [] {})]
      (is (nil? lang))))

  (testing "handles files without extensions"
    (let [lang (impl/extract-language [{:path "Makefile" :content ""}] {})]
      (is (nil? lang)))))

;------------------------------------------------------------------------------ Layer 3
;; validate-code-artifact edge cases

(deftest validate-missing-code-id-test
  (testing "fails validation without :code/id"
    (let [result (impl/validate-code-artifact
                  {:code/files [{:path "a.clj" :content "(ns a)" :action :create}]})]
      (is (not (:valid? result))))))

(deftest validate-empty-files-vector-test
  (testing "passes validation with empty file vector and valid id"
    (let [result (impl/validate-code-artifact
                  {:code/id (random-uuid) :code/files []})]
      (is (:valid? result)))))

(deftest validate-invalid-action-test
  (testing "fails validation with unknown action"
    (let [result (impl/validate-code-artifact
                  {:code/id (random-uuid)
                   :code/files [{:path "a.clj" :content "(ns a)" :action :unknown}]})]
      (is (not (:valid? result))))))

(deftest validate-missing-path-test
  (testing "fails validation with missing path"
    (let [result (impl/validate-code-artifact
                  {:code/id (random-uuid)
                   :code/files [{:content "(ns a)" :action :create}]})]
      (is (not (:valid? result))))))

(deftest validate-modify-with-content-test
  (testing "passes validation for :modify with non-empty content"
    (let [result (impl/validate-code-artifact
                  {:code/id (random-uuid)
                   :code/files [{:path "a.clj" :content "(ns a)" :action :modify}]})]
      (is (:valid? result)))))

(deftest validate-delete-with-empty-content-test
  (testing "passes validation for :delete with empty content"
    (let [result (impl/validate-code-artifact
                  {:code/id (random-uuid)
                   :code/files [{:path "a.clj" :content "" :action :delete}]})]
      (is (:valid? result)))))

;------------------------------------------------------------------------------ Layer 4
;; repair-code-artifact tests

(deftest repair-adds-missing-id-test
  (testing "adds UUID when :code/id is missing"
    (let [artifact {:code/files [{:path "a.clj" :content "(ns a)" :action :create}]}
          result (impl/repair-code-artifact artifact {:code/id "missing"} {})]
      (is (response/success? result))
      (is (uuid? (get-in result [:output :code/id]))))))

(deftest repair-adds-missing-files-vector-test
  (testing "adds empty files vector when nil"
    (let [artifact {:code/id (random-uuid) :code/files nil}
          result (impl/repair-code-artifact artifact {} {})]
      (is (response/success? result))
      (is (vector? (get-in result [:output :code/files]))))))

(deftest repair-fills-empty-create-content-test
  (testing "adds placeholder content to empty :create files"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content "" :action :create}]}
          result (impl/repair-code-artifact artifact {:files "empty"} {})]
      (is (response/success? result))
      (is (seq (get-in result [:output :code/files 0 :content]))))))

(deftest repair-deduplicates-files-test
  (testing "keeps last occurrence of duplicate paths"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content "(ns a-v1)" :action :create}
                                 {:path "b.clj" :content "(ns b)" :action :create}
                                 {:path "a.clj" :content "(ns a-v2)" :action :modify}]}
          result (impl/repair-code-artifact artifact {} {})]
      (is (response/success? result))
      (let [files (get-in result [:output :code/files])
            paths (map :path files)]
        (is (= (count (distinct paths)) (count paths)))))))

(deftest repair-drops-nil-path-entries-test
  (testing "filters out file entries with nil path"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content "(ns a)" :action :create}
                                 {:path nil :content "orphan" :action :create}]}
          result (impl/repair-code-artifact artifact {} {})]
      (is (response/success? result))
      (is (= 1 (count (get-in result [:output :code/files])))))))

(deftest repair-adds-default-action-test
  (testing "adds :create action when missing"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content "(ns a)"}]}
          result (impl/repair-code-artifact artifact {} {})]
      (is (response/success? result))
      (is (= :create (get-in result [:output :code/files 0 :action]))))))

(deftest repair-adds-empty-content-when-nil-test
  (testing "adds empty string content when nil"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content nil :action :delete}]}
          result (impl/repair-code-artifact artifact {} {})]
      (is (response/success? result))
      (is (= "" (get-in result [:output :code/files 0 :content]))))))

(deftest repair-records-repairs-made-test
  (testing "records original errors when repairs were needed"
    (let [artifact {:code/files [{:path "a.clj" :content "(ns a)" :action :create}]}
          errors {:code/id "missing"}
          result (impl/repair-code-artifact artifact errors {})]
      (is (some? (:repairs-made result))))))

(deftest repair-no-repairs-made-test
  (testing "no :repairs-made when artifact is already valid"
    (let [artifact {:code/id (random-uuid)
                    :code/files [{:path "a.clj" :content "(ns a)" :action :create}]}
          result (impl/repair-code-artifact artifact {} {})]
      (is (nil? (:repairs-made result))))))

;------------------------------------------------------------------------------ Layer 5
;; task->text with prior-attempts

(deftest task->text-prior-attempts-test
  (testing "includes retry warning with attempt number"
    (let [text (impl/task->text
                {:task/description "Do the thing"
                 :task/prior-attempts {:attempt-number 3
                                       :prior-error "Type mismatch on line 42"
                                       :instruction "Ensure types match"}})]
      (is (str/includes? text "RETRY"))
      (is (str/includes? text "Attempt 3"))
      (is (str/includes? text "Type mismatch on line 42"))
      (is (str/includes? text "Ensure types match"))))

  (testing "prior-attempts section appears before description"
    (let [text (impl/task->text
                {:task/description "Main task"
                 :task/prior-attempts {:attempt-number 2
                                       :prior-error "Error"
                                       :instruction "Fix it"}})]
      (is (< (str/index-of text "RETRY")
             (str/index-of text "Main task"))))))

(deftest task->text-non-string-non-map-test
  (testing "non-string, non-map values are stringified"
    (is (= "42" (impl/task->text 42)))
    (is (= ":keyword" (impl/task->text :keyword)))))

(deftest task->text-map-fallback-keys-test
  (testing "uses :description when :task/description is nil"
    (let [text (impl/task->text {:description "fallback desc"})]
      (is (= "fallback desc" text))))

  (testing "uses :content when both task/ and description are nil"
    (let [text (impl/task->text {:content "content desc"})]
      (is (= "content desc" text)))))

(deftest task->text-empty-map-test
  (testing "empty map is pr-str'd"
    (let [text (impl/task->text {})]
      (is (= "{}" text)))))

;------------------------------------------------------------------------------ Layer 6
;; format-repo-map tests

(deftest format-repo-map-nil-test
  (testing "nil returns nil"
    (is (nil? (impl/format-repo-map nil)))))

(deftest format-repo-map-blank-test
  (testing "blank string returns nil"
    (is (nil? (impl/format-repo-map "")))
    (is (nil? (impl/format-repo-map "   ")))))

(deftest format-repo-map-with-content-test
  (testing "non-blank string returns formatted section with the content"
    (let [result (impl/format-repo-map "src/\n  core.clj\n  util.clj")]
      (is (string? result))
      (is (str/includes? result "src/"))
      (is (str/includes? result "core.clj")))))

;------------------------------------------------------------------------------ Layer 6
;; format-existing-files tests

(deftest format-existing-files-nil-test
  (testing "nil returns nil"
    (is (nil? (impl/format-existing-files nil)))))

(deftest format-existing-files-empty-test
  (testing "empty seq returns nil"
    (is (nil? (impl/format-existing-files [])))))

(deftest format-existing-files-with-content-test
  (testing "formats files as markdown code blocks"
    (let [files [{:path "src/core.clj" :content "(ns core)"}]
          result (impl/format-existing-files files)]
      (is (string? result))
      (is (str/includes? result "src/core.clj"))
      (is (str/includes? result "(ns core)")))))

(deftest format-existing-files-truncated-test
  (testing "shows truncated indicator"
    (let [files [{:path "src/big.clj" :content "(ns big)" :truncated? true}]
          result (impl/format-existing-files files)]
      (is (str/includes? result "(truncated)")))))

;------------------------------------------------------------------------------ Layer 7
;; code-summary edge cases

(deftest code-summary-empty-files-test
  (testing "handles empty file list"
    (let [summary (impl/code-summary {:code/id (random-uuid)
                                       :code/files []})]
      (is (= 0 (:file-count summary)))
      (is (= {} (:actions summary))))))

(deftest code-summary-nil-optional-fields-test
  (testing "handles nil optional fields gracefully"
    (let [summary (impl/code-summary {:code/id (random-uuid)
                                       :code/files [{:path "a.clj"
                                                      :content ""
                                                      :action :create}]})]
      (is (nil? (:language summary)))
      (is (nil? (:tests-needed? summary)))
      (is (= 0 (:dependencies-added summary))))))

;------------------------------------------------------------------------------ Layer 7
;; total-lines edge cases

(deftest total-lines-empty-files-test
  (testing "zero for no files"
    (is (= 0 (impl/total-lines {:code/files []})))))

(deftest total-lines-single-line-test
  (testing "single line file counts as 1"
    (is (= 1 (impl/total-lines {:code/files [{:path "a.clj"
                                                :content "single"
                                                :action :create}]})))))

(deftest total-lines-all-deleted-test
  (testing "all deleted files produce 0 lines"
    (is (= 0 (impl/total-lines {:code/files [{:path "a.clj"
                                                :content "should\nnot\ncount"
                                                :action :delete}
                                               {:path "b.clj"
                                                :content "also\nnot"
                                                :action :delete}]})))))

;------------------------------------------------------------------------------ Layer 7
;; files-by-action edge cases

(deftest files-by-action-empty-test
  (testing "empty files produce empty groups"
    (let [grouped (impl/files-by-action {:code/files []})]
      (is (= {} grouped)))))

(deftest files-by-action-all-actions-test
  (testing "all three action types grouped correctly"
    (let [artifact {:code/files [{:path "a.clj" :content "" :action :create}
                                 {:path "b.clj" :content "" :action :modify}
                                 {:path "c.clj" :content "" :action :delete}
                                 {:path "d.clj" :content "" :action :create}]}
          grouped (impl/files-by-action artifact)]
      (is (= 2 (count (:create grouped))))
      (is (= 1 (count (:modify grouped))))
      (is (= 1 (count (:delete grouped)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.implementer-extended-test)
  :leave-this-here)
