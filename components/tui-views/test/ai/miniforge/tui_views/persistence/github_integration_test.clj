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

(ns ai.miniforge.tui-views.persistence.github-integration-test
  "Integration tests for the GitHub PR fetch pipeline.

   Verifies the full path from dispatch-effect through handle-fetch-pr-diff
   to github/fetch-pr-diff-and-detail and msg/pr-diff-fetched construction.

   Covers acceptance criteria:
   AC1: Function accepts repo string + PR number, returns
        {:diff <string> :detail <map> :repo <string> :number <int>}
   AC2: Diff is unified diff text (GitHub API format)
   AC3: Detail includes :title, :body, :labels, :files
        (files have :path, :additions, :deletions)
   AC4: Graceful error handling — nil fields, never throws

   Also covers msg/pr-diff-fetched constructor and
   handle-fetch-pr-diff number coercion edge cases."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.msg :as msg]
   [babashka.process :as process]))

;; ---------------------------------------------------------------------------- Test data

(def ^:private multi-file-diff
  "A realistic multi-file unified diff with additions, deletions, renames."
  (str "diff --git a/src/core.clj b/src/core.clj\n"
       "index 1234567..abcdef0 100644\n"
       "--- a/src/core.clj\n"
       "+++ b/src/core.clj\n"
       "@@ -10,7 +10,12 @@\n"
       " (ns src.core)\n"
       " \n"
       "-(defn process [x] x)\n"
       "+(defn process\n"
       "+  \"Process input with validation.\"\n"
       "+  [x]\n"
       "+  {:pre [(some? x)]}\n"
       "+  (transform x))\n"
       " \n"
       "diff --git a/src/util.clj b/src/util.clj\n"
       "index 0000000..fedcba9\n"
       "--- a/src/util.clj\n"
       "+++ b/src/util.clj\n"
       "@@ -1,3 +1,5 @@\n"
       " (ns src.util)\n"
       "+(defn transform [x]\n"
       "+  (str x))\n"
       "diff --git a/test/core_test.clj b/test/core_test.clj\n"
       "new file mode 100644\n"
       "index 0000000..9876543\n"
       "--- /dev/null\n"
       "+++ b/test/core_test.clj\n"
       "@@ -0,0 +1,8 @@\n"
       "+(ns core-test\n"
       "+  (:require [clojure.test :refer [deftest is]]\n"
       "+            [src.core :as sut]))\n"
       "+\n"
       "+(deftest process-test\n"
       "+  (is (= \"42\" (sut/process 42))))\n"))

(def ^:private detail-with-all-fields
  "JSON with title, body, labels, and files — comprehensive."
  (str "{\"title\":\"Add input validation to process fn\","
       "\"body\":\"## Summary\\nAdds precondition checks and transforms.\\n\\n"
       "## Test plan\\n- Unit tests for process fn\","
       "\"labels\":[{\"name\":\"enhancement\"},{\"name\":\"needs-review\"}],"
       "\"files\":["
       "{\"path\":\"src/core.clj\",\"additions\":5,\"deletions\":1},"
       "{\"path\":\"src/util.clj\",\"additions\":2,\"deletions\":0},"
       "{\"path\":\"test/core_test.clj\",\"additions\":8,\"deletions\":0}"
       "]}"))

(def ^:private empty-pr-json
  "{\"title\":\"Empty PR\",\"body\":\"\",\"labels\":[],\"files\":[]}")

(defn- mock-shell-success [out]
  {:exit 0 :out out :err ""})

(defn- mock-shell-failure [exit err]
  {:exit exit :out "" :err err})

(defn- route-gh-command
  "Create a mock process/shell that routes based on gh subcommand.
   diff-response and detail-response are [exit-code output] pairs."
  [diff-response detail-response]
  (fn [_opts & args]
    (let [a (vec args)
          cmd (nth a 2)]
      (case cmd
        "diff" (if (zero? (first diff-response))
                 (mock-shell-success (second diff-response))
                 (mock-shell-failure (first diff-response) (second diff-response)))
        "view" (if (zero? (first detail-response))
                 (mock-shell-success (second detail-response))
                 (mock-shell-failure (first detail-response) (second detail-response)))))))

;; ---------------------------------------------------------------------------- msg/pr-diff-fetched unit tests

(deftest pr-diff-fetched-msg-structure-test
  (testing "produces :msg/pr-diff-fetched with pr-id, diff, detail"
    (let [m (msg/pr-diff-fetched ["r" 1] "patch" {:title "T"} nil)]
      (is (= :msg/pr-diff-fetched (first m)))
      (is (= ["r" 1] (:pr-id (second m))))
      (is (= "patch" (:diff (second m))))
      (is (= {:title "T"} (:detail (second m))))))

  (testing "omits :error key when error is nil"
    (let [[_ payload] (msg/pr-diff-fetched ["r" 1] "d" {} nil)]
      (is (not (contains? payload :error)))))

  (testing "includes :error key when error is truthy"
    (let [[_ payload] (msg/pr-diff-fetched ["r" 1] nil nil "fetch failed")]
      (is (contains? payload :error))
      (is (= "fetch failed" (:error payload)))))

  (testing "diff and detail can both be nil"
    (let [[_ payload] (msg/pr-diff-fetched ["r" 1] nil nil "err")]
      (is (nil? (:diff payload)))
      (is (nil? (:detail payload))))))

(deftest pr-diff-fetched-msg-always-vector-test
  (testing "always returns a two-element vector"
    (doseq [args [[["r" 1] "d" {:title "T"} nil]
                  [["r" 1] nil nil "err"]
                  [["r" 1] "" {} nil]
                  [["r" 1] nil {:title "X"} nil]]]
      (let [m (apply msg/pr-diff-fetched args)]
        (is (vector? m))
        (is (= 2 (count m)))
        (is (= :msg/pr-diff-fetched (first m)))
        (is (map? (second m)))))))

