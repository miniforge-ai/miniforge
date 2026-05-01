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

(ns ai.miniforge.boundary.interface.ex-data-preservation-test
  "ex-data preservation. Libraries that throw `ExceptionInfo` carry
   structured data through `(ex-data e)`. Boundary must surface that
   payload intact so downstream handlers can branch on it without
   re-parsing exception messages."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(defn- ex-data-from [chain]
  (-> chain chain/last-anomaly :anomaly/data :exception/data))

(deftest plain-keyword-keys-survive
  (testing "ex-data with simple keyword keys is preserved verbatim"
    (let [payload {:user/id 42 :retry? true}
          c (boundary/execute :db
                              (chain/create-chain :flow)
                              :db/lookup
                              (fn [] (throw (ex-info "lookup failed" payload))))]
      (is (= payload (ex-data-from c))))))

(deftest namespaced-keys-survive
  (testing "namespaced ex-data keys are preserved without rewriting"
    (let [payload {:db.error/code :sql.unique-violation
                   :db.error/table "users"}
          c (boundary/execute :db
                              (chain/create-chain :flow)
                              :db/insert
                              (fn [] (throw (ex-info "constraint" payload))))]
      (is (= payload (ex-data-from c))))))

(deftest nested-data-structures-survive
  (testing "nested maps and vectors inside ex-data are preserved"
    (let [payload {:request {:url "https://x" :headers {"x-trace" "1"}}
                   :attempts [1 2 3]}
          c (boundary/execute :network
                              (chain/create-chain :flow)
                              :http/get
                              (fn [] (throw (ex-info "5xx" payload))))]
      (is (= payload (ex-data-from c))))))

(deftest empty-ex-data-is-an-empty-map
  (testing "ex-info with an empty data map preserves the empty map (not nil)"
    (let [c (boundary/execute :db
                              (chain/create-chain :flow)
                              :db/op
                              (fn [] (throw (ex-info "x" {}))))]
      (is (= {} (ex-data-from c))))))

(deftest ex-data-preservation-survives-cause-chain
  (testing "ex-data on the outer ExceptionInfo is preserved even when there is a cause"
    (let [root (RuntimeException. "root")
          wrap (ex-info "wrapped" {:layer :outer} root)
          c    (boundary/execute :network
                                 (chain/create-chain :flow)
                                 :op
                                 (fn [] (throw wrap)))]
      (is (= {:layer :outer} (ex-data-from c)))
      (is (= "root" (-> c chain/last-anomaly :anomaly/data :exception/cause))))))
