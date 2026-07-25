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
(ns ai.miniforge.tool.anomaly-shape-test
  "Lock the canonical anomaly shape produced by the tool component
   after the W2 anomaly convergence flip.

   The tool component produces anomalies on three failure paths:
   - parameter validation failure → `:invalid-input`
   - handler exception           → `:fault`
   - tool-not-found in registry  → `:not-found`

   All three previously used the legacy `response/make-anomaly` /
   `response/from-exception` constructors carrying `:anomaly/category`.
   Post-flip they use `ai.miniforge.anomaly.interface` with canonical
   `:anomaly/type`; all three are generic-standard cognitect categories
   with no `:anomaly/subtype` per
   `docs/runbooks/anomaly-convergence.md`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.tool.interface :as tool]))

;------------------------------------------------------------------------------ Layer 0

;; Test factories + handler fixtures.
(defn- ^{:stratum 0} echo-handler [params _ctx] (:message params))

(defn- ^{:stratum 0} throwing-handler [_params _ctx] (throw (ex-info "boom" {:why :test})))

(defn- ^{:stratum 0} nil-message-throwing-handler [_params _ctx] (throw (Exception.)))

(defn- ^{:stratum 0} make-tool
  "Factory for a `tool/create-tool` invocation. Always supplies the
   four-field map shape so each test only varies what it cares about."
  [{:keys [id description parameters handler]
    :or   {description "test tool"
           parameters  {}}}]
  (tool/create-tool {:id          id
                     :description description
                     :parameters  parameters
                     :handler     handler}))

(deftest ^{:stratum 0} execute-tool-not-found-emits-not-found-anomaly
  (testing "execute-tool on a missing tool yields :not-found anomaly"
    (let [reg    (tool/create-registry)
          result (tool/execute-by-id reg :tools/missing {} {})
          a      (:anomaly result)]
      (is (false? (:success result)))
      (is (anomaly/anomaly? a))
      (is (= :not-found (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))))))

;------------------------------------------------------------------------------ Layer 1

;; Anomaly-shape tests, one per failure path.
(deftest ^{:stratum 1} execute-validation-error-emits-invalid-input-anomaly
  (testing "missing required parameter yields :invalid-input anomaly"
    (let [t      (make-tool {:id         :tools/echo
                             :parameters {:message {:type :string :required true}}
                             :handler    echo-handler})
          result (tool/execute t {} {})
          a      (:anomaly result)]
      (is (false? (:success result)))
      (is (anomaly/anomaly? a))
      (is (= :invalid-input (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))
          "generic-standard incorrect has no domain subtype"))))

(deftest ^{:stratum 1} execute-handler-exception-emits-fault-anomaly
  (testing "handler exception yields :fault anomaly with provenance"
    (let [t      (make-tool {:id      :tools/throws
                             :handler throwing-handler})
          result (tool/execute t {} {})
          a      (:anomaly result)]
      (is (false? (:success result)))
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))
      (is (= "boom" (:anomaly/ex-message (:anomaly/data a)))))))

(deftest ^{:stratum 1} execute-handler-nil-message-exception-falls-back-to-class-name
  (testing "handler throwing an exception with no message still yields a schema-valid anomaly"
    (let [t      (make-tool {:id      :tools/throws-blank
                             :handler nil-message-throwing-handler})
          result (tool/execute t {} {})
          a      (:anomaly result)]
      (is (false? (:success result)))
      (is (anomaly/anomaly? a)
          "nil ex-message must not break the canonical anomaly schema")
      (is (= :fault (:anomaly/type a)))
      (is (string? (:anomaly/message a)))
      (is (= "java.lang.Exception" (:anomaly/message a))
          "fallback is the exception class name")
      (is (= "java.lang.Exception" (get-in result [:error :message]))
          ":error :message stays consistent with the anomaly fallback"))))
