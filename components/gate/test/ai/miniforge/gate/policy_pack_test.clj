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

(ns ai.miniforge.gate.policy-pack-test
  "Tests for the phase-scoped policy-pack gate (:policy-verify / :policy-review)."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.gate.policy-pack :as sut]
   [ai.miniforge.gate.registry :as registry]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Factories

(defn- content-scan-rule
  "A content-scan rule. Overrides merge over sensible defaults."
  [& {:keys [id phases action pattern severity]
      :or   {id       :test/forbidden
             phases   #{:verify :review}
             action   :hard-halt
             pattern  "FORBIDDEN"
             severity :critical}}]
  {:rule/id         id
   :rule/enabled?   true
   :rule/severity   severity
   :rule/applies-to {:phases phases}
   :rule/detection  {:type :content-scan :pattern pattern}
   :rule/enforcement {:action action :message (str "rule " id " fired")}})

(defn- pack
  "A single-rule (or supplied-rules) pack manifest."
  [& rules]
  {:pack/id    "test-pack"
   :pack/name  "Test Pack"
   :pack/rules (vec rules)})

(defn- ctx-with
  "Gate context carrying the given packs (bypasses the classpath standards
   pack so tests are hermetic)."
  [packs]
  {:policy-packs packs})

(def ^:private dirty-artifact
  {:artifact/content "(def x \"FORBIDDEN\")" :artifact/path "core.clj"})

(def ^:private clean-artifact
  {:artifact/content "(def x 42)" :artifact/path "core.clj"})

;------------------------------------------------------------------------------ No packs

(deftest no-packs-passes-with-warning-test
  (testing "with no packs the gate passes and records a no-packs warning (no regression)"
    (let [result (sut/check-policy-pack-for-phase :verify clean-artifact (ctx-with []))]
      (is (:passed? result))
      (is (= :no-policy-packs (-> result :warnings first :type))))))

;------------------------------------------------------------------------------ Blocking

(deftest hard-halt-rule-blocks-test
  (testing "a :hard-halt rule matching the artifact fails the gate with an error"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (not (:passed? result)))
      (is (seq (:errors result)))
      (is (= :test/forbidden (-> result :errors first :code))))))

(deftest clean-artifact-passes-test
  (testing "a clean artifact yields zero violations and passes"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify clean-artifact ctx)]
      (is (:passed? result))
      (is (empty? (:errors result))))))

;------------------------------------------------------------------------------ Phase scoping

(deftest phase-scoping-test
  (testing "a rule targeting only :review does NOT fire in verify, but DOES in review"
    (let [ctx (ctx-with [(pack (content-scan-rule :phases #{:review}))])]
      (is (:passed? (sut/check-policy-verify dirty-artifact ctx))
          "review-only rule must not gate verify")
      (is (not (:passed? (sut/check-policy-review dirty-artifact ctx)))
          "review-only rule must gate review"))))

;------------------------------------------------------------------------------ Severity / enforcement routing

(deftest warn-rule-records-warning-not-error-test
  (testing "a :warn rule surfaces as a warning and does not block"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify} :action :warn))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (:passed? result) "a :warn rule must not block")
      (is (empty? (:errors result)))
      (is (seq (:warnings result))))))

;------------------------------------------------------------------------------ Registry wiring

(deftest gates-registered-test
  (testing "both phase gates resolve through the registry with check + repair fns"
    (doseq [gate-kw [:policy-verify :policy-review]]
      (is (contains? (registry/list-gates) gate-kw))
      (let [{:keys [check repair]} (registry/get-gate gate-kw)]
        (is (fn? check))
        (is (fn? repair))))))

(deftest end-to-end-through-check-gate-test
  (testing "a :hard-halt violation fails when run through gate/check-gate (the real path)"
    (let [ctx    (assoc (ctx-with [(pack (content-scan-rule :phases #{:review}))])
                        :event-stream nil)
          result (gate/check-gate :policy-review dirty-artifact ctx)]
      (is (not (gate/passed? result))))))

;------------------------------------------------------------------------------ Repair

(deftest repair-is-redirect-test
  (testing "repair never fixes in-place — it signals a redirect"
    (let [result (sut/repair-policy-pack dirty-artifact [{:code :test/forbidden}] {})]
      (is (false? (:success? result)))
      (is (string? (:message result))))))

;------------------------------------------------------------------------------ Per-rule evidence (GROUP 2)

(defn- glob-scoped-rule
  "A content-scan rule that matches `phase` but is restricted to a file glob,
   so phase-matching but artifact-excluded rules can be exercised."
  [id phases glob]
  (assoc (content-scan-rule :id id :phases phases)
         :rule/applies-to {:phases phases :file-globs [glob]}))

(deftest classify-rules-statuses-test
  (testing "every enabled rule is classified into passed / failed / skipped-by-phase / not-applicable"
    (let [r-fail (content-scan-rule :id :r/fail :phases #{:verify})
          r-pass (content-scan-rule :id :r/pass :phases #{:verify} :pattern "NEVER-MATCHES")
          r-skip (content-scan-rule :id :r/skip :phases #{:review})
          r-na   (glob-scoped-rule :r/na #{:verify} "**/*.py")
          packs      [(pack r-fail r-pass r-skip r-na)]
          violations [{:rule r-fail :violation {:message "boom"}}]
          classified (#'sut/classify-rules packs :verify dirty-artifact {} violations)
          by-id      (into {} (map (juxt :rule-id :status)) classified)]
      (is (= :failed (by-id :r/fail)))
      (is (= :passed (by-id :r/pass)))
      (is (= :skipped-by-phase (by-id :r/skip)))
      (is (= :not-applicable (by-id :r/na)))
      (testing "a failed rule carries a non-sensitive violation summary + severity"
        (let [fail-rec (first (filter #(= :r/fail (:rule-id %)) classified))]
          (is (= "boom" (-> fail-rec :violation :message)))
          (is (not (contains? (:violation fail-rec) :matches))
              "raw matches must be redacted from evidence")
          (is (= :critical (:severity fail-rec))))))))

(deftest emits-rule-applied-events-test
  (testing "evaluation publishes one :gate/rule-applied event per enabled rule"
    (let [stream    (event-stream/create-event-stream {:sinks []})
          captured  (atom [])
          _         (event-stream/subscribe! stream :collector #(swap! captured conj %))
          ctx       (assoc (ctx-with [(pack (content-scan-rule :id :r/a :phases #{:verify})
                                            (content-scan-rule :id :r/b :phases #{:review}))])
                           :event-stream stream
                           :workflow/id "wf-evidence")
          _         (sut/check-policy-verify dirty-artifact ctx)
          rule-events (filterv #(= :gate/rule-applied (:event/type %)) @captured)
          by-id     (into {} (map (juxt :rule/id :rule/status)) rule-events)]
      (is (= 2 (count rule-events)) "one event per enabled rule")
      (is (= :failed (by-id :r/a)) "verify-phase rule violated → failed")
      (is (= :skipped-by-phase (by-id :r/b)) "review-only rule → skipped in verify")
      (is (every? #(= "wf-evidence" (:workflow/id %)) rule-events)))))

(deftest evidence-emission-never-breaks-the-gate-test
  (testing "the gate verdict stands even with no event stream wired"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (false? (:passed? result))))))
