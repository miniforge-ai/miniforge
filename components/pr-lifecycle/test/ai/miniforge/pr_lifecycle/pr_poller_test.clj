;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.pr-lifecycle.pr-poller-test
  (:require
   [ai.miniforge.pr-lifecycle.pr-poller :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest parse-gh-comments-test
  (testing "normalizes review comments with file context"
    (let [comments (#'sut/parse-gh-comments
                    "[{\"id\":1,\"body\":\"Please fix\",\"created_at\":\"2026-04-01T12:00:00Z\",\"path\":\"src/core.clj\",\"line\":42,\"user\":{\"login\":\"reviewer\"}}]"
                    :review-comment)]
      (is (= 1 (count comments)))
      (is (= :review-comment (:comment/type (first comments))))
      (is (= "src/core.clj" (:comment/path (first comments))))
      (is (= 42 (:comment/line (first comments)))))))

(deftest comments-since-test
  (testing "returns comments strictly newer than the watermark"
    (let [comments [{:comment/created-at "2026-04-01T12:00:00Z" :comment/id 1}
                    {:comment/created-at "2026-04-01T12:05:00Z" :comment/id 2}]]
      (is (= [2]
             (mapv :comment/id
                   (sut/comments-since comments "2026-04-01T12:00:00Z")))))))
