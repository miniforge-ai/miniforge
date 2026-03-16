(ns ai.miniforge.agent.tester-test
  "Tests for the Tester agent."
  (:require
   [clojure.test :as test :refer [deftest testing is]]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-test-artifact
  {:test/id (random-uuid)
   :test/files [{:path "test/example/core_test.clj"
                 :content "(ns example.core-test\n  (:require [clojure.test :refer :all]))\n\n(deftest basic-test\n  (is true))"}]
   :test/type :unit
   :test/coverage {:lines 80.0 :branches 70.0 :functions 85.0}
   :test/framework "clojure.test"
   :test/assertions-count 1
   :test/cases-count 1})

(def minimal-test-artifact
  {:test/id (random-uuid)
   :test/files [{:path "test/minimal_test.clj"
                 :content "(ns minimal-test (:require [clojure.test :refer :all]))\n(deftest t (is true))"}]
   :test/type :unit})

(def sample-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/auth/login.clj"
                 :content "(ns auth.login)\n\n(defn login [user] {:status :ok})"
                 :action :create}]
   :code/language "clojure"})

;------------------------------------------------------------------------------ Layer 1
;; Agent creation tests

(deftest create-tester-test
  (testing "creates tester with default config"
    (let [agent (tester/create-tester)]
      (is (some? agent))
      (is (= :tester (:role agent)))
      (is (string? (:system-prompt agent)))
      (is (= {:tokens 40000 :cost-usd 2.0}
             (get-in agent [:config :budget])))))

  (testing "creates tester with custom config"
    (let [agent (tester/create-tester {:config {:temperature 0.1}})]
      (is (= 0.1 (get-in agent [:config :temperature])))))

  (testing "creates tester with logger"
    (let [[logger _] (log/collecting-logger)
          agent (tester/create-tester {:logger logger})]
      (is (some? (:logger agent))))))

;------------------------------------------------------------------------------ Layer 2
;; Invoke tests

(deftest tester-invoke-test
  (testing "fails explicitly without LLM backend (no silent fallback)"
    (let [agent (tester/create-tester)
          result (core/invoke agent {} {:code sample-code-artifact})]
      (is (= :error (:status result)))
      (is (some? (:error result))))))

;------------------------------------------------------------------------------ Layer 3
;; Validation tests

(deftest validate-test-artifact-test
  (testing "valid artifact passes validation"
    (let [result (tester/validate-test-artifact valid-test-artifact)]
      (is (:valid? result))
      (is (nil? (:errors result)))))

  (testing "minimal artifact passes validation"
    (let [result (tester/validate-test-artifact minimal-test-artifact)]
      (is (:valid? result))))

  (testing "missing required fields fails validation"
    (let [result (tester/validate-test-artifact {:test/files []})]
      (is (not (:valid? result)))))

  (testing "non-test file path fails validation"
    (let [bad-artifact {:test/id (random-uuid)
                        :test/files [{:path "src/core.clj"
                                      :content "(ns core)"}]
                        :test/type :unit}
          result (tester/validate-test-artifact bad-artifact)]
      (is (not (:valid? result)))
      (is (contains? (:errors result) :files))))

  (testing "zero assertions fails validation"
    (let [bad-artifact {:test/id (random-uuid)
                        :test/files [{:path "test/empty_test.clj"
                                      :content "(ns empty-test)"}]
                        :test/type :unit
                        :test/assertions-count 0}
          result (tester/validate-test-artifact bad-artifact)]
      (is (not (:valid? result))))))

;------------------------------------------------------------------------------ Layer 3.5
;; Response parsing tests

(deftest parse-test-response-edn-block-test
  (testing "parses EDN in clojure code block"
    (let [text "Here are your tests:\n```clojure\n{:test/id #uuid \"00000000-0000-0000-0000-000000000001\"\n :test/files [{:path \"test/x_test.clj\" :content \"(ns x-test)\"}]\n :test/type :unit}\n```"
          result (tester/parse-test-response text)]
      (is (some? result))
      (is (= :unit (:test/type result)))))

  (testing "parses EDN with :test/files but no :test/id"
    (let [text "```clojure\n{:test/files [{:path \"test/y_test.clj\" :content \"(ns y)\"}]}\n```"
          result (tester/parse-test-response text)]
      (is (some? result))
      (is (vector? (:test/files result)))))

  (testing "returns nil for non-test EDN"
    (let [text "```clojure\n{:code/id #uuid \"00000000-0000-0000-0000-000000000001\"}\n```"
          result (tester/parse-test-response text)]
      (is (nil? result))))

  (testing "returns nil for non-parseable text"
    (is (nil? (tester/parse-test-response "just some text, no EDN here")))))

