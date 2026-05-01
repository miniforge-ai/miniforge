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

(ns ai.miniforge.boundary.interface.exception-data-capture-test
  "Exception payload capture. The anomaly's `:anomaly/data` carries
   the canonical `CapturedException` shape — type, message, cause,
   ex-data, and the boundary category."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

(defn- captured [chain]
  (-> chain chain/last-anomaly :anomaly/data))

(deftest type-is-fully-qualified-class-name
  (testing ":exception/type is the FQCN of the thrown exception"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (java.io.IOException. "boom"))))]
      (is (= "java.io.IOException"
             (:exception/type (captured c)))))))

(deftest message-is-the-exception-message
  (testing ":exception/message is (.getMessage e)"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (RuntimeException. "down for maintenance"))))]
      (is (= "down for maintenance"
             (:exception/message (captured c)))))))

(deftest cause-is-captured-when-present
  (testing ":exception/cause is the message of the immediate cause"
    (let [root  (IllegalStateException. "root")
          wrap  (RuntimeException. "wrap" root)
          c     (boundary/execute :unknown
                                  (chain/create-chain :flow)
                                  :op
                                  (fn [] (throw wrap)))]
      (is (= "root" (:exception/cause (captured c)))))))

(deftest cause-is-nil-when-absent
  (testing ":exception/cause is nil when the exception has no cause"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (RuntimeException. "lonely"))))]
      (is (nil? (:exception/cause (captured c)))))))

(deftest data-is-ex-data-for-exceptioninfo
  (testing ":exception/data carries the (ex-data e) map for ExceptionInfo"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (ex-info "boom" {:user/id 7 :retry? true}))))]
      (is (= {:user/id 7 :retry? true}
             (:exception/data (captured c)))))))

(deftest data-is-nil-for-plain-exceptions
  (testing ":exception/data is nil when the throw is a plain Throwable"
    (let [c (boundary/execute :unknown
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (RuntimeException. "no data"))))]
      (is (nil? (:exception/data (captured c)))))))

(deftest captured-payload-validates-against-schema
  (testing "the captured payload satisfies the CapturedException schema"
    (let [c (boundary/execute :db
                              (chain/create-chain :flow)
                              :op
                              (fn [] (throw (ex-info "x" {:k :v}))))]
      (is (m/validate boundary/CapturedException (captured c))))))
