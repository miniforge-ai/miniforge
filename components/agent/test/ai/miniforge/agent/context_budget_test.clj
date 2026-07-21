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

(ns ai.miniforge.agent.context-budget-test
  "Tests for the shared N12 §5 context-window budgeting ladder. The planner
   and reviewer each wrap this with role-specific prompt builders; here it is
   exercised directly with synthetic builders so the ladder's behavior is
   pinned independent of either role's prompt shape.

   `codellama-34b` is a 16384-token-window catalog entry (the same one the
   planner budget test uses); deriving thresholds from the returned window
   keeps the asserts off hard-coded sizes."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.context-budget :as cb]))

(defn- assemble [m]
  (cb/assemble-within-budget
   (merge {:effective-system "system"
           :reserve 0
           :build-full (constantly "FULL")
           :build-shed (constantly "SHED")
           :shed-count 1}
          m)))

(deftest assemble-within-budget-test
  (testing "uncatalogued model (nil window) → never sheds, full prompt kept"
    (let [r (assemble {:model "no-such-model"})]
      (is (nil? (:window r)))
      (is (false? (:shed? r)))
      (is (false? (:over-after-shed? r)))
      (is (= "FULL" (:user-prompt r)))))

  (testing "a small prompt under the window is not shed"
    (let [r (assemble {:model "codellama-34b"})]
      (is (false? (:shed? r)))
      (is (= "FULL" (:user-prompt r)))
      (is (= (:est-full r) (:est-final r)))))

  (testing "a full prompt over the window sheds to the shed builder's output"
    (let [big (apply str (repeat 300000 \x))
          r (assemble {:model "codellama-34b"
                       :build-full (constantly big)
                       :build-shed (constantly "manifest")})]
      (is (true? (:shed? r)))
      (is (false? (:over-after-shed? r)))
      (is (= "manifest" (:user-prompt r)))
      (is (< (:est-final r) (:est-full r)))))

  (testing "shed-count 0 → nothing to shed → irreducible overflow, full kept"
    (let [big (apply str (repeat 300000 \x))
          r (assemble {:model "codellama-34b"
                       :build-full (constantly big)
                       :shed-count 0})]
      (is (false? (:shed? r)))
      (is (true? (:over-after-shed? r)))
      (is (= big (:user-prompt r)))))

  (testing "the shed form is still over the window → shed? true AND over-after-shed? true"
    (let [big (apply str (repeat 300000 \x))
          r (assemble {:model "codellama-34b"
                       :build-full (constantly big)
                       :build-shed (constantly big)})]
      (is (true? (:shed? r)))
      (is (true? (:over-after-shed? r)))))

  (testing "max-inline-fraction sheds a prompt that FITS the window but exceeds
            the cap — window fit alone stalls turns past the phase timeout
            (2026-07-21 dogfood: 99.2%-of-window prompt, killed at 600s)"
    ;; window 16384, reserve 0 → cap 0.5 ⇒ inline-cap 8192.
    ;; ~40000 chars ≈ 10000 est tokens: under the window, over the cap.
    (let [mid (apply str (repeat 40000 \x))
          r (assemble {:model "codellama-34b"
                       :max-inline-fraction 0.5
                       :build-full (constantly mid)
                       :build-shed (constantly "manifest")})]
      (is (= 8192 (:inline-cap r)))
      (is (true? (:shed? r)))
      (is (false? (:over-after-shed? r)) "under the window — not an overflow")
      (is (= "manifest" (:user-prompt r)))))

  (testing "a prompt under the cap stays fully inlined"
    (let [small (apply str (repeat 20000 \x))    ; ≈5000 est tokens < 8192 cap
          r (assemble {:model "codellama-34b"
                       :max-inline-fraction 0.5
                       :build-full (constantly small)})]
      (is (false? (:shed? r)))
      (is (= small (:user-prompt r)))))

  (testing "over the cap with nothing to shed is sent as-is and is NOT an
            irreducible overflow — that word is reserved for the window"
    (let [mid (apply str (repeat 40000 \x))
          r (assemble {:model "codellama-34b"
                       :max-inline-fraction 0.5
                       :build-full (constantly mid)
                       :shed-count 0})]
      (is (false? (:shed? r)))
      (is (false? (:over-after-shed? r)))
      (is (= mid (:user-prompt r)))))

  (testing "the reserve drops the effective window and fires the shed earlier"
    (let [content    (apply str (repeat 50000 \x))
          no-reserve (assemble {:model "codellama-34b"
                                :build-full (constantly content)
                                :build-shed (constantly "m")})
          window     (:window no-reserve)
          est        (:est-full no-reserve)
          ;; a reserve that drops the effective window just below the estimate,
          ;; kept under window/2 so it isn't clamped
          reserve    (+ (- window est) 1000)
          reserved   (assemble {:model "codellama-34b"
                                :reserve reserve
                                :build-full (constantly content)
                                :build-shed (constantly "m")})]
      (is (false? (:shed? no-reserve)) "fits the full window with no reserve")
      (is (true? (:shed? reserved)) "reserve pushes it past the effective window")
      (is (false? (:reserve-clamped? reserved)))
      (is (= (- window reserve) (:effective-window reserved)))))

  (testing "a reserve >= window is clamped so it can't zero the effective window"
    (let [r (assemble {:model "codellama-34b" :reserve 999999
                       :build-full (constantly "tiny")})]
      (is (true? (:reserve-clamped? r)))
      (is (pos? (:effective-window r)) "effective window is never zeroed")
      (is (false? (:shed? r)) "a trivial prompt still fits")
      (is (false? (:over-after-shed? r)))))

  (testing ":file-count echoes the supplied shed-count"
    (is (= 7 (:file-count (assemble {:model "codellama-34b" :shed-count 7}))))))
