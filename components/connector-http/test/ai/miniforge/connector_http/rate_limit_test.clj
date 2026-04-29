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

(ns ai.miniforge.connector-http.rate-limit-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-http.rate-limit :as rate]))

;;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def github-mapping
  {:remaining "x-ratelimit-remaining" :reset "x-ratelimit-reset" :limit "x-ratelimit-limit"})

(def gitlab-mapping
  {:remaining "ratelimit-remaining" :reset "ratelimit-reset" :limit "ratelimit-limit"})

;;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest parse-rate-headers-test
  (testing "parses GitHub rate limit headers"
    (let [headers {"x-ratelimit-remaining" "4990"
                   "x-ratelimit-reset" "1711468800"
                   "x-ratelimit-limit" "5000"}
          info (rate/parse-rate-headers headers github-mapping)]
      (is (= 4990 (:remaining info)))
      (is (= 1711468800 (:reset-epoch info)))
      (is (= 5000 (:limit info)))))

  (testing "parses GitLab rate limit headers"
    (let [headers {"ratelimit-remaining" "295"
                   "ratelimit-reset" "1711468800"
                   "ratelimit-limit" "300"}
          info (rate/parse-rate-headers headers gitlab-mapping)]
      (is (= 295 (:remaining info)))
      (is (= 300 (:limit info)))))

  (testing "returns nil when headers absent"
    (is (nil? (rate/parse-rate-headers {} github-mapping))))

  (testing "handles non-numeric values gracefully"
    (is (nil? (rate/parse-rate-headers {"x-ratelimit-remaining" "bogus"
                                        "x-ratelimit-reset" "also-bogus"}
                                       github-mapping)))))

(deftest update-rate-state-test
  (testing "stores rate info in handle atom"
    (let [handles (atom {"h1" {:config {}}})]
      (rate/update-rate-state! handles "h1" {:remaining 100 :reset-epoch 99999})
      (is (= 100 (get-in @handles ["h1" :rate-limit :remaining])))))

  (testing "no-op when rate-info is nil"
    (let [handles (atom {"h1" {:config {}}})]
      (rate/update-rate-state! handles "h1" nil)
      (is (nil? (get-in @handles ["h1" :rate-limit]))))))

(deftest acquire-permit-no-block-test
  (testing "does not block when remaining is above threshold"
    (let [handles (atom {"h1" {:rate-limit {:remaining 4990 :reset-epoch 9999999999}}})]
      ;; Should return immediately (nil = no wait needed)
      (is (nil? (rate/acquire-permit! handles "h1" {}))))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector-http.rate-limit-test
  )
