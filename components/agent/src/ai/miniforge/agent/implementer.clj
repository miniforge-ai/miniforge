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
(ns ai.miniforge.agent.implementer
  "Implementer agent implementation.
   Generates code from plans and task descriptions."
  (:require
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.implementer.code :as impl-code]
   [ai.miniforge.agent.implementer.collect :as impl-collect]
   [ai.miniforge.agent.implementer.prompt :as impl-prompt]
   [ai.miniforge.agent.implementer.repair :as impl-repair]
   [ai.miniforge.agent.implementer.response :as impl-response]
   [ai.miniforge.agent.implementer.session :as impl-session]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.agent.submission-recovery :as submission-recovery]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.repo-index.interface :as messages]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workspace.interface :as workspace]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} normalized-artifact-source
  "Return a keyword artifact source for telemetry, defaulting malformed values to :none."
  [normalized]
  (let [source (get normalized :artifact-source)]
    (if (keyword? source) source :none)))

(defn- ^{:stratum 0} recover-implementer-submission
  "When the main turn produced usable analysis but NO artifact, run one short
   submission-only retry that asks the implementer to write the files now, then
   re-normalize. Returns the recovered normalized map, or nil when recovery
   produced no artifact either.

   When the recovery turn itself is cut by max_turns (exit-0 with
   stop_reason=max_turns), returns a sentinel
   `{::recovery-cut-by-max-turns? true :num-turns N :partial-files [...]
     :recovery-normalized {recovery LLM metadata}}` so the caller can emit a
   typed error that preserves the recovery turn's token/cost provenance.
   Generalizes PR #997's planner recovery."
  [{:keys [llm-client config context on-chunk working-dir effective-system-prompt
           input normalized logger]}]
  (log/info logger :implementer :implementer/submission-retry
            {:data {:llm-success? (llm/success? (:response normalized))
                    :content-length (count (:content normalized))}})
  (let [raw (submission-recovery/run-recovery-session
             {:context          context
              :llm-client       llm-client
              :on-chunk         on-chunk
              :effective-system effective-system-prompt
              :retry-prompt     (impl-prompt/submission-retry-prompt (impl-prompt/task->text input)
                                                         (:content normalized))
              :mcp-opts-fn      (fn [session]
                                  (artifact-session/write-cursor-permissions-for-session!
                                   session impl-session/implementer-disallowed-tools)
                                  (let [budget-usd      (budget/resolve-cost-budget-usd
                                                         :implementer config context)
                                        retry-max-turns (get @impl-prompt/implementer-prompt-data
                                                             :prompt/submission-retry-max-turns 6)
                                        retry-monitor   (impl-session/create-implementer-retry-progress-monitor)]
                                    (cond-> (artifact-session/session->mcp-opts
                                             session budget-usd retry-max-turns)
                                      true          (assoc :disallowed-tools impl-session/implementer-disallowed-tools)
                                      working-dir   (assoc :workdir working-dir)
                                      retry-monitor (assoc :progress-monitor retry-monitor))))})
        recovery-response   (:llm-result raw)
        recovery-answers    (:codex-answers raw)
        recovery-answer-log (:codex-answer-log raw)
        recovery-cut?       (and (llm/success? recovery-response)
                                 (= "max_turns" (:stop-reason recovery-response)))]
    (if recovery-cut?
      (let [partial-artifact (impl-collect/collect-session-artifact
                              context (:session-mode raw) working-dir
                              (:pre-session-snapshot raw))
            partial-files    (mapv :path (:code/files partial-artifact))]
        (log/warn logger :implementer :implementer/llm-max-turns-exceeded
                  {:data {:num-turns       (:num-turns recovery-response)
                          :tool-call-count (get recovery-response :tool-call-count
                                               (count (get recovery-response :tools-called [])))
                          :tools-called    (get recovery-response :tools-called [])
                          :partial-file-count (count partial-files)
                          :partial-files   partial-files
                          :working-dir     working-dir
                          :recovery-turn?  true}})
        {::recovery-cut-by-max-turns? true
         ::recovery-codex-answers recovery-answers
         ::recovery-codex-answer-log recovery-answer-log
         :num-turns    (:num-turns recovery-response)
         :partial-files partial-files
         ;; Recovery LLM metadata for error-response: tokens/cost from the
         ;; recovery turn, NOT the primary session, so phase accounting sees
         ;; the right usage. stop-reason and num-turns come from the recovery
         ;; response so they flow into :data without needing to be duplicated.
         :recovery-normalized {:stop-reason (:stop-reason recovery-response)
                               :num-turns   (:num-turns recovery-response)
                               :tokens      (:tokens recovery-response)
                               :cost-usd    (:cost-usd recovery-response)}})
      (let [recovered (impl-collect/normalize-implementer-result raw context working-dir)]
        (if (or (:structured-artifact recovered)
                (:parsed-content recovered)
                (:derived-artifact recovered))
          (assoc recovered
                 ::recovery-codex-answers recovery-answers
                 ::recovery-codex-answer-log recovery-answer-log)
          ;; No promotable artifact: the recovery turn still RAN, and any
          ;; answers it recorded are observations (SPEC $7.7.2) -- carry
          ;; them out on the sentinel-only shape the call site unwraps.
          ;; A torn recovery answer log (:unreadable) is itself an
          ;; observation about the channel and rides out the same way.
          (when (or (some? recovery-answers)
                    (= :unreadable recovery-answer-log))
            {::no-recovered-artifact? true
             ::recovery-codex-answers recovery-answers
             ::recovery-codex-answer-log recovery-answer-log}))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} invoke-with-llm
  "Invoke the implementer via the LLM backend."
  [llm-client user-prompt effective-system-prompt config context on-chunk logger
   existing-files input]
  (let [working-dir (workspace/resolve-execution-workdir context "implement")
        {:keys [llm-result artifact worktree-artifacts context-misses
                context-reads codex-answers codex-answer-log
                pre-session-snapshot session-mode]}
        (artifact-session/with-session context
          #(impl-session/invoke-implementer-session % llm-client user-prompt effective-system-prompt
                                       config context on-chunk existing-files working-dir))
        response llm-result
        ;; The LLM client cut the run at its `:max-total-ms` ceiling. The
        ;; files on disk are whatever the agent had written when the clock
        ;; ran out — mid-write on the 2026-09-04 trap-bench series 9 run,
        ;; which left a deps.edn without its :paths line and failed every
        ;; later verify. Such a turn is NOT promoted from the working tree;
        ;; it fails with the timeout envelope so the phase routes it as a
        ;; backend timeout (retry from the session checkpoint within the
        ;; iteration budget, then the terminal :implement/backend-timeout
        ;; verdict). Stream-idle / stagnation keep the container-promotion
        ;; precedence below: there the model finished writing and hung.
        cut-by-ceiling? (= :max-total (:llm/terminated-by response))
        ;; Claude CLI exited with code 0 but stop_reason="max_turns" — the
        ;; agent ran out of turns before finishing. Exit 0 makes the LLM
        ;; client classify this as success and returns {:success true,
        ;; :stop-reason "max_turns"}, so cut-by-ceiling? is false and the
        ;; partial files on disk would be silently promoted without this guard.
        ;; Mirrors the fix for :max-total (commit 2a2f9e7) but for the turns
        ;; budget rather than the wall-clock ceiling. Guard on success? so a
        ;; combined adaptive-timeout+max_turns failure ({:success false, ...})
        ;; still falls through to the :else path and preserves the full error.
        cut-by-max-turns? (and (llm/success? response)
                               (= "max_turns" (:stop-reason response)))
        file-artifact (impl-collect/collect-session-artifact context
                                                session-mode
                                                working-dir
                                                pre-session-snapshot)
        normalized (result-boundary/normalize-llm-result
                    {:role :implement
                     :response response
                     :worktree-artifacts worktree-artifacts
                     :artifact artifact
                     :fallback-artifact (when-not (or cut-by-ceiling?
                                                      cut-by-max-turns?)
                                          file-artifact)
                     :parse-response impl-code/parse-code-response
                     :derive-artifact impl-code/code-from-blocks})
        partial-files (mapv :path (:code/files file-artifact))]
    (when (seq context-misses)
      (log/info logger :implementer :implementer/context-cache-misses
                {:data {:miss-count (count context-misses)
                        :misses context-misses}}))
    (when cut-by-ceiling?
      (log/warn logger :implementer :implementer/llm-max-total-exceeded
                {:data {:elapsed-ms (get-in response [:error :timeout :elapsed-ms])
                        :max-ms (get-in response [:error :timeout :max-ms])
                        :tool-call-count (get response :tool-call-count
                                              (count (get response :tools-called [])))
                        :tools-called (get response :tools-called [])
                        :partial-file-count (count partial-files)
                        :partial-files partial-files
                        :working-dir working-dir}}))
    (when cut-by-max-turns?
      (log/warn logger :implementer :implementer/llm-max-turns-exceeded
                {:data {:num-turns (:num-turns response)
                        :tool-call-count (get response :tool-call-count
                                              (count (get response :tools-called [])))
                        :tools-called (get response :tools-called [])
                        :partial-file-count (count partial-files)
                        :partial-files partial-files
                        :working-dir working-dir}}))
    (when (and file-artifact (not (or cut-by-ceiling? cut-by-max-turns?)))
      (log/warn logger :implementer :implementer/file-artifact-fallback
                {:data {:file-count (count (:code/files file-artifact))
                        :tools-called (get response :tools-called [])}}))
    (log/info logger :implementer :implementer/llm-called
              {:data (cond-> {:success (llm/success? response)
                              :tokens (:tokens normalized)
                              :streaming? (boolean on-chunk)
                              :artifact-source (normalized-artifact-source normalized)
                              ;; Dir the agent wrote to / collection scanned —
                              ;; surfaces the worktree-path-vs-CWD resolution so
                              ;; a write/scan dir mismatch is diagnosable.
                              :working-dir working-dir
                              :tools-called (:tools-called normalized)}
                       (:llm/terminated-by response)
                       (assoc :terminated-by (:llm/terminated-by response))
                       (:stop-reason response)
                       (assoc :stop-reason (:stop-reason response))
                       (:num-turns response)
                       (assoc :num-turns (:num-turns response)))})
    ;; Container-promotion precedence: if the agent wrote files into
    ;; the worktree (file-artifact via collect-written-files) or
    ;; submitted via the MCP artifact path, honor that even when the
    ;; CLI itself terminated with a timeout/error. Iter-20 of the
    ;; planner-convergence dogfood showed Claude stalling AFTER a
    ;; successful stream of edits; the old path discarded a real
    ;; artifact because the LLM response was classified as failure.
    ;; Mirrors the planner fix in `(or worktree-plan (llm/success? …))`.
    ;; Submission recovery: if the turn produced usable analysis but NO
    ;; artifact (no files, no MCP submit, no parseable code) — the same
    ;; intermittent failure PR #997 fixed for the planner — run ONE short
    ;; submission-only retry that asks the implementer to write the files now.
    ;; Without this, a prose-only implement turn fails "no artifact found",
    ;; which is what stalled redirect-implements in the review-redirect churn.
    (let [submitted (:structured-artifact normalized)
          ;; Fold the derived artifact (code extracted from final-message
          ;; fences) into the "artifact already exists" guard so a turn that
          ;; produced usable code blocks does NOT trigger an unnecessary
          ;; recovery LLM call.
          parsed    (or (:parsed-content normalized) (:derived-artifact normalized))
          ;; No recovery turn after a ceiling or max-turns cut: the turn was
          ;; still working, not narrating; the phase-level retry resumes the
          ;; session instead.
          recovered (when (and (not cut-by-ceiling?)
                               (not cut-by-max-turns?)
                               (submission-recovery/submission-retry?
                                response submitted parsed (:content normalized)))
                      (recover-implementer-submission
                       {:llm-client llm-client :config config :context context
                        :on-chunk on-chunk :working-dir working-dir
                        :effective-system-prompt effective-system-prompt
                        :input input :normalized normalized :logger logger}))
          ;; recover-implementer-submission returns a sentinel map when the
          ;; recovery turn itself hits the max-turns budget, and an
          ;; answers-only sentinel when it produced no promotable artifact
          ;; but did record peg answers (SPEC $7.7.2 -- observations
          ;; survive artifact failure).
          recovery-codex-answers (::recovery-codex-answers recovered)
          recovery-answer-log (::recovery-codex-answer-log recovered)
          recovery-cut-by-max-turns? (::recovery-cut-by-max-turns? recovered)
          final     (if (or recovery-cut-by-max-turns?
                            (::no-recovered-artifact? recovered))
                      normalized
                      (or recovered normalized))
          ;; Container-promotion precedence: if the agent wrote files into
          ;; the worktree (file-artifact via collect-written-files) or
          ;; submitted via the MCP artifact path, honor that even when the
          ;; CLI itself terminated with a timeout/error. Iter-20 of the
          ;; planner-convergence dogfood showed Claude stalling AFTER a
          ;; successful stream of edits; the old path discarded a real
          ;; artifact because the LLM response was classified as failure.
          result    (cond
                      cut-by-ceiling?
                      ;; Ceiling cut: always a failed result, whatever is on
                      ;; disk. `:data` keeps the llm-error (with its
                      ;; `:timeout` envelope, which the phase classifies as
                      ;; a backend timeout) plus the marker and the partial
                      ;; files for post-mortem.
                      (result-boundary/error-response
                       final
                       (messages/t :error/llm-max-total-exceeded)
                       {:data {:llm/terminated-by :max-total
                               :partial-files partial-files}})

                      cut-by-max-turns?
                      ;; Max-turns cut: exit code 0 but stop_reason="max_turns"
                      ;; means the CLI exhausted the turn budget, not that the
                      ;; agent finished. Partial files are not promoted.
                      (result-boundary/error-response
                       final
                       (messages/t :error/llm-max-turns-exceeded)
                       {:data {:stop-reason "max_turns"
                               :num-turns (:num-turns response)
                               :partial-files partial-files}})

                      recovery-cut-by-max-turns?
                      ;; Recovery turn exhausted its own turn budget. Applies the
                      ;; same refusal as the primary-turn guard: partial files
                      ;; written by the recovery agent are not promoted.
                      ;; Use :recovery-normalized (recovery LLM metadata) as the
                      ;; first arg so error-response picks up the recovery turn's
                      ;; tokens/cost for phase accounting and propagates the
                      ;; recovery stop-reason / num-turns into :data without
                      ;; the primary session's values clobbering them.
                      (result-boundary/error-response
                       (:recovery-normalized recovered)
                       (messages/t :error/llm-max-turns-exceeded)
                       {:data {:partial-files  (:partial-files recovered)
                               :recovery-turn? true}})

                      (result-boundary/usable-content? final)
                      (impl-response/process-llm-response final context logger input)

                      :else
                      ;; LLM call failed, no artifact (even after recovery) —
                      ;; preserve the full llm-error shape into :data so the
                      ;; phase-completed event carries :type / :stderr /
                      ;; :stdout / :timeout / :exit-code for post-mortem.
                      (result-boundary/error-response final
                                                      (messages/t :error/llm-failed)))]
      ;; Session channel: which files the agent actually read (Codex SPEC
      ;; §7.4.2). The phase layer turns this into consultation provenance.
      ;; some?, not seq: an EMPTY reads log is "known: nothing was read",
      ;; which is a different fact from "no log surfaced" (capsule mode) —
      ;; dropping it would turn a real unread into unknown downstream.
      (cond-> result
        (some? context-reads) (assoc :context-reads context-reads)
        ;; §7.7.2 explicit answers: same some?-not-seq reasoning as reads.
        ;; Recovery-turn answers append AFTER the primary log so
        ;; last-recording-wins includes an answer revised during recovery.
        (or (some? codex-answers) (some? recovery-codex-answers))
        (assoc :codex-answers (vec (concat codex-answers recovery-codex-answers)))
        ;; §7.7.2 lost-vs-unanswered: the answer-log marker rides even
        ;; when no answers parsed — :unreadable is exactly the case
        ;; where codex-answers is nil but the record must say LOST.
        ;; Merged over the primary and recovery sessions: a torn log in
        ;; either means observations were lost.
        (or (some? codex-answer-log) (some? recovery-answer-log))
        (assoc :codex-answer-log
               (artifact-session/merge-answer-logs
                (keep identity [codex-answer-log recovery-answer-log])))))))

;------------------------------------------------------------------------------ Layer 2

;; Public API
(defn ^{:stratum 2} create-implementer
  "Create an Implementer agent with optional configuration overrides.

   Options:
   - :config - Agent configuration (model, temperature, etc.)
   - :logger - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        config (->> (merge (role-config/agent-llm-default :implementer)
                           (:config opts))
                    (model/apply-default-model :implementer)
                    (budget/apply-default-budget :implementer))]
    (specialized/create-base-agent
     {:role :implementer
      :system-prompt @impl-prompt/implementer-system-prompt
      :config config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :implementer
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              task-text (impl-prompt/task->text input)
              effective-system-prompt (impl-prompt/build-effective-system-prompt input)
              user-prompt (impl-prompt/build-user-prompt task-text input)]
          ;; Ground truth for which evidence sections this prompt carried —
          ;; stream dumps record the model's OUTPUT, not its input, so a
          ;; retry's prompt contents were unverifiable from the record.
          (when logger
            (log/info logger :implementer :implementer/prompt-sections
                      {:data {:sections (impl-prompt/task-sections input)
                              :prompt-chars (count user-prompt)}}))
          (if llm-client
            (invoke-with-llm llm-client user-prompt effective-system-prompt
                             config context on-chunk logger
                             (:task/existing-files input)
                             input)
            (response/error (messages/t :error/no-llm-backend)))))

      :validate-fn impl-code/validate-code-artifact

      :repair-fn impl-repair/repair-code-artifact})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def impl (create-implementer))

  (specialized/invoke impl
               {:language "clojure"
                :suggested-path "src/ai/miniforge/auth/login.clj"}
               {:task/description "Implement user login with email verification"
                :task/type :implement})

  (impl-code/validate-code-artifact
   {:code/id (random-uuid)
    :code/files [{:path "src/example.clj"
                  :content "(ns example)"
                  :action :create}]})

  (impl-code/code-summary {:code/id (random-uuid)
                 :code/files [{:path "a.clj" :content "" :action :create}
                              {:path "b.clj" :content "" :action :modify}]
                 :code/language "clojure"})

  :leave-this-here)