;; ---------------------------------------------------------------------------- Integration: handle-fetch-pr-diff

(deftest handle-fetch-pr-diff-full-success-pipeline-test
  (testing "full pipeline: dispatch → handle → github → msg, all fields present"
    (with-redefs [process/shell (route-gh-command [0 multi-file-diff]
                                                   [0 detail-with-all-fields])]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff
                                {:repo "acme/app" :number 99})]
        ;; AC1: correct message type
        (is (= :msg/pr-diff-fetched msg-type))
        ;; AC1: pr-id is [repo, coerced-number]
        (is (= ["acme/app" 99] (:pr-id payload)))

        ;; AC2: diff is unified diff text
        (is (string? (:diff payload)))
        (is (str/starts-with? (:diff payload) "diff --git"))
        (is (str/includes? (:diff payload) "@@"))

        ;; AC3: detail has minimum required fields
        (let [detail (:detail payload)]
          (is (map? detail))
          (is (= "Add input validation to process fn" (:title detail)))
          (is (string? (:body detail)))
          (is (sequential? (:labels detail)))
          (is (= #{"enhancement" "needs-review"}
                 (set (map :name (:labels detail)))))
          (is (= 3 (count (:files detail))))
          (is (every? #(contains? % :path) (:files detail))))

        ;; AC4: no error on success
        (is (nil? (:error payload)))))))

(deftest handle-fetch-pr-diff-string-number-coercion-test
  (testing "string PR number is coerced to long in pr-id"
    (with-redefs [process/shell (route-gh-command [0 "patch"] [0 empty-pr-json])]
      (let [[_ payload] (iface/handle-fetch-pr-diff
                         {:repo "org/repo" :number "456"})]
        (is (= ["org/repo" 456] (:pr-id payload)))
        (is (integer? (second (:pr-id payload))))))))

;; ---------------------------------------------------------------------------- Integration: partial failures

(deftest handle-fetch-pr-diff-diff-fails-detail-ok-test
  (testing "diff failure + detail success → no error, detail preserved"
    (with-redefs [process/shell (route-gh-command [1 "not found"]
                                                   [0 detail-with-all-fields])]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff
                                {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (nil? (:diff payload)))
        (is (map? (:detail payload)))
        (is (nil? (:error payload)))))))

(deftest handle-fetch-pr-diff-detail-fails-diff-ok-test
  (testing "detail failure + diff success → no error, diff preserved"
    (with-redefs [process/shell (route-gh-command [0 multi-file-diff]
                                                   [128 "auth failed"])]
      (let [[_ payload] (iface/handle-fetch-pr-diff
                         {:repo "r" :number 1})]
        (is (string? (:diff payload)))
        (is (str/starts-with? (:diff payload) "diff --git"))
        (is (nil? (:detail payload)))
        (is (nil? (:error payload)))))))

(deftest handle-fetch-pr-diff-both-fail-sets-error-test
  (testing "both diff and detail fail → error message set"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-failure 1 "nope"))]
      (let [[_ payload] (iface/handle-fetch-pr-diff
                         {:repo "r" :number 1})]
        (is (nil? (:diff payload)))
        (is (nil? (:detail payload)))
        (is (string? (:error payload)))))))

(deftest handle-fetch-pr-diff-exception-wraps-error-test
  (testing "exception from github layer is caught and wrapped"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (java.net.SocketTimeoutException. "Read timed out")))]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff
                                {:repo "r" :number 5})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["r" 5] (:pr-id payload)))
        (is (nil? (:diff payload)))
        (is (str/includes? (:error payload) "Read timed out"))))))

;; ---------------------------------------------------------------------------- Integration: dispatch-effect routing

(deftest dispatch-effect-routes-fetch-pr-diff-test
  (testing ":fetch-pr-diff effect type routes to handle-fetch-pr-diff"
    (with-redefs [process/shell (route-gh-command [0 "simple diff"]
                                                   [0 empty-pr-json])]
      (let [[msg-type payload] (iface/dispatch-effect nil {:type :fetch-pr-diff
                                                           :repo "org/lib"
                                                           :number 77})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["org/lib" 77] (:pr-id payload)))
        (is (= "simple diff" (:diff payload)))
        (is (map? (:detail payload)))))))

(deftest dispatch-effect-fetch-pr-diff-total-failure-test
  (testing "dispatch-effect with total failure returns error in payload"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-failure 127 "gh: command not found"))]
      (let [[msg-type payload] (iface/dispatch-effect nil {:type :fetch-pr-diff
                                                           :repo "r"
                                                           :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (string? (:error payload)))))))

;; ---------------------------------------------------------------------------- Edge cases: diff content

(deftest diff-with-binary-file-markers-test
  (testing "diff containing binary file markers is returned intact"
    (let [diff "diff --git a/img.png b/img.png\nBinary files differ"]
      (with-redefs [process/shell (fn [_opts & _] (mock-shell-success diff))]
        (is (= diff (github/fetch-pr-diff "r" 1)))))))

(deftest diff-with-unicode-content-test
  (testing "diff with unicode characters is preserved"
    (let [diff "diff --git a/i18n.clj b/i18n.clj\n+;; 日本語テスト — émojis: 🎉"]
      (with-redefs [process/shell (fn [_opts & _] (mock-shell-success diff))]
        (is (= diff (github/fetch-pr-diff "r" 1)))
        (is (str/includes? (github/fetch-pr-diff "r" 1) "🎉"))))))