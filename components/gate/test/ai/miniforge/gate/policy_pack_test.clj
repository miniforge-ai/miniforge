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
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Factories
(defn- ^{:stratum 0} content-scan-rule
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

(defn- ^{:stratum 0} pack
  "A single-rule (or supplied-rules) pack manifest."
  [& rules]
  {:pack/id    "test-pack"
   :pack/name  "Test Pack"
   :pack/rules (vec rules)})

(defn- ^{:stratum 0} ctx-with
  "Gate context carrying the given packs (bypasses the classpath standards
   pack so tests are hermetic)."
  [packs]
  {:policy-packs packs})

(def ^{:stratum 0} ^:private dirty-artifact
  {:artifact/content "(def x \"FORBIDDEN\")" :artifact/path "core.clj"})

(def ^{:stratum 0} ^:private clean-artifact
  {:artifact/content "(def x 42)" :artifact/path "core.clj"})

(def ^{:stratum 0} ^:private dirty-code-artifact
  {:code/files [{:path "src/core.clj"
                 :content "(def x \"FORBIDDEN\")"
                 :action :modify}]})

(defn- ^{:stratum 0} temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "policy-gate"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} write-worktree-file!
  [worktree path content]
  (let [file (io/file worktree path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

;------------------------------------------------------------------------------ Semantic seam
(defn- ^{:stratum 0} semantic-rule
  "A heuristic `:custom` rule with no `:custom-fn` — resolves to the `:semantic`
   (LLM-judge) detector. Defaults to an acting (:hard-halt) enforcement action."
  [& {:keys [id phases action severity]
      :or   {id :test/semantic phases #{:verify :review}
             action :hard-halt severity :high}}]
  {:rule/id          id
   :rule/enabled?    true
   :rule/severity    severity
   :rule/applies-to  {:phases phases}
   :rule/detection   {:type :custom}
   :rule/enforcement {:action action :message (str "rule " id " fired")}})

(defn- ^{:stratum 0} recording-analyze-fn
  "Stands in for `semantic-analyzer/analyze-rule`. Records the args the gate
   passes (proving the injected wiring) and reports one violation."
  [recorder]
  (fn [llm-client complete-fn repo-path rule]
    (reset! recorder {:llm-client  llm-client
                      :complete-fn complete-fn
                      :repo-path   repo-path
                      :rule-id     (:rule/id rule)})
    {:rule/id    (:rule/id rule)
     :status     :failed
     :violations [{:path "src/core.clj" :message "semantic issue"}]}))

;------------------------------------------------------------------------------ Registry wiring
(deftest ^{:stratum 0} gates-registered-test
  (testing "both phase gates resolve through the registry with check + repair fns"
    (doseq [gate-kw [:policy-verify :policy-review]]
      (is (contains? (registry/list-gates) gate-kw))
      (let [{:keys [check repair]} (registry/get-gate gate-kw)]
        (is (fn? check))
        (is (fn? repair))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} ctx-with-implement-artifact
  [packs artifact]
  (assoc-in (ctx-with packs)
            [:execution/phase-results :implement :artifact]
            artifact))

;------------------------------------------------------------------------------ No packs
(deftest ^{:stratum 1} no-packs-passes-with-warning-test
  (testing "with no packs the gate passes and records a no-packs warning (no regression)"
    (let [result (sut/check-policy-pack-for-phase :verify clean-artifact (ctx-with []))]
      (is (:passed? result))
      (is (= :no-policy-packs (-> result :warnings first :type))))))

;------------------------------------------------------------------------------ Blocking
(deftest ^{:stratum 1} hard-halt-rule-blocks-test
  (testing "a :hard-halt rule matching the artifact fails the gate with an error"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (not (:passed? result)))
      (is (seq (:errors result)))
      (is (= :test/forbidden (-> result :errors first :code))))))

(deftest ^{:stratum 1} compile-failure-fails-with-error-test
  (testing "an unbindable compiled rule fails closed with a gate error"
    (let [rule   (assoc (content-scan-rule :phases #{:verify})
                        :rule/detection {:type :not-a-detector})
          ctx    (ctx-with [(pack rule)])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (not (:passed? result)))
      (is (= :policy-pack/compile-failed (-> result :errors first :code))))))

(deftest ^{:stratum 1} compile-failure-does-not-emit-rule-applied-events-test
  (testing "no per-rule applied evidence is emitted when pack compilation fails"
    (let [stream      (event-stream/create-event-stream {:sinks []})
          captured    (atom [])
          _           (event-stream/subscribe! stream :collector #(swap! captured conj %))
          broken-rule (assoc (content-scan-rule :phases #{:verify})
                             :rule/detection {:type :not-a-detector})
          ctx         (assoc (ctx-with [(pack broken-rule)])
                             :event-stream stream
                             :workflow/id "wf-compile-failed")
          result      (sut/check-policy-verify dirty-artifact ctx)]
      (is (not (:passed? result)))
      (is (empty? (filter #(= :gate/rule-applied (:event/type %)) @captured))))))

(deftest ^{:stratum 1} clean-artifact-passes-test
  (testing "a clean artifact yields zero violations and passes"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify clean-artifact ctx)]
      (is (:passed? result))
      (is (empty? (:errors result))))))

(deftest ^{:stratum 1} semantic-acting-rule-runs-judge-and-blocks-test
  (testing "a :hard-halt semantic rule runs the judge and blocks; the gate
            derives :llm-client from :llm-backend and sets :complete-fn"
    (let [rec    (atom nil)
          ctx    (assoc (ctx-with [(pack (semantic-rule :phases #{:verify}
                                                        :action :hard-halt))])
                        :llm-backend ::client
                        :semantic-analyze-fn (recording-analyze-fn rec))
          result (sut/check-policy-verify dirty-code-artifact ctx)]
      (is (not (:passed? result)))
      (is (seq (:errors result)))
      (is (= ::client (:llm-client @rec)) ":llm-client derived from :llm-backend")
      (is (fn? (:complete-fn @rec)) ":complete-fn injected"))))

(deftest ^{:stratum 1} semantic-non-acting-rule-skips-judge-test
  (testing "a :warn semantic rule is guidance — the judge is not invoked and the
            gate passes (no LLM cost for a finding that could only warn)"
    (let [rec    (atom nil)
          ctx    (assoc (ctx-with [(pack (semantic-rule :phases #{:verify}
                                                        :action :warn))])
                        :llm-backend ::client
                        :semantic-analyze-fn (recording-analyze-fn rec))
          result (sut/check-policy-verify dirty-code-artifact ctx)]
      (is (:passed? result))
      (is (nil? @rec) "judge must not run for a non-acting semantic rule"))))

(deftest ^{:stratum 1} semantic-acting-rule-without-wiring-fails-closed-test
  (testing "a :hard-halt semantic rule with no LLM backend fails closed — a
            missing-wiring violation blocks rather than silently passing"
    (let [ctx    (ctx-with [(pack (semantic-rule :phases #{:verify}
                                                 :action :hard-halt))])
          result (sut/check-policy-verify dirty-code-artifact ctx)]
      (is (not (:passed? result)))
      (is (seq (:errors result))))))

(deftest ^{:stratum 1} semantic-judge-scoped-to-changed-files-test
  (testing "with no explicit analyze-fn, the gate injects a changed-files-scoped
            judge: the LLM prompt carries the artifact's changed file content,
            not a whole-repo scan"
    (let [seen          (atom nil)
          fake-complete (fn [_client request]
                          (reset! seen request)
                          {:content "[{:line 1 :message \"semantic issue\"}]"})
          ctx           (assoc (ctx-with [(pack (semantic-rule :phases #{:verify}
                                                              :action :hard-halt))])
                               :llm-backend ::client
                               :complete-fn fake-complete)
          result        (sut/check-policy-verify dirty-code-artifact ctx)]
      (is (not (:passed? result)))
      (is (seq (:errors result)))
      (is (re-find #"FORBIDDEN"
                   (-> @seen :messages first :content))
          "judge prompt must contain the changed file's content"))))

(deftest ^{:stratum 1} semantic-rules-batched-into-one-call-per-file-test
  (testing "two acting semantic rules are judged in ONE batched call per changed
            file, not one call per rule; both rules' violations are distributed"
    (let [calls         (atom 0)
          fake-complete (fn [_client _request]
                          (swap! calls inc)
                          {:content (str "[{:rule-id :test/sem-a :line 1 :message \"a\"} "
                                         "{:rule-id :test/sem-b :line 1 :message \"b\"}]")})
          ctx           (assoc (ctx-with
                                [(pack (semantic-rule :id :test/sem-a :phases #{:verify}
                                                      :action :hard-halt)
                                       (semantic-rule :id :test/sem-b :phases #{:verify}
                                                      :action :hard-halt))])
                               :llm-backend ::client
                               :complete-fn fake-complete)
          result        (sut/check-policy-verify dirty-code-artifact ctx)]
      (is (= 1 @calls) "one batched LLM call for two semantic rules on one file")
      (is (not (:passed? result)))
      (is (= 2 (count (:errors result))) "both rules block"))))

;------------------------------------------------------------------------------ Phase scoping
(deftest ^{:stratum 1} phase-scoping-test
  (testing "a rule targeting only :review does NOT fire in verify, but DOES in review"
    (let [ctx (ctx-with [(pack (content-scan-rule :phases #{:review}))])]
      (is (:passed? (sut/check-policy-verify dirty-artifact ctx))
          "review-only rule must not gate verify")
      (is (not (:passed? (sut/check-policy-review dirty-artifact ctx)))
          "review-only rule must gate review"))))

;------------------------------------------------------------------------------ Severity / enforcement routing
(deftest ^{:stratum 1} warn-rule-records-warning-not-error-test
  (testing "a :warn rule surfaces as a warning and does not block"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify} :action :warn))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (:passed? result) "a :warn rule must not block")
      (is (empty? (:errors result)))
      (is (seq (:warnings result))))))

(deftest ^{:stratum 1} end-to-end-through-check-gate-test
  (testing "a :hard-halt violation fails when run through gate/check-gate (the real path)"
    (let [ctx    (assoc (ctx-with [(pack (content-scan-rule :phases #{:review}))])
                        :event-stream nil)
          result (gate/check-gate :policy-review dirty-artifact ctx)]
      (is (not (gate/passed? result))))))

;------------------------------------------------------------------------------ Repair
(deftest ^{:stratum 1} repair-is-redirect-test
  (testing "repair never fixes in-place — it signals a redirect"
    (let [result (sut/repair-policy-pack dirty-artifact [{:code :test/forbidden}] {})]
      (is (false? (:success? result)))
      (is (string? (:message result))))))

;------------------------------------------------------------------------------ Per-rule evidence (GROUP 2)
(defn- ^{:stratum 1} glob-scoped-rule
  "A content-scan rule that matches `phase` but is restricted to a file glob,
   so phase-matching but artifact-excluded rules can be exercised."
  [id phases glob]
  (assoc (content-scan-rule :id id :phases phases)
         :rule/applies-to {:phases phases :file-globs [glob]}))

(deftest ^{:stratum 1} classify-rules-guidance-status-test
  (testing "an applicable non-acting semantic rule is classified :guidance, not
            :passed — the judge was skipped, so evidence must not imply it ran"
    (let [r-guid     (semantic-rule :id :r/guid :phases #{:verify} :action :warn)
          classified (#'sut/classify-rules [(pack r-guid)] :verify
                                           dirty-code-artifact {} [])
          by-id      (into {} (map (juxt :rule-id :status)) classified)]
      (is (= :guidance (by-id :r/guid))))))

(deftest ^{:stratum 1} emits-rule-applied-events-test
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

(deftest ^{:stratum 1} evidence-emission-never-breaks-the-gate-test
  (testing "the gate verdict stands even with no event stream wired"
    (let [ctx    (ctx-with [(pack (content-scan-rule :phases #{:verify}))])
          result (sut/check-policy-verify dirty-artifact ctx)]
      (is (false? (:passed? result))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} review-policy-uses-implement-artifact-test
  (testing "review policy evaluates code under review, not the review verdict artifact"
    (let [ctx    (ctx-with-implement-artifact
                  [(pack (content-scan-rule :phases #{:review}))]
                  dirty-code-artifact)
          result (sut/check-policy-review {:review/decision :approved} ctx)]
      (is (not (:passed? result)))
      (is (= :test/forbidden (-> result :errors first :code))))))

(deftest ^{:stratum 2} verify-policy-uses-implement-artifact-test
  (testing "verify policy evaluates code under review when verify output is metadata-only"
    (let [ctx    (ctx-with-implement-artifact
                  [(pack (content-scan-rule :phases #{:verify}))]
                  dirty-code-artifact)
          result (sut/check-policy-verify {:status :success :summary "tests passed"} ctx)]
      (is (not (:passed? result)))
      (is (= :test/forbidden (-> result :errors first :code))))))

(deftest ^{:stratum 2} path-only-implement-artifact-defaults-missing-actions-test
  (testing "path-only artifacts rehydrate files even when actions are omitted"
    (let [worktree (temp-dir)
          _        (write-worktree-file! worktree "src/core.clj"
                                         "(def x \"FORBIDDEN\")")
          ctx      (-> (ctx-with-implement-artifact
                        [(pack (content-scan-rule :phases #{:verify}))]
                        {:code/file-paths ["src/core.clj"]})
                       (assoc :execution/worktree-path (.getPath worktree)))
          result   (sut/check-policy-verify {:status :success} ctx)]
      (is (not (:passed? result)))
      (is (= :test/forbidden (-> result :errors first :code))))))

(deftest ^{:stratum 2} path-only-implement-artifact-does-not-read-outside-worktree-test
  (testing "rehydration does not follow path traversal outside the worktree"
    (let [root     (temp-dir)
          worktree (io/file root "worktree")
          secret   (io/file root "secret.clj")
          _        (.mkdirs worktree)
          _        (spit secret "(def x \"FORBIDDEN\")")
          ctx      (-> (ctx-with-implement-artifact
                        [(pack (content-scan-rule :phases #{:verify}))]
                        {:code/file-paths ["../secret.clj"]})
                       (assoc :execution/worktree-path (.getPath worktree)))
          result   (sut/check-policy-verify {:status :success} ctx)]
      (is (:passed? result)))))

(deftest ^{:stratum 2} path-only-delete-action-rehydrates-empty-content-test
  (testing "deleted files do not rehydrate old worktree content"
    (let [worktree (temp-dir)
          _        (write-worktree-file! worktree "src/core.clj"
                                         "(def x \"FORBIDDEN\")")
          ctx      (-> (ctx-with-implement-artifact
                        [(pack (content-scan-rule :phases #{:verify}))]
                        {:code/file-paths   ["src/core.clj"]
                         :code/file-actions [:delete]})
                       (assoc :execution/worktree-path (.getPath worktree)))
          result   (sut/check-policy-verify {:status :success} ctx)]
      (is (:passed? result)))))

(deftest ^{:stratum 2} classify-rules-statuses-test
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
