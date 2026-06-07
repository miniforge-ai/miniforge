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

(ns ai.miniforge.llm.prompt-size-telemetry-test
  "Pre-flight input-size gauge for the assembled agent prompt."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

(deftest prompt-size-telemetry-test
  (testing "counts system + user chars and estimates input tokens (chars/4)"
    (is (= {:system-chars 8
            :user-chars 12
            :total-chars 20
            :estimated-input-tokens 5}
           (impl/prompt-size-telemetry "sysprmpt" "user-prompt!"))))

  (testing "estimate rounds UP (ceil) so a near-boundary prompt isn't under-reported"
    ;; 21 chars / 4 = 5.25 → 6, not 5 (truncation would hide imminent overflow)
    (is (= 6 (:estimated-input-tokens (impl/prompt-size-telemetry "" (apply str (repeat 21 \x))))))
    ;; exact multiples don't over-count
    (is (= 5 (:estimated-input-tokens (impl/prompt-size-telemetry "" (apply str (repeat 20 \x))))))
    ;; a single char is at least 1 token, never 0
    (is (= 1 (:estimated-input-tokens (impl/prompt-size-telemetry "" "x")))))

  (testing "nil system or prompt is treated as empty, never throws"
    (is (= {:system-chars 0 :user-chars 4 :total-chars 4 :estimated-input-tokens 1}
           (impl/prompt-size-telemetry nil "abcd")))
    (is (= {:system-chars 0 :user-chars 0 :total-chars 0 :estimated-input-tokens 0}
           (impl/prompt-size-telemetry nil nil))))

  (testing "scales toward the context window — a ~200k-token prompt reads as such"
    (let [big (apply str (repeat 800000 \x))
          {:keys [estimated-input-tokens]} (impl/prompt-size-telemetry "" big)]
      (is (= 200000 estimated-input-tokens)))))
