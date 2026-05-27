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

(ns ai.miniforge.anomaly.interface.let-ok-test
  "let-ok threads anomaly-returning steps: it binds left-to-right,
   returns the first anomaly without evaluating later bindings, and
   otherwise evaluates its body like let."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest let-ok-runs-body-when-no-anomaly
  (testing "all bindings ok -> body evaluates like let, seeing the bindings"
    (is (= 3 (anomaly/let-ok [a 1 b 2] (+ a b))))
    (is (= 6 (anomaly/let-ok [a 1 b (+ a 2)] (* a b 2))))))

(deftest let-ok-returns-first-anomaly
  (testing "the first binding that yields an anomaly is returned"
    (let [boom (anomaly/anomaly :not-found "missing" {})]
      (is (= boom (anomaly/let-ok [a 1 b boom c 9] (+ a b c)))))))

(deftest let-ok-short-circuits-remaining-bindings
  (testing "bindings after the first anomaly are not evaluated"
    (let [boom (anomaly/anomaly :fault "boom" {})
          seen (atom [])]
      (is (= boom
             (anomaly/let-ok [a (do (swap! seen conj :a) 1)
                              b (do (swap! seen conj :b) boom)
                              c (do (swap! seen conj :c) 9)]
               ;; body references all three; it never runs (b short-circuits)
               (+ a b c))))
      (is (= [:a :b] @seen)
          "the :c binding must not run once :b is an anomaly"))))

(deftest let-ok-does-not-evaluate-body-on-anomaly
  (testing "the body is skipped when any binding is an anomaly"
    (let [boom (anomaly/anomaly :fault "boom" {})
          body-ran (atom false)]
      (is (= boom (anomaly/let-ok [a boom] (reset! body-ran true) a)))
      (is (false? @body-ran)))))

(deftest let-ok-empty-bindings-runs-body
  (testing "an empty binding vector simply evaluates the body"
    (is (= :ok (anomaly/let-ok [] :ok)))))

(defn- cause-chain-message
  "Walk the cause chain of `t`, returning the first message matching
   `pattern`, or nil. `macroexpand` wraps the IllegalArgumentException
   in a CompilerException, so the useful message is down the chain."
  [^Throwable t pattern]
  (loop [^Throwable t t]
    (cond
      (nil? t)                                  nil
      (re-find pattern (or (.getMessage t) "")) (.getMessage t)
      :else                                     (recur (.getCause t)))))

(defn- throws-on-expand?
  "True when `(macroexpand form)` throws with a cause-chain message
   matching `pattern`; false when nothing throws."
  [form pattern]
  (try
    #_:clj-kondo/ignore (macroexpand form)
    false
    (catch Throwable t
      (some? (cause-chain-message t pattern)))))

(deftest let-ok-rejects-non-vector-bindings
  (testing "a non-vector binding form throws at macroexpansion"
    (is (throws-on-expand?
         '(ai.miniforge.anomaly.interface/let-ok (a 1) :x)
         #"binding vector"))))

(deftest let-ok-rejects-odd-binding-count
  (testing "an odd-length binding vector throws at macroexpansion"
    (is (throws-on-expand?
         '(ai.miniforge.anomaly.interface/let-ok [a 1 b] :x)
         #"even number"))))
