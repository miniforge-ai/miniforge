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

(ns ai.miniforge.llm.llm-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `llm-client/llm-error`
   and `llm-client/http-post-request` after the W2 anomaly convergence
   flip.

   Pre-flip these emitted `:anomaly/category` via
   `response/make-anomaly` / `response/from-exception`, with domain keys
   such as `:anomaly.llm/url` and `:anomaly/operation` at the anomaly
   map's top level.

   Post-flip:
   - generic-standard categories (`:anomalies/{fault,unavailable,
     unsupported,timeout}`) map 1:1 to `:anomaly/type` with no
     `:anomaly/subtype`;
   - domain categories (`:anomalies.agent/llm-error`,
     `:anomalies.agent/rate-limited`) carry `:anomaly/subtype` equal to
     the legacy category verbatim;
   - domain payload (`:operation`, `:url`) lives under `:anomaly/data` —
     the canonical schema is closed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.client :as http]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.protocols.impl.llm-client :as client]))

;------------------------------------------------------------------------------ Layer 0
;; Factories and fixtures

(def ^:private test-url
  "Static target URL used by `http-post-request` failure tests. Never
   actually dereferenced — `http/post` is stubbed via `with-redefs`."
  "http://llm-anomaly-shape-test.invalid/llm")

(def ^:private test-headers
  {"Content-Type" "application/json"})

(defn- error-type-placeholder
  "Opaque error-type label used by `llm-error` tests. The shape-of-anomaly
   tests don't assert against the error-type field — they only need a
   stable, non-blank token to pass the builder's contract."
  []
  "shape_test_error_type")

(defn- anomaly-of
  "Build a canonical anomaly map for the given legacy category by driving
   the public `llm-error` builder and extracting `:anomaly` from the
   response. The 2nd/3rd args are opaque placeholders — shape tests
   only assert on the anomaly map itself."
  [category]
  (:anomaly (client/llm-error category (error-type-placeholder) "boom")))

(defn- delivered-future
  "Wrap a value in an already-resolved future so `@(http/post ...)`
   returns it synchronously under `with-redefs`."
  [v]
  (let [p (promise)]
    (deliver p v)
    p))

(defn- stub-http-post-throwing
  "`http/post` stub whose deref throws the given exception — mirrors
   http-kit's throwing failure mode (e.g. body serialization or an
   `IllegalArgumentException` on a malformed URL)."
  [^Throwable ex]
  (fn [_url _opts]
    (reify clojure.lang.IDeref
      (deref [_] (throw ex)))))

(defn- stub-http-post-error-map
  "`http/post` stub whose deref returns `{:error <Throwable>}` — mirrors
   http-kit's non-throwing failure mode (the common case for connection
   refused / DNS failure)."
  [^Throwable cause]
  (fn [_url _opts]
    (delivered-future {:error cause :opts {}})))

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest llm-error-generic-standard-category-emits-typed-anomaly-no-subtype
  (testing ":anomalies/unavailable maps to :unavailable with no subtype"
    (let [a (anomaly-of :anomalies/unavailable)]
      (is (anomaly/anomaly? a))
      (is (= :unavailable (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))
          "generic-standard category emits no :anomaly/subtype")))

  (testing ":anomalies/fault maps to :fault with no subtype"
    (let [a (anomaly-of :anomalies/fault)]
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))))

  (testing ":anomalies/unsupported maps to :unsupported with no subtype"
    (let [a (anomaly-of :anomalies/unsupported)]
      (is (= :unsupported (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))))

  (testing ":anomalies/timeout maps to :timeout with no subtype"
    (let [a (anomaly-of :anomalies/timeout)]
      (is (= :timeout (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))))))

(deftest llm-error-domain-category-emits-subtype
  (testing ":anomalies.agent/llm-error → :fault + subtype verbatim"
    (let [a (anomaly-of :anomalies.agent/llm-error)]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (= :anomalies.agent/llm-error (:anomaly/subtype a))
          "legacy domain category preserved verbatim as :anomaly/subtype")))

  (testing ":anomalies.agent/rate-limited → :unavailable + subtype"
    (let [a (anomaly-of :anomalies.agent/rate-limited)]
      (is (= :unavailable (:anomaly/type a)))
      (is (= :anomalies.agent/rate-limited (:anomaly/subtype a))))))

(deftest llm-error-domain-payload-lives-under-anomaly-data
  (testing "the :operation domain field is carried under :anomaly/data"
    (let [a    (anomaly-of :anomalies/fault)
          data (:anomaly/data a)]
      (is (= :llm-complete (:operation data))
          ":operation lives under :anomaly/data — not at the anomaly's top level")
      (is (nil? (:anomaly/operation a))
          "no legacy :anomaly/operation at the anomaly's top level")
      (is (nil? (:operation a))
          ":operation also not at the anomaly's top level"))))

(deftest http-post-request-throwing-failure-emits-canonical-unavailable
  (testing "thrown exception on deref → canonical :unavailable anomaly"
    (let [cause (java.net.ConnectException. "Connection refused")]
      (with-redefs [http/post (stub-http-post-throwing cause)]
        (let [a (client/http-post-request test-url test-headers {})]
          (is (anomaly/anomaly? a)
              "throwing path must produce a canonical anomaly")
          (is (= :unavailable (:anomaly/type a)))
          (is (nil? (:anomaly/subtype a))
              ":anomalies/unavailable is generic-standard — no subtype")
          (is (= :http-request (get-in a [:anomaly/data :operation])))
          (is (= test-url (get-in a [:anomaly/data :url]))
              ":url lives under :anomaly/data, not at the top level")
          (is (nil? (:anomaly.llm/url a))
              "legacy :anomaly.llm/url no longer set at the top level"))))))

(deftest http-post-request-error-map-failure-emits-canonical-unavailable
  (testing "{:error <Throwable>} deref response → canonical :unavailable anomaly"
    ;; http-kit's common non-throwing failure mode: the future resolves
    ;; to a map with `:error` set. Pre-fix this would have flowed through
    ;; as a non-anomaly map and `parse-ollama-response` would have
    ;; misclassified it as `:anomalies/fault` (parse_error).
    (let [cause (java.net.ConnectException. "Connection refused")]
      (with-redefs [http/post (stub-http-post-error-map cause)]
        (let [a (client/http-post-request test-url test-headers {})]
          (is (anomaly/anomaly? a)
              ":error map must be translated into a canonical anomaly")
          (is (= :unavailable (:anomaly/type a)))
          (is (nil? (:anomaly/subtype a)))
          (is (= :http-request (get-in a [:anomaly/data :operation])))
          (is (= test-url (get-in a [:anomaly/data :url]))))))))
