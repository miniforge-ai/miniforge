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
(ns ai.miniforge.webhook-intake.signature-test
  (:require
   [ai.miniforge.webhook-intake.signature :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} github-example
  "The worked example from GitHub's webhook validation docs."
  {:secret "It's a Secret to Everybody"
   :body "Hello, World!"
   :signature "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"})

(defn ^{:stratum 0} utf8
  "UTF-8 bytes of `s`."
  [s]
  (.getBytes (str s) "UTF-8"))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} sign-matches-github-example-test
  (testing "sign reproduces GitHub's documented signature"
    (let [{:keys [secret body signature]} github-example]
      (is (= signature (sut/sign secret (utf8 body)))))))

(deftest ^{:stratum 1} valid-signature-test
  (let [{:keys [secret body signature]} github-example]
    (testing "the documented signature is accepted"
      (is (sut/valid-signature? secret (utf8 body) signature)))
    (testing "a changed body, a changed secret, or a bare digest is refused"
      (is (not (sut/valid-signature? secret (utf8 (str body " ")) signature)))
      (is (not (sut/valid-signature? "another secret" (utf8 body) signature)))
      (is (not (sut/valid-signature? secret (utf8 body) (subs signature (count sut/signature-prefix))))))
    (testing "no header, or no secret configured, never validates"
      (is (not (sut/valid-signature? secret (utf8 body) nil)))
      (is (not (sut/valid-signature? "" (utf8 body) signature)))
      (is (not (sut/valid-signature? nil (utf8 body) signature))))))
