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

(ns ai.miniforge.dag-executor.when-let-ok-test
  "Tests for the `when-let-ok` railway-binding macro.

   The macro is the canonical replacement for nested
   `(if-not (dag/ok? r) r (let [next-r ...] ...))` chains — see
   `.cursor/rules/languages/clojure-railway-binding.mdc` (dewey 211)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.interface :as dag]))

(deftest body-runs-when-all-bindings-ok
  (testing "all bindings ok → body evaluates with bindings in scope"
    (let [r (dag/when-let-ok [a (dag/ok 1)
                              b (dag/ok 2)
                              c (dag/ok 3)]
              (dag/ok {:sum (+ (:data a) (:data b) (:data c))}))]
      (is (dag/ok? r))
      (is (= 6 (-> r :data :sum))))))

(deftest short-circuits-on-first-err
  (testing "first err binding is returned verbatim; subsequent exprs not evaluated"
    (let [side-effects (atom 0)
          r (dag/when-let-ok [_a (dag/ok 1)
                              _b (dag/err :boom "step-2 failed" {:step 2})
                              _c (do (swap! side-effects inc)
                                     (dag/ok 3))]
              (do (swap! side-effects inc)
                  (dag/ok :unreached)))]
      (is (not (dag/ok? r)))
      (is (= :boom (get-in r [:error :code])))
      (is (= "step-2 failed" (get-in r [:error :message])))
      (is (zero? @side-effects)
          "step-3 and body must not run after step-2's err"))))

(deftest short-circuits-on-first-err-when-first
  (testing "first binding itself fails → that err returned, no further bindings or body"
    (let [side-effects (atom 0)
          r (dag/when-let-ok [_a (dag/err :first "step-1 failed" {})
                              _b (do (swap! side-effects inc)
                                     (dag/ok 2))]
              (do (swap! side-effects inc)
                  (dag/ok :unreached)))]
      (is (= :first (get-in r [:error :code])))
      (is (zero? @side-effects)))))

(deftest bindings-are-sequential-and-visible-downstream
  (testing "each binding sees prior bindings (lexically chained)"
    (let [r (dag/when-let-ok [a (dag/ok 10)
                              b (dag/ok (+ (:data a) 5))
                              c (dag/ok (* (:data b) 2))]
              (dag/ok {:a (:data a) :b (:data b) :c (:data c)}))]
      (is (dag/ok? r))
      (is (= {:a 10 :b 15 :c 30} (:data r))))))

(deftest empty-binding-vector-evaluates-body
  (testing "no bindings is allowed; body runs directly"
    (let [r (dag/when-let-ok [] (dag/ok :no-bindings))]
      (is (dag/ok? r))
      (is (= :no-bindings (:data r))))))

(defn- ^:private cause-chain-message
  "Walk the cause chain of `t` and return the first message that
   matches `pattern`, or nil. Useful because Clojure's `macroexpand`
   wraps the IllegalArgumentException in a `CompilerException`."
  [^Throwable t pattern]
  (loop [^Throwable t t]
    (cond
      (nil? t)                           nil
      (re-find pattern (or (.getMessage t) ""))  (.getMessage t)
      :else                              (recur (.getCause t)))))

(defn- matches-cause-on-throw?
  "True when `(macroexpand form)` throws a Throwable whose cause
   chain has a message matching `pattern`. False if no exception
   thrown OR the pattern doesn't appear anywhere in the chain.

   Used because Clojure wraps macroexpansion failures in
   `CompilerException` — the underlying message we care about
   (e.g. `IllegalArgumentException: when-let-ok requires …`)
   surfaces only via `.getCause`."
  [form pattern]
  (try
    ;; Force the expansion. Successful expansion returns the expanded
    ;; form — we don't need it, just the absence of a throw. The
    ;; explicit `false` after is the no-match signal that the
    ;; `catch` clause replaces on actual failure.
    #_:clj-kondo/ignore (macroexpand form)
    false
    (catch Throwable t
      (some? (cause-chain-message t pattern)))))

(deftest rejects-non-vector-bindings
  (testing "non-vector binding form throws at macroexpansion"
    (is (matches-cause-on-throw?
         '(ai.miniforge.dag-executor.interface/when-let-ok (a 1) (dag/ok 2))
         #"binding vector"))))

(deftest rejects-odd-binding-count
  (testing "odd-length binding vector throws at macroexpansion"
    (is (matches-cause-on-throw?
         '(ai.miniforge.dag-executor.interface/when-let-ok [a 1 b] (dag/ok :x))
         #"even number"))))
