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

(ns ai.miniforge.messages.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.messages.interface :as messages]))

(deftest create-translator-test
  (testing "create-translator returns a callable function"
    ;; Use a resource path that won't exist — should fall back to key name
    (let [t (messages/create-translator "nonexistent.edn" :test/messages)]
      (is (fn? t))
      (is (= "some-key" (t :some-key))))))

(deftest t-with-catalog-test
  (testing "t substitutes params into template"
    (let [catalog (delay {:greeting "Hello, {name}!"})]
      (is (= "Hello, World!" (messages/t catalog :greeting {:name "World"})))))

  (testing "t falls back to key name for missing keys"
    (let [catalog (delay {})]
      (is (= "missing-key" (messages/t catalog :missing-key))))))
