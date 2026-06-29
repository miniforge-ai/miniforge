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

(ns ai.miniforge.policy-calibration.interface-test
  (:require [ai.miniforge.policy-calibration.config :as config]
            [ai.miniforge.policy-calibration.interface :as sut]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ^:private bar {:max-clean-fp 0 :min-recall 0.8})

(defn- cell-at [cells] (fn [rel t] (get cells [rel t])))

(defn- temp-resource-url
  [content]
  (let [f (java.io.File/createTempFile "policy-calibration-config-" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    (.toURL (.toURI f))))

(defn- unreadable-resource-url
  []
  (java.net.URL.
   nil
   "memory://policy-calibration-config-unreadable"
   (proxy [java.net.URLStreamHandler] []
     (openConnection [url]
       (proxy [java.net.URLConnection] [url]
         (connect [])
         (getInputStream []
           (throw (java.io.IOException. "unreadable"))))))))

(deftest load-config-invalid-resource-test
  (testing "missing config resource carries invalid-config ex-data"
    (let [ex (with-redefs [io/resource (constantly nil)]
               (try (config/load-config)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= "policy-calibration/config.edn" (:classpath/resource (ex-data ex))))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :missing-resource (:config/invalid-config-reason (ex-data ex))))))

  (testing "malformed EDN carries invalid-config ex-data"
    (let [ex (with-redefs [io/resource (constantly (temp-resource-url "{"))]
               (try (config/load-config)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :malformed-edn (:config/invalid-config-reason (ex-data ex))))))

  (testing "unreadable resource carries invalid-config ex-data"
    (let [ex (with-redefs [io/resource (constantly (unreadable-resource-url))]
               (try (config/load-config)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :unreadable-resource (:config/invalid-config-reason (ex-data ex))))))

  (testing "non-map EDN carries invalid-config ex-data"
    (let [ex (with-redefs [io/resource (constantly (temp-resource-url "[]"))]
               (try (config/load-config)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :not-a-map (:config/invalid-config-reason (ex-data ex))))))

  (testing "schema-invalid config carries invalid-config ex-data"
    (let [ex (with-redefs [io/resource (constantly (temp-resource-url "{}"))]
               (try (config/load-config)
                    (catch clojure.lang.ExceptionInfo e e)))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :invalid-config (:config/error (ex-data ex))))
      (is (= :schema-validation (:config/invalid-config-reason (ex-data ex))))
      (is (some? (:errors (ex-data ex)))))))

(deftest score-rule-clean-and-violating-test
  (testing "0 false-positives + full recall -> gate-ready"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/cell-success #{} {})
                    ["viol.clj" 0]  (sut/cell-success #{:r/x} {:r/x "bad"})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 0 (:clean-fp score)))
      (is (= 1.0 (:recall score)))
      (is (:gate-ready? score)))))

(deftest score-rule-false-positive-not-ready-test
  (testing "a false-fire on a clean fixture blocks gate-ready and reports the judge's reason"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/cell-success #{:r/x} {:r/x "false fire"})
                    ["viol.clj" 0]  (sut/cell-success #{:r/x} {:r/x "bad"})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 1 (:clean-fp score)))
      (is (not (:gate-ready? score)))
      (is (= "false fire" (:judge-said (first (:fp-cases score))))))))

(deftest score-rule-anomaly-cells-not-counted-test
  (testing "backend/format-error cells are neither clean passes nor recall misses; with no successful eval a rule can't be certified"
    (let [rule     {:rule/id :r/x}
          fixtures [{:rel "clean.clj" :seeded #{}} {:rel "viol.clj" :seeded #{:r/x}}]
          cells    {["clean.clj" 0] (sut/backend-error "judge down" {})
                    ["viol.clj" 0]  (sut/format-error "unparseable" {})}
          score    (sut/score-rule rule fixtures 1 bar (cell-at cells))]
      (is (= 0 (:clean-fp score)) "an errored clean cell is not a false positive")
      (is (= 2 (:failed score)))
      (is (not (:evaluated? score)) "no successful evaluation -> not certifiable")
      (is (not (:gate-ready? score))))))

(deftest aggregate-consensus-test
  (testing "gate-ready only if EVERY run passes; a flip is unstable and not ready"
    (let [ready {:clean-fp 0 :recall 1.0 :evaluated? true :failed 0 :gate-ready? true :fp-cases [] :fn-cases []}
          fail  {:clean-fp 1 :recall 1.0 :evaluated? true :failed 0 :gate-ready? false
                 :fp-cases [{:fixture "c.clj" :judge-said "x"}] :fn-cases []}]
      (let [a (sut/aggregate [ready ready ready] 3 1)]
        (is (:gate-ready? a))
        (is (:stable? a)))
      (let [a (sut/aggregate [ready fail ready] 3 1)]
        (is (not (:gate-ready? a)) "one failing run -> not gate-ready")
        (is (not (:stable? a)) "verdict flipped across runs -> unstable")
        (is (= [true false true] (:run-verdicts a)))
        (is (= 1 (:clean-fp-max a)))))))

(deftest calibrate-end-to-end-test
  (testing "calibrate produces per-rule verdicts from an injected judge (no LLM)"
    (let [rules    [{:rule/id :r/reliable} {:rule/id :r/flaky}]
          fixtures [{:rel "clean.clj" :seeded #{}}
                    {:rel "viol.clj" :seeded #{:r/reliable :r/flaky}}]
          ;; reliable fires exactly on seeded; flaky also false-fires on the clean fixture
          judge-fn (fn [_rules f]
                     (let [fp (when (= "clean.clj" (:rel f)) #{:r/flaky})]
                       (sut/cell-success (into (:seeded f) fp) {})))
          record   (sut/calibrate {:rules rules :fixtures fixtures :judge-fn judge-fn
                                   :runs 2 :trials 1 :max-parallel 2 :gate-bar bar})]
      (is (get-in record [:r/reliable :gate-ready?]) "reliable: 0 fp, full recall")
      (is (not (get-in record [:r/flaky :gate-ready?])) "flaky false-fires on clean")
      (is (true? (:stable? (get record :r/reliable)))))))

;; ---- build-time gate-readiness check ----

(deftest gate-check-test
  (testing "an acting semantic rule without a passing record is ungated; deterministic + non-acting rules are exempt"
    (let [rules  [{:rule/id :r/sem-ready  :rule/detection {:type :custom} :rule/enforcement {:action :hard-halt}}
                  {:rule/id :r/sem-bad    :rule/detection {:type :custom} :rule/enforcement {:action :hard-halt}}
                  ;; :require-approval also gates, so it must earn a record too
                  {:rule/id :r/appr-bad   :rule/detection {:type :custom} :rule/enforcement {:action :require-approval}}
                  {:rule/id :r/appr-ready :rule/detection {:type :custom} :rule/enforcement {:action :require-approval}}
                  {:rule/id :r/scan       :rule/detection {:type :content-scan :pattern "x"} :rule/enforcement {:action :hard-halt}}
                  {:rule/id :r/warn-sem   :rule/detection {:type :custom} :rule/enforcement {:action :warn}}]
          record {:r/sem-ready  {:gate-ready? true}  :r/sem-bad  {:gate-ready? false}
                  :r/appr-ready {:gate-ready? true}  :r/appr-bad {:gate-ready? false}}
          result (sut/gate-check rules record)]
      (is (not (:ok? result)))
      (is (= [:r/sem-bad :r/appr-bad] (:ungated result))
          "both acting actions (:hard-halt + :require-approval) that lack a passing record are ungated (content-scan + warn exempt)"))))

(deftest shipped-pack-gate-readiness-test
  (testing "every ACTING SEMANTIC rule (:hard-halt / :require-approval) in the shipped pack carries a passing calibration record"
    (let [{:keys [ok? ungated]} (sut/gate-check-shipped)]
      (is ok? (str "acting semantic rules lacking a passing calibration record "
                   "(sharpen the rule + recalibrate, or reclassify): " (pr-str ungated))))))
