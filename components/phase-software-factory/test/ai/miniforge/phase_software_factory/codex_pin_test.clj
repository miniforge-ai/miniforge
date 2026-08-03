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
(ns ai.miniforge.phase-software-factory.codex-pin-test
  "The happy path (a real codex producing a pin) is covered by the codex
   component's own tests against generator-produced fixtures
   (ai.miniforge.codex.render-test/pin-entry-builds-a-prompt-ready-file);
   these cover the phase-side skip conditions."
  (:require [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} no-configured-codex-means-no-pin-and-no-noise
  (is (nil? (codex-pin/pin-file :implement nil nil))))

(deftest ^{:stratum 0} unmapped-phase-gets-no-pin
  (is (nil? (codex-pin/pin-file :verify nil "/anywhere"))))

(deftest ^{:stratum 0} anomaly-skips-the-pin-rather-than-pinning-garbage
  (is (nil? (codex-pin/pin-file :implement nil "/nonexistent/codex"))))

(deftest ^{:stratum 0} only-wired-phases-are-mapped
  ;; implement/plan wire via pin-outcome (existing-files); review wires via
  ;; landings-text (prompt section). A mapping without a wire would be a
  ;; defined-but-unreachable capability.
  (is (= #{:implement :plan :review} (set (keys codex-pin/phase->situation)))))

(deftest ^{:stratum 0} landings-text-skip-conditions
  (is (nil? (codex-pin/landings-text :review nil nil)))
  (is (nil? (codex-pin/landings-text :verify nil "/anywhere")))
  (is (nil? (codex-pin/landings-text :review nil "/nonexistent/codex"))))

(deftest ^{:stratum 0} anomaly-with-nil-logger-warns-on-stderr
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (nil? (codex-pin/pin-file :implement nil "/nonexistent/codex"))))
    (is (re-find #"WARN: codex pin skipped for implement" (str err))
        "a nil logger must not turn a configured-codex failure silent")))

(deftest ^{:stratum 0} pin-outcome-states
  (is (= {:entry nil :status :unconfigured :anomaly nil}
         (codex-pin/pin-outcome :implement nil nil)))
  (is (= {:entry nil :status :unmapped :anomaly nil}
         (codex-pin/pin-outcome :verify nil "/anywhere")))
  (is (= :skipped
         (:status (codex-pin/pin-outcome :implement nil "/nonexistent/codex")))))

(deftest ^{:stratum 0} consultation-summary-distinguishes-unknown-from-unread
  (let [pinned {:entry {:path codex-pin/pin-path} :status :pinned :anomaly nil}]
    (testing "nil reads log means UNKNOWN, not false — absence of the record is not absence of the read"
      (is (nil? (:pin-read? (codex-pin/consultation-summary pinned nil)))))
    (testing "reads log without the pin path means false"
      (is (false? (:pin-read? (codex-pin/consultation-summary
                                pinned [{:path "src/a.clj" :source :cache}])))))
    (testing "reads log with the pin path means true"
      (is (true? (:pin-read? (codex-pin/consultation-summary
                               pinned [{:path codex-pin/pin-path :source :cache}])))))
    (testing "skipped consultation carries its anomaly"
      (is (= {:pinned? false :status :skipped :anomaly :codex-unreadable :pin-read? nil}
             (codex-pin/consultation-summary
               {:entry nil :status :skipped :anomaly :codex-unreadable} nil))))))
