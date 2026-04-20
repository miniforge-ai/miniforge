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

(ns ai.miniforge.bb-r2.core-test
  "Pure layer tests: classification + command-vector shape. Side effects
   are exercised by `pull!`/`upload!` via injected `:sh-fn`/`:run-fn` in
   a single happy-path + missing-key test each."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-r2.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Classification.

(deftest test-classify-wrangler-result-ok
  (testing "given exit 0 → :ok"
    (is (= :ok (sut/classify-wrangler-result {:exit 0 :err ""})))
    (is (= :ok (sut/classify-wrangler-result {:exit 0 :err "ignored"})))))

(deftest test-classify-wrangler-result-missing
  (testing "given non-zero exit with 'key does not exist' in stderr → :missing"
    (is (= :missing (sut/classify-wrangler-result
                     {:exit 1 :err "The specified key does not exist."})))))

(deftest test-classify-wrangler-result-error
  (testing "given non-zero exit with unknown stderr → :error"
    (is (= :error (sut/classify-wrangler-result
                   {:exit 1 :err "something else broke"})))
    (is (= :error (sut/classify-wrangler-result {:exit 1 :err nil})))))

;------------------------------------------------------------------------------ Layer 1
;; Command-vector builder.

(deftest test-build-upload-cmd-minimal
  (testing "given bucket/src/key and no opts → default content-type JSON"
    (is (= ["aws" "s3" "cp" "/tmp/src.json" "s3://bkt/a/b.json"
            "--content-type" "application/json"]
           (sut/build-upload-cmd "bkt" "/tmp/src.json" "a/b.json" {})))))

(deftest test-build-upload-cmd-includes-endpoint
  (testing "given :endpoint → --endpoint-url appended"
    (is (some #{"--endpoint-url" "https://r2"}
              (sut/build-upload-cmd "bkt" "/x" "k"
                                    {:endpoint "https://r2"})))))

(deftest test-build-upload-cmd-includes-cache-control
  (testing "given :cache-control → --cache-control appended"
    (is (some #{"public, max-age=3600"}
              (sut/build-upload-cmd "bkt" "/x" "k"
                                    {:cache-control "public, max-age=3600"})))))

(deftest test-build-upload-cmd-honors-content-type-override
  (testing "given :content-type → overrides default"
    (is (some #{"text/plain"}
              (sut/build-upload-cmd "bkt" "/x" "k"
                                    {:content-type "text/plain"})))))

;------------------------------------------------------------------------------ Layer 2
;; pull! / upload! via injected mocks.

(deftest test-pull-happy-path
  (testing "given sh-fn that returns exit 0 → :ok"
    (is (= :ok (sut/pull! "a/b.json" "/tmp/d.json"
                          {:worker-dir "/tmp/worker"
                           :bucket "bkt"
                           :sh-fn (fn [& _] {:exit 0 :err ""})})))))

(deftest test-pull-missing-key
  (testing "given sh-fn that returns missing-key stderr → :missing"
    (is (= :missing (sut/pull! "a/b.json" "/tmp/d.json"
                               {:worker-dir "/tmp/worker"
                                :bucket "bkt"
                                :sh-fn (fn [& _]
                                         {:exit 1
                                          :err "The specified key does not exist."})})))))

(deftest test-pull-requires-worker-dir
  (testing "given no :worker-dir → throws with :opts :worker-dir"
    (is (thrown? Exception
                 (sut/pull! "k" "/tmp/d" {:bucket "bkt"})))))

(deftest test-pull-requires-bucket
  (testing "given no :bucket → throws with :opts :bucket"
    (is (thrown? Exception
                 (sut/pull! "k" "/tmp/d" {:worker-dir "/tmp/w"})))))

(deftest test-upload-invokes-run-fn-and-returns-dest
  (testing "given run-fn → invoked with the built cmd, returns s3 URL"
    (let [calls (atom [])
          run   (fn [& args] (swap! calls conj args))
          dest  (sut/upload! "bkt" "/tmp/x.json" "a/b.json"
                             {:endpoint "https://r2"
                              :run-fn run})]
      (is (= "s3://bkt/a/b.json" dest))
      (is (= 1 (count @calls)))
      (is (= "aws" (first (first @calls)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-r2.core-test)

  :leave-this-here)