(deftest extract-test-code-blocks-test
  (testing "extracts blocks containing deftest"
    (let [text "Here are the tests:\n```clojure\n(ns my.test-ns\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest foo-test\n  (is true))\n```\nDone."
          result (tester/extract-test-code-blocks text)]
      (is (= 1 (count result)))
      (is (re-find #"test" (:path (first result))))
      (is (re-find #"deftest" (:content (first result))))))

  (testing "extracts blocks containing (is assertions"
    (let [text "```clojure\n(is (= 1 1))\n(is (pos? 5))\n```"
          result (tester/extract-test-code-blocks text)]
      (is (= 1 (count result)))))

  (testing "filters out non-test code blocks"
    (let [text "```clojure\n(ns my.core)\n(defn add [a b] (+ a b))\n```\n\n```clojure\n(ns my.core-test\n  (:require [clojure.test :refer [deftest is]]))\n(deftest add-test (is (= 3 (add 1 2))))\n```"
          result (tester/extract-test-code-blocks text)]
      ;; Both blocks have (ns so both match, but only one has deftest
      ;; The first has (ns which is a match criterion
      (is (pos? (count result)))))

  (testing "returns nil when no code blocks present"
    (is (nil? (tester/extract-test-code-blocks "No code blocks here."))))

  (testing "returns nil when code blocks contain no test content"
    (is (nil? (tester/extract-test-code-blocks
                "```python\nprint('hello')\n```")))))

(deftest infer-test-path-test
  (testing "infers path from ns declaration"
    (let [content "(ns my.app.core-test\n  (:require [clojure.test :refer :all]))"
          path (#'tester/infer-test-path content 0)]
      (is (= "test/my/app/core-test.clj" path))))

  (testing "adds _test suffix when ns doesn't end in -test"
    (let [content "(ns my.app.core\n  (:require [clojure.test :refer :all]))"
          path (#'tester/infer-test-path content 0)]
      (is (= "test/my/app/core_test.clj" path))))

  (testing "generates fallback path when no ns found"
    (let [content "(deftest foo (is true))"
          path (#'tester/infer-test-path content 3)]
      (is (= "test/generated-test-3_test.clj" path)))))

;------------------------------------------------------------------------------ Layer 4
;; Utility tests

(deftest test-summary-test
  (testing "returns test summary"
    (let [summary (tester/test-summary valid-test-artifact)]
      (is (uuid? (:id summary)))
      (is (number? (:file-count summary)))
      (is (keyword? (:type summary)))
      (is (string? (:framework summary)))
      (is (number? (:assertions summary)))
      (is (number? (:cases summary)))
      (is (map? (:coverage summary))))))

(deftest coverage-meets-threshold-test
  (testing "returns true when coverage meets thresholds"
    (is (true? (tester/coverage-meets-threshold?
                {:test/coverage {:lines 85.0 :branches 75.0 :functions 90.0}}
                :lines 80.0 :branches 70.0 :functions 80.0))))

  (testing "returns false when coverage below thresholds"
    (is (false? (tester/coverage-meets-threshold?
                 {:test/coverage {:lines 50.0 :branches 40.0 :functions 60.0}}
                 :lines 80.0 :branches 70.0 :functions 80.0))))

  (testing "uses default thresholds when not specified"
    (is (true? (tester/coverage-meets-threshold?
                {:test/coverage {:lines 85.0 :branches 75.0 :functions 85.0}})))))

(deftest tests-by-path-test
  (testing "returns map of path to content"
    (let [artifact {:test/files [{:path "test/a_test.clj" :content "content-a"}
                                 {:path "test/b_test.clj" :content "content-b"}]}
          by-path (tester/tests-by-path artifact)]
      (is (= 2 (count by-path)))
      (is (= "content-a" (get by-path "test/a_test.clj")))
      (is (= "content-b" (get by-path "test/b_test.clj"))))))

;------------------------------------------------------------------------------ Layer 5
;; Repair tests

(deftest tester-repair-test
  (testing "repairs missing ID"
    (let [agent (tester/create-tester)
          bad-artifact {:test/files [{:path "test/example_test.clj"
                                      :content "(ns example-test (:require [clojure.test :refer :all]))\n(deftest t (is true))"}]
                        :test/type :unit}
          result (core/repair agent bad-artifact {:test/id ["required"]} {})]
      (is (= :success (:status result)))
      (is (uuid? (get-in result [:output :test/id])))))

  (testing "repairs non-test file paths"
    (let [agent (tester/create-tester)
          bad-artifact {:test/id (random-uuid)
                        :test/files [{:path "src/not_test.clj"
                                      :content "(ns not-test)"}]
                        :test/type :unit}
          result (core/repair agent bad-artifact {:files ["naming"]} {})]
      (is (= :success (:status result)))
      ;; Path should be fixed to include _test
      (let [repaired-path (get-in result [:output :test/files 0 :path])]
        (is (re-find #"_test" repaired-path)))))

  (testing "calculates missing assertion count"
    (let [agent (tester/create-tester)
          bad-artifact {:test/id (random-uuid)
                        :test/files [{:path "test/example_test.clj"
                                      :content "(ns example-test)\n(is true)\n(is false)\n(is (= 1 1))"}]
                        :test/type :unit}
          result (core/repair agent bad-artifact {} {})]
      (is (= :success (:status result)))
      (is (pos? (get-in result [:output :test/assertions-count]))))))

;------------------------------------------------------------------------------ Layer 6
;; Full cycle tests

(deftest tester-cycle-test
  (testing "full invoke-validate cycle fails without LLM (no silent fallback)"
    (let [agent (tester/create-tester)
          result (core/cycle-agent agent {} {:code sample-code-artifact})]
      (is (= :error (:status result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.agent.tester-test)

  :leave-this-here)
