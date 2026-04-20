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

(ns ai.miniforge.bb-data-plane-http.core-test
  "Layer 0 + Layer 2 tested here. Layer 1 (process lifecycle) is exercised
   by consumer repos running `bb test:signals:fixtures` or equivalent."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-data-plane-http.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Base-URL resolution.

(deftest test-resolve-base-url-falls-back-to-default
  (testing "given an empty cfg → default URL"
    (is (= "http://127.0.0.1:8787" (sut/resolve-base-url {})))))

(deftest test-resolve-base-url-honors-explicit-cfg
  (testing "given :base-url in cfg → returned as-is when env unset"
    ;; We can't easily set/unset env in JVM tests; rely on a var that
    ;; we know is unset (long random name) for the env-var slot.
    (is (= "http://other:9000"
           (sut/resolve-base-url
            {:base-url "http://other:9000"
             :base-url-env "UNLIKELY_TO_EXIST_ENV_VAR_NAME_12345"})))))

(deftest test-binary-path-resolves-relative
  (testing "given :root + :binary → joined path"
    (is (= "/tmp/root/bin/dp"
           (sut/binary-path {:root "/tmp/root" :binary "bin/dp"})))))

(deftest test-binary-path-honors-absolute
  (testing "given absolute :binary → returned as-is"
    (is (= "/abs/bin/dp"
           (sut/binary-path {:root "/tmp/root" :binary "/abs/bin/dp"})))))

(deftest test-manifest-path-resolves-relative
  (testing "given :root + :manifest → joined path"
    (is (= "/tmp/root/Cargo.toml"
           (sut/manifest-path {:root "/tmp/root" :manifest "Cargo.toml"})))))

;------------------------------------------------------------------------------ Layer 2
;; HTTP helpers.

(deftest test-http-get-body-returns-body
  (testing "given http-fn returning {:body 'x'} → 'x'"
    (is (= "raw-bytes"
           (sut/http-get-body "http://example"
                              {:http-fn (fn [_] {:status 200 :body "raw-bytes"})})))))

(deftest test-http-get-json-parses-on-200
  (testing "given 200 response with JSON body → keywordized parsed map"
    (is (= {:a 1 :b "c"}
           (sut/http-get-json "http://example"
                              {:http-fn (fn [_] {:status 200 :body "{\"a\":1,\"b\":\"c\"}"})})))))

(deftest test-http-get-json-throws-on-non-200
  (testing "given 500 response → throws ex-info"
    (is (thrown? Exception
                 (sut/http-get-json "http://example"
                                    {:http-fn (fn [_] {:status 500 :body "err"})})))))

(deftest test-http-post-json-sends-body-and-parses
  (testing "given http-fn capturing args → body serialized, response parsed"
    (let [captured (atom nil)
          http    (fn [url opts]
                    (reset! captured {:url url :opts opts})
                    {:status 200 :body "{\"ok\":true}"})]
      (is (= {:ok true}
             (sut/http-post-json "http://example" {:scenario "live"}
                                 {:http-fn http})))
      (is (= "http://example" (:url @captured)))
      (is (= "application/json"
             (get-in @captured [:opts :headers "Content-Type"])))
      (is (= "{\"scenario\":\"live\"}"
             (get-in @captured [:opts :body]))))))

(deftest test-http-post-json-throws-on-non-200
  (testing "given 400 response → throws ex-info"
    (is (thrown? Exception
                 (sut/http-post-json "http://example" {}
                                     {:http-fn (fn [_ _] {:status 400 :body "bad"})})))))

;------------------------------------------------------------------------------ wait-ready!
;; Uses injected http + sleep so it's deterministic.

(deftest test-wait-ready-returns-ready-on-first-success
  (testing "given http-fn returning 200 on first call → :ready"
    (is (= :ready
           (sut/wait-ready! "http://example"
                            {:http-fn (fn [_] {:status 200})
                             :sleep-fn (fn [] nil)
                             :max-attempts 1})))))

(deftest test-wait-ready-exhausts-attempts
  (testing "given http-fn that always fails → throws with :attempts"
    (let [ex (try
               (sut/wait-ready! "http://example"
                                {:http-fn (fn [_] (throw (Exception. "nope")))
                                 :sleep-fn (fn [] nil)
                                 :max-attempts 3})
               nil
               (catch Exception e e))]
      (is (some? ex))
      (is (= 3 (:attempts (ex-data ex)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-data-plane-http.core-test)

  :leave-this-here)
