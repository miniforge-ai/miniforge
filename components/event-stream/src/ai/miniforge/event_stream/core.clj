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

(ns ai.miniforge.event-stream.core
  "Event bus and event constructors for workflow observability."
  (:require
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.event-stream.snowflake :as snowflake]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.event-stream.sinks :as sinks]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const event-version "1.0.0")

(def ^:private redirect-transition-type
  :transition/redirect)

(def ^:private phase-transition-request-key
  :phase/transition-request)

(def ^:private transition-type-key
  :transition/type)

(def ^:private transition-target-key
  :transition/target)

(defn- phase-transition-request
  [result]
  (get result phase-transition-request-key))

(defn- redirect-target
  "Project a redirect target for legacy consumers.

   The workflow runner now emits :phase/transition-request. This helper keeps
   :phase/redirect-to available only when the transition request represents a
  redirect, so older event consumers do not break while the newer event shape
  remains authoritative."
  [result]
  (let [request (phase-transition-request result)
        transition-type (get request transition-type-key)]
    (when (= redirect-transition-type transition-type)
      (get request transition-target-key))))

;------------------------------------------------------------------------------ Layer 0
;; Event persistence via configurable sinks
;; Note: File persistence moved to sinks.clj for configurability

;------------------------------------------------------------------------------ Layer 0
;; Event envelope constructor

(defn create-envelope
  "Create an event envelope with sequence numbering.

   When the stream carries a `:snowflake-generator` (BD-2b), the
   envelope's `:event/id` is a snowflake-encoded UUID — the
   most-significant 64 bits encode (epoch-ms timestamp, worker id,
   per-ms sequence) per the RFC, so events sort lexically by creation
   order. Streams without a generator fall back to a random UUID;
   `:event/id` stays a `uuid?` either way for downstream compatibility.

   Identity propagation (Decision 14 of miniforge-fleet's Phase E
   plan): every envelope can carry org / workspace / repo / auth
   context so subscribers can scope authorization without
   retrofitting the event log later. Fields default off; pass any
   subset via the options map. The OPTIONS arity is the
   recommended call shape; the legacy 4-arg arity stays for
   in-tree callers that don't have identity context yet.

   Options:
     :org/id        — UUID. Org tenancy boundary.
     :workspace/id  — UUID. Workspace tenancy boundary.
     :repo/id       — string. Slug-style repo id when applicable.
     :auth/context  — map. Caller-shaped auth context.
     :event/parent-id — UUID. Parent event id for causality.
     :agent/id        — keyword. Agent that emitted the event.
     :agent/instance-id — UUID. Specific agent instance."
  ([stream event-type workflow-id message]
   (create-envelope stream event-type workflow-id message {}))
  ([stream event-type workflow-id message opts]
   ;; `swap-vals!` returns [old-state new-state] atomically, so we
   ;; pull the seq value and increment it in a single CAS — no
   ;; window where two concurrent producers see the same number.
   ;; The previous read-then-swap pattern WAS racey; reviewers
   ;; flagged it on PR #814.
   (let [[old _new] (swap-vals! stream
                                update-in
                                [:sequence-numbers workflow-id]
                                (fnil inc 0))
         seq-num    (get-in old [:sequence-numbers workflow-id] 0)
         generator  (:snowflake-generator @stream)]
     (cond-> {:event/type event-type
              :event/id (if generator
                          (snowflake/next-id! generator)
                          (random-uuid))
              :event/timestamp (java.util.Date.)
              :event/version event-version
              :event/sequence-number seq-num
              :workflow/id workflow-id
              :message message}
       (:org/id opts)            (assoc :org/id (:org/id opts))
       (:workspace/id opts)      (assoc :workspace/id (:workspace/id opts))
       (:repo/id opts)           (assoc :repo/id (:repo/id opts))
       (:auth/context opts)      (assoc :auth/context (:auth/context opts))
       (:event/parent-id opts)   (assoc :event/parent-id (:event/parent-id opts))
       (:agent/id opts)          (assoc :agent/id (:agent/id opts))
       (:agent/instance-id opts) (assoc :agent/instance-id (:agent/instance-id opts))))))

;------------------------------------------------------------------------------ Layer 1
;; Event bus operations

(defn create-event-stream
  "Create an event stream with configurable sinks.

   Options:
     :logger              - Optional logger instance
     :sinks               - Vector of sink functions (default: file sink)
     :config              - Config map to create sinks from
     :snowflake-generator - Optional snowflake event-id generator
                            (BD-2b). When supplied, `create-envelope`
                            uses it for `:event/id` so events sort
                            lexically by creation order. Without it,
                            `:event/id` falls back to `random-uuid`
                            and the file sink uses the legacy
                            `{timestamp}-{uuid}.json` filename.

   Returns: Event stream atom

   Example:
     ;; Default file sink
     (create-event-stream)

     ;; Custom sinks
     (create-event-stream {:sinks [(sinks/file-sink) (sinks/stdout-sink)]})

     ;; From config
     (create-event-stream {:config user-config})

     ;; With a Snowflake event-id generator (BD-2b)
     (create-event-stream {:snowflake-generator (snowflake/create-generator)})"
  [& [opts]]
  (let [;; Create sinks from config or use provided sinks or default
        event-sinks (cond
                      (:sinks opts) (:sinks opts)
                      (:config opts) (sinks/create-sinks-from-config (:config opts))
                      :else [(sinks/file-sink)])] ;; Default to file sink
    (atom {:events []
           :subscribers {}
           :filters {}
           :sequence-numbers {}
           :logger (:logger opts)
           :sinks event-sinks
           ;; BD-2a: workflow-scoped publisher fence. Once a workflow id
           ;; is in this set, `publish!` rejects further events for that
           ;; workflow rather than running sinks/subscribers.
           :quiesced-workflows #{}
           ;; BD-2a: in-flight publish counter. `drain!` waits on this
           ;; reaching zero before draining sinks. `publish!` increments
           ;; on entry (after the quiesce check) and decrements in a
           ;; finally so a sink exception still releases the slot.
           :in-flight 0
           ;; BD-2b: optional Snowflake event-id generator. When present,
           ;; `create-envelope` calls it for `:event/id` so events sort
           ;; lexically by creation order. nil = random-uuid fallback.
           :snowflake-generator (:snowflake-generator opts)})))

;------------------------------------------------------------------------------ Layer 0
;; publish! helpers — small, single-purpose pieces composed by publish!
;; itself. Each helper is testable in isolation; tests live in
;; `publish_helpers_test.clj`.

(defn- workflow-quiesced?
  "True when `event`'s workflow id has been fenced via `quiesce!`."
  [stream event]
  (when-let [wid (:workflow/id event)]
    (contains? (:quiesced-workflows @stream) wid)))

(defn- rejection-result
  "Build the structured rejection map returned to a publisher whose
   workflow has been quiesced. Stable shape so callers can pattern-match."
  [event reason]
  {:rejected?   true
   :reason      reason
   :workflow-id (:workflow/id event)
   :event-type  (:event/type event)})

(defn- log-rejection!
  "Surface a quiesce rejection at warn level if a logger is configured."
  [logger event]
  (when logger
    (log/warn logger :event-stream :event/rejected-after-quiesce
              {:message "publish! rejected: workflow quiesced"
               :data    {:event-type  (:event/type event)
                         :workflow-id (:workflow/id event)}})))

(defn- rejection-if-quiesced
  "Return the structured rejection map when `event`'s workflow is fenced,
   else nil. Logs the rejection as a side effect so callers don't have
   to do it twice."
  [stream event]
  (when (workflow-quiesced? stream event)
    (log-rejection! (:logger @stream) event)
    (rejection-result event :workflow-quiesced)))

(def ^:private quiesced-sentinel
  "Sentinel value returned by `with-in-flight` when the event's workflow was
   quiesced between the caller's fast-path check and the atomic increment,
   closing the TOCTOU window. Distinct from nil so callers can distinguish
   'quiesced-during-acquire' from 'no-workflow-id event'."
  ::quiesced)

(defn- try-acquire-in-flight!
  "Atomically check the quiesce fence for `event`'s workflow and, if not
   fenced, increment the in-flight counter.

   Uses a decision atom captured inside the `swap!` fn to signal the outcome
   to the caller without polluting the stream map with temporary keys. The
   `swap!` fn may be retried by the atom under contention — the decision atom
   is reset on each retry so the final value always reflects the last
   successful swap.

   Returns true when the slot was acquired (in-flight incremented), false
   when the workflow was quiesced at the moment of the swap."
  [stream event]
  (let [wid      (:workflow/id event)
        acquired (volatile! false)]
    (swap! stream
           (fn [s]
             (if (and wid (contains? (:quiesced-workflows s) wid))
               (do (vreset! acquired false) s)
               (do (vreset! acquired true)
                   (update s :in-flight inc)))))
    @acquired))

(defn- with-in-flight
  "Run `body-fn` while incrementing the stream's in-flight publish
   counter, decrementing in a finally so an exception still releases the
   slot. Returns body-fn's result, or `quiesced-sentinel` when the
   workflow was fenced at the moment of the atomic acquire.

   The quiesce check and increment are fused into a single `swap!` via
   `try-acquire-in-flight!`, eliminating the TOCTOU window that existed
   when the two operations were separate (check in `rejection-if-quiesced`
   then increment in `swap! update :in-flight inc`)."
  [stream event body-fn]
  (if-not (try-acquire-in-flight! stream event)
    quiesced-sentinel
    (try
      (body-fn)
      (finally
        (swap! stream update :in-flight dec)))))

(defn- record-event!
  "Append `event` to the in-memory event log."
  [stream event]
  (swap! stream update :events conj event))

(defn- deliver-to-sink!
  "Invoke `sink` with `event`, swallowing exceptions and logging them.
   A failing sink must not break others or the publish path."
  [sink event logger]
  (try
    (sink event)
    (catch Exception e
      (when logger
        (log/warn logger :event-stream :sink-error
                  {:message "Event sink failed"
                   :data    {:event-type (:event/type event)
                             :anomaly    (response/from-exception e)}})))))

(defn- deliver-to-sinks!
  "Fan `event` out to every configured sink. Each sink runs in
   isolation via `deliver-to-sink!`."
  [sinks event logger]
  (doseq [sink sinks]
    (deliver-to-sink! sink event logger)))

(defn- deliver-to-subscriber!
  "Invoke `callback` for `event` when `filter-fn` accepts it,
   swallowing exceptions and logging them."
  [sub-id callback filter-fn event logger]
  (when (filter-fn event)
    (try
      (callback event)
      (catch Exception e
        (when logger
          (log/error logger :event-stream :event/callback-error
                     {:message "Event callback failed"
                      :data    {:subscriber-id sub-id
                                :event-type    (:event/type event)
                                :anomaly       (response/from-exception e)}}))))))

(defn- deliver-to-subscribers!
  "Fan `event` out to every subscriber that accepts it via its filter."
  [subscribers filters event logger]
  (doseq [[sub-id callback] subscribers]
    (let [filter-fn (get filters sub-id (constantly true))]
      (deliver-to-subscriber! sub-id callback filter-fn event logger))))

(defn- log-published!
  "Debug-log that `event` reached the subscribers."
  [logger event]
  (when logger
    (log/debug logger :event-stream :event/published
               {:message "Event published"
                :data    {:event-type  (:event/type event)
                          :workflow-id (:workflow/id event)
                          :sequence    (:event/sequence-number event)}})))

;------------------------------------------------------------------------------ Layer 1
;; publish! orchestrates the helpers above as a small pipeline.

(defn publish!
  "Publish `event` to the stream.

   When the event's workflow has been quiesced (BD-2a), short-circuits
   with a structured `{:rejected? true ...}` map and runs no sinks or
   subscribers. Otherwise: fan out to sinks, append to the in-memory
   log, fan out to subscribers, log, and return `event`.

   In-flight publishes are tracked so `quiesce!` / `drain!` can wait
   for the stream to settle before reporting at-rest.

   The quiesce fence is enforced atomically: `with-in-flight` fuses the
   quiesce-check and the in-flight increment into a single `swap!`,
   eliminating the TOCTOU window that allowed a publish to slip through
   after `quiesce!` fenced the workflow."
  [stream event]
  ;; Fast path: check quiesce before acquiring the in-flight slot so
  ;; already-quiesced workflows skip the swap! entirely.
  (or (rejection-if-quiesced stream event)
      (let [result (with-in-flight stream event
                     (fn []
                       (let [{:keys [sinks subscribers filters logger]} @stream]
                         (deliver-to-sinks! sinks event logger)
                         (record-event! stream event)
                         (deliver-to-subscribers! subscribers filters event logger)
                         (log-published! logger event)
                         event)))]
        ;; with-in-flight returns quiesced-sentinel when the workflow was
        ;; fenced during the atomic acquire (the TOCTOU window). Convert
        ;; to the canonical rejection shape so callers see a consistent
        ;; {:rejected? true ...} map regardless of which path triggered it.
        (if (= result quiesced-sentinel)
          (do (log-rejection! (:logger @stream) event)
              (rejection-result event :workflow-quiesced))
          result))))

(defn subscribe!
  ([stream subscriber-id callback]
   (subscribe! stream subscriber-id callback (constantly true)))
  ([stream subscriber-id callback filter-fn]
   (swap! stream
          (fn [s]
            (-> s
                (assoc-in [:subscribers subscriber-id] callback)
                (assoc-in [:filters subscriber-id] filter-fn))))
   subscriber-id))

(defn unsubscribe! [stream subscriber-id]
  (swap! stream
         (fn [s]
           (-> s
               (update :subscribers dissoc subscriber-id)
               (update :filters dissoc subscriber-id))))
  nil)

;------------------------------------------------------------------------------ Layer 1
;; BD-2a: workflow-scoped publisher quiesce + sink drain barrier.
;;
;; `quiesce!` fences future publishes for a workflow so the terminal event
;; for that workflow is genuinely the last. `drain!` waits for in-flight
;; publishes to complete and then asks each sink that exposes a drain hook
;; (under the sink's metadata) to flush. Together they replace the
;; pre-BD-2a race where headless exits could land before background
;; producers finished publishing or sinks finished writing.

(defn- wait-for-condition
  "Spin-wait until `pred` returns truthy or the deadline passes. Returns
   the final pred value (truthy = condition met before deadline)."
  [pred deadline-ms]
  (loop []
    (let [v (pred)]
      (cond
        v v
        (>= (System/currentTimeMillis) deadline-ms) nil
        :else (do (Thread/sleep 10) (recur))))))

(defn quiesce!
  "Fence publishers for `workflow-id` and wait for any in-flight publishes
   to settle. After return, `publish!` for that workflow returns
   `{:rejected? true :reason :workflow-quiesced ...}` instead of running
   sinks or subscribers.

   Without `:workflow-id`, no fence is added — the call only waits for
   currently in-flight publishes across all workflows to complete. Useful
   as a barrier before `drain!` when the caller does not care to fence a
   specific workflow.

   Options:
     :workflow-id  fence target. Optional.
     :timeout-ms   wait budget for in-flight publishes (default 5000).

   Returns:
     {:ok? true  :pending-publishers 0}
     {:ok? false :pending-publishers N :reason :timeout}"
  ([stream] (quiesce! stream {}))
  ([stream {:keys [workflow-id timeout-ms]
            :or   {timeout-ms 5000}}]
   (when workflow-id
     (swap! stream update :quiesced-workflows conj workflow-id))
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)
         settled? (wait-for-condition #(zero? (:in-flight @stream)) deadline)
         ;; Read in-flight once at return time and report it honestly in
         ;; both branches. In no-workflow-id mode there is no fence, so a
         ;; concurrent publisher can land between the wait observing zero
         ;; and us reading. With a fence in place that workflow's future
         ;; publishes are rejected (no in-flight increment), but other
         ;; workflows can still increment. The observational contract is:
         ;; "ok=true means in-flight reached zero at some point during
         ;; the wait; pending is the value at return."
         pending (:in-flight @stream)]
     (if settled?
       {:ok? true :pending-publishers pending}
       {:ok? false :reason :timeout :pending-publishers pending}))))

(defn drain!
  "Wait until every event accepted by `publish!` before this call has
   reached all configured sinks, including any sink-specific flush hook.

   Sinks may declare a drain hook by carrying it under metadata:

     (with-meta sink-fn {:drain (fn [opts] {:ok? true})})

   Sinks without a drain hook are assumed already-drained on `publish!`
   return — the file/stdout/stderr sinks are synchronous, so this is the
   common case. The fleet sink with its internal batching is the
   motivating exception.

   `drain!` first waits for in-flight publishes to settle (so the
   sink-list snapshot it operates on covers the full pre-call set), then
   invokes each sink's drain hook with a shared timeout.

   Options:
     :timeout-ms  total wait budget across in-flight settle + sink drain
                  (default 5000).

   Returns one of:
     {:ok? true  :drained-count N}
     {:ok? false :reason :timeout       :pending-count N}
     {:ok? false :reason :sink-error    :failed-sinks [{...}]}

   The result is structured so the caller (e.g. `run-workflow!`) can map
   it to a non-zero exit unless `MINIFORGE_BEST_EFFORT_SHUTDOWN` is set,
   per the BD-2a contract."
  ([stream] (drain! stream {}))
  ([stream {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)
         settled? (wait-for-condition #(zero? (:in-flight @stream)) deadline)]
     (if-not settled?
       {:ok? false :reason :timeout :pending-count (:in-flight @stream)}
       (let [{:keys [sinks]} @stream
             ;; Process sinks in order, honoring the total budget. If the
             ;; deadline is already past, mark the remaining sinks as
             ;; timed out without calling them — this prevents a slow
             ;; first sink from granting later sinks "extra" wall time
             ;; just because of a per-sink minimum, which would violate
             ;; the docstring's total-budget contract.
             results (reduce
                      (fn [acc sink]
                        (let [remaining (- deadline (System/currentTimeMillis))]
                          (conj acc
                                (cond
                                  (<= remaining 0)
                                  {:sink sink :result {:ok? false :reason :timeout}}

                                  (some? (:drain (meta sink)))
                                  (try
                                    {:sink sink
                                     :result ((:drain (meta sink)) {:timeout-ms remaining})}
                                    (catch Exception e
                                      {:sink sink :result {:ok? false
                                                           :reason :sink-error
                                                           :error (ex-message e)}}))

                                  ;; No drain hook: sink is assumed synchronous.
                                  :else {:sink sink :result {:ok? true}}))))
                      []
                      sinks)
             failed (filterv (comp not :ok? :result) results)]
         (if (seq failed)
           {:ok? false
            :reason :sink-error
            :failed-sinks (mapv :result failed)}
           {:ok? true :drained-count (count results)}))))))

;------------------------------------------------------------------------------ Layer 2
;; Query API

(defn get-events [stream & [opts]]
  (let [{:keys [workflow-id event-type offset limit]} opts
        events (:events @stream)]
    (cond->> events
      workflow-id (filter #(= workflow-id (:workflow/id %)))
      event-type (filter #(= event-type (:event/type %)))
      offset (drop offset)
      limit (take limit)
      true vec)))

(defn get-latest-status [stream workflow-id & [agent-id]]
  (->> (:events @stream)
       (filter #(= :agent/status (:event/type %)))
       (filter #(= workflow-id (:workflow/id %)))
       (filter #(or (nil? agent-id) (= agent-id (:agent/id %))))
       last))

;------------------------------------------------------------------------------ Layer 3
;; Event constructors (N3 compliant)

(defn workflow-started
  "Build a :workflow/started envelope. Multi-arity to preserve the legacy
   2/3-arg call shape used across the workspace.

   Options (4-arg / opts-map form):

   - `:routing/trigger-event-id` — N5-delta-4 §4.3 envelope addition. When
     present, the automation-edge-correlator maps the handler workflow
     back to its originating routing trigger via this id (explicit-id
     path, §3.5 case 1). Absent on operator-initiated workflows; the
     correlator's heuristic-fallback (§3.5 case 2) covers absence."
  ([stream workflow-id]
   (workflow-started stream workflow-id nil nil))
  ([stream workflow-id spec]
   (workflow-started stream workflow-id spec nil))
  ([stream workflow-id spec opts]
   (let [trigger-event-id (:routing/trigger-event-id opts)]
     (cond-> (create-envelope stream :workflow/started workflow-id "Workflow started")
       spec             (assoc :workflow/spec spec)
       trigger-event-id (assoc :routing/trigger-event-id trigger-event-id)))))

(defn phase-started [stream workflow-id phase & [context]]
  (-> (create-envelope stream :workflow/phase-started workflow-id
                       (str (name phase) " phase started"))
      (assoc :workflow/phase phase)
      (cond-> context (assoc :phase/context context))))

(defn phase-completed [stream workflow-id phase & [result]]
  (let [outcome (get result :outcome :success)
        request (phase-transition-request result)
        redirect-to (or (redirect-target result)
                        (:redirect-to result))]
    (-> (create-envelope stream :workflow/phase-completed workflow-id
                         (str (name phase) " phase " (name outcome)))
        (assoc :workflow/phase phase
               :phase/outcome outcome)
        (cond->
          (:duration-ms result) (assoc :phase/duration-ms (:duration-ms result))
          (:review-decision result) (assoc :phase/review-decision (:review-decision result))
          (:phase/blocked-reason result) (assoc :phase/blocked-reason (:phase/blocked-reason result))
          (:artifacts result) (assoc :phase/artifacts (:artifacts result))
          (:error result) (assoc :phase/error (:error result))
          request (assoc :phase/transition-request request)
          redirect-to (assoc :phase/redirect-to redirect-to)
          (:tokens result) (assoc :phase/tokens (:tokens result))
          (:cost-usd result) (assoc :phase/cost-usd (:cost-usd result))
          (:meta result) (assoc :phase/meta (:meta result))
          (:phase/termination-reason result)
          (assoc :phase/termination-reason (:phase/termination-reason result))))))

(defn workspace-persisted
  "Build a :workspace/persisted event recording that a phase's worktree was
   archived to a checkpoint. Surfaces the bundle path so dashboards and
   evidence bundles can offer 'inspect/resume from this checkpoint'
   affordances without grepping logs.

   Arguments:
   - stream:      event stream
   - workflow-id: id of the (sub-)workflow whose work was persisted
   - data:        {:phase keyword     phase at which persistence ran
                   :env-id str        executor environment id (task-X)
                   :branch str        branch the work landed on
                   :commit-sha str    sha of the persisted commit
                   :bundle-path str   absolute path to the .bundle file
                   :persist-tier kw   :worktree (local) or :remote (governed)}"
  [stream workflow-id data]
  (-> (create-envelope stream :workspace/persisted workflow-id
                       (str "Workspace persisted: "
                            (or (:bundle-path data) (:commit-sha data))))
      (assoc :workspace/phase       (:phase data)
             :workspace/env-id      (:env-id data)
             :workspace/branch      (:branch data)
             :workspace/commit-sha  (:commit-sha data)
             :workspace/bundle-path (:bundle-path data)
             :workspace/tier        (get data :persist-tier :worktree))))

(defn agent-chunk [stream workflow-id agent-id delta & [done?]]
  (-> (create-envelope stream :agent/chunk workflow-id
                       (if done? "Agent stream completed" "Agent streaming"))
      (assoc :agent/id agent-id
             :chunk/delta delta)
      (cond-> done? (assoc :chunk/done? true))))

(defn agent-status [stream workflow-id agent-id status-type message]
  (-> (create-envelope stream :agent/status workflow-id message)
      (assoc :agent/id agent-id
             :status/type status-type)))

(defn agent-tool-call
  "Publish a :agent/tool-call event carrying the tool name(s) the agent
   just invoked, per N3 §3.x. Replaces the generic :agent/status
   :tool-calling emission for consumers that want structured tool data.

   Fields:
     :tool/name            — single tool name when one tool fired in the block
     :tool/names           — vector of names when a single assistant block
                             included multiple tool_use items
     :tool/call-id         — provider-supplied id (Claude tool_use.id /
                             codex item id) when available
     :tool/args-preview    — truncated/digested args for diagnosis (bounded
                             to keep events small)"
  [stream workflow-id agent-id
   {:keys [tool-name tool-names tool-call-id tool-args-preview]}]
  (cond-> (create-envelope stream :agent/tool-call workflow-id
                           (str "Agent called tool"
                                (when tool-name (str ": " tool-name))))
    true                (assoc :agent/id agent-id)
    tool-name           (assoc :tool/name tool-name)
    (seq tool-names)    (assoc :tool/names (vec tool-names))
    tool-call-id        (assoc :tool/call-id tool-call-id)
    tool-args-preview   (assoc :tool/args-preview tool-args-preview)))

(defn agent-tool-call-started
  "Build an :agent/tool-call-started event marking the moment an agent
   begins executing a named tool call or tool-use block.

   Arguments:
   - stream:      event stream
   - workflow-id: owning workflow UUID
   - agent-id:    keyword identifying the agent (e.g. :implementer)
   - opts:        {:tool/name     string  — tool being called when known
                   :tool/names    vector  — tool names for provider blocks
                   :tool/args-digest map   — bounded digest of tool args
                   :tool/call-id  string  — provider-supplied call id}"
  [stream workflow-id agent-id
   {:keys [:tool/name :tool/names :tool/args-digest :tool/call-id]}]
  (cond-> (create-envelope stream :agent/tool-call-started workflow-id
                           (messages/t :tool-call/started
                                       {:tool-name-suffix
                                        (if name (str ": " name) "")}))
    true            (assoc :agent/id agent-id)
    name            (assoc :tool/name name)
    (seq names)     (assoc :tool/names (vec names))
    args-digest     (assoc :tool/args-digest args-digest)
    call-id         (assoc :tool/call-id call-id)))

(defn tool-call-completed
  "Build a :tool/call-completed event closing the latency span opened by
   :agent/tool-call-started.

   Arguments:
   - stream:      event stream
   - workflow-id: owning workflow UUID
   - opts:        {:tool/call-id       string   — matches the started event
                   :tool/result-digest map      — bounded digest of result
                   :tool/duration-ms   int      — elapsed ms
                   :tool/success?      boolean  — outcome
                   :tool/error         map      — populated on failure}"
  [stream workflow-id
   {:keys [:tool/call-id :tool/result-digest :tool/duration-ms
           :tool/success? :tool/error] :as _opts}]
  (cond-> (create-envelope stream :tool/call-completed workflow-id
                           (messages/t (cond
                                         (true? success?) :tool-call/succeeded
                                         (false? success?) :tool-call/failed
                                         :else :tool-call/completed)))
    call-id         (assoc :tool/call-id call-id)
    result-digest   (assoc :tool/result-digest result-digest)
    duration-ms     (assoc :tool/duration-ms duration-ms)
    (some? success?) (assoc :tool/success? success?)
    error           (assoc :tool/error error)))

(defn phase-heartbeat
  "Build a :workflow/phase-heartbeat event for long-running phase liveness
   signalling.

   Arguments:
   - stream:      event stream
   - workflow-id: owning workflow UUID
   - phase:       keyword identifying the current phase
   - opts:        {:phase/active-since              inst
                   :phase/events-emitted            int
                   :phase/last-event-at             inst
                   :phase/gap-since-last-event-ms   int}"
  [stream workflow-id phase
   {:keys [:phase/active-since :phase/events-emitted
           :phase/last-event-at :phase/gap-since-last-event-ms]}]
  (cond-> (create-envelope stream :workflow/phase-heartbeat workflow-id
                           (messages/t :phase/heartbeat
                                       {:phase (name phase)}))
    true                       (assoc :workflow/phase phase)
    active-since               (assoc :phase/active-since active-since)
    (some? events-emitted)     (assoc :phase/events-emitted events-emitted)
    last-event-at              (assoc :phase/last-event-at last-event-at)
    (some? gap-since-last-event-ms)
    (assoc :phase/gap-since-last-event-ms gap-since-last-event-ms)))

(defn workflow-completed [stream workflow-id status & [duration-ms opts]]
  (-> (create-envelope stream :workflow/completed workflow-id
                       (str "Workflow " (name status)))
      (assoc :workflow/status status)
      (cond-> duration-ms (assoc :workflow/duration-ms duration-ms)
              (:tokens opts) (assoc :workflow/tokens (:tokens opts))
              (:cost-usd opts) (assoc :workflow/cost-usd (:cost-usd opts))
              (:pr-info opts) (assoc :workflow/pr-info (:pr-info opts))
              (seq (:pr-infos opts)) (assoc :workflow/pr-infos (:pr-infos opts))
              (:workflow/evidence-bundle-id opts)
              (assoc :workflow/evidence-bundle-id (:workflow/evidence-bundle-id opts)))))

(defn workflow-failed [stream workflow-id error & [{:keys [failure/class]}]]
  (let [;; Handle anomaly maps, Throwables, and plain error maps
        anomaly-map (cond
                      (response/anomaly-map? error) error
                      (instance? Throwable error) (response/from-exception error)
                      (and (map? error) (:anomaly error)) (:anomaly error)
                      :else nil)
        error-map (cond
                    (instance? Throwable error)
                    {:message (.getMessage ^Throwable error)
                     :type (str (type error))}
                    (response/anomaly-map? error)
                    (response/anomaly->event-data error)
                    :else error)
        event-data (response/anomaly->event-data
                    (or anomaly-map
                        (response/make-anomaly :anomalies/fault
                                               (get error-map :message "unknown error"))))]
    (-> (create-envelope stream :workflow/failed workflow-id
                         (str "Workflow failed: " (:message error-map "unknown error")))
        (assoc :workflow/failure-reason (:message error-map (:message event-data))
               :workflow/error-details error-map
               :workflow/anomaly-code (:anomaly-code event-data)
               :workflow/retryable? (:retryable? event-data false))
        (cond-> class (assoc :failure/class class)))))

(defn llm-request [stream workflow-id agent-id model & [prompt-tokens]]
  (-> (create-envelope stream :llm/request workflow-id
                       (str "Calling " model (when prompt-tokens (str " (" prompt-tokens " tokens)"))))
      (assoc :agent/id agent-id
             :llm/model model
             :llm/request-id (random-uuid))
      (cond-> prompt-tokens (assoc :llm/prompt-tokens prompt-tokens))))

(defn llm-response [stream workflow-id agent-id model request-id & [metrics]]
  (-> (create-envelope stream :llm/response workflow-id
                       (str "Response from " model
                            (when (:completion-tokens metrics)
                              (str " (" (:completion-tokens metrics) " tokens)"))))
      (assoc :agent/id agent-id
             :llm/model model
             :llm/request-id request-id)
      (cond->
        (:completion-tokens metrics) (assoc :llm/completion-tokens (:completion-tokens metrics))
        (:total-tokens metrics) (assoc :llm/total-tokens (:total-tokens metrics))
        (:duration-ms metrics) (assoc :llm/duration-ms (:duration-ms metrics))
        (:cost-usd metrics) (assoc :llm/cost-usd (:cost-usd metrics)))))

;------------------------------------------------------------------------------ Layer 4
;; Agent lifecycle events

(defn agent-started [stream workflow-id agent-id & [context]]
  (-> (create-envelope stream :agent/started workflow-id
                       (str "Agent " (name agent-id) " started"))
      (assoc :agent/id agent-id)
      (cond-> context (assoc :agent/context context))))

(defn agent-completed [stream workflow-id agent-id & [result]]
  (-> (create-envelope stream :agent/completed workflow-id
                       (str "Agent " (name agent-id) " completed"))
      (assoc :agent/id agent-id)
      (cond-> result (assoc :agent/result result))))

(defn agent-failed [stream workflow-id agent-id & [error {:keys [failure/class]}]]
  (-> (create-envelope stream :agent/failed workflow-id
                       (str "Agent " (name agent-id) " failed"))
      (assoc :agent/id agent-id)
      (cond-> error (assoc :agent/error error)
              class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 4
;; Gate lifecycle events

(defn gate-started [stream workflow-id gate-id & [artifact-summary]]
  (-> (create-envelope stream :gate/started workflow-id
                       (str "Gate " (name gate-id) " started"))
      (assoc :gate/id gate-id)
      (cond-> artifact-summary (assoc :gate/artifact-summary artifact-summary))))

(defn gate-passed [stream workflow-id gate-id & [duration-ms]]
  (-> (create-envelope stream :gate/passed workflow-id
                       (str "Gate " (name gate-id) " passed"))
      (assoc :gate/id gate-id)
      (cond-> duration-ms (assoc :gate/duration-ms duration-ms))))

(defn gate-failed [stream workflow-id gate-id & [violations {:keys [failure/class]}]]
  (-> (create-envelope stream :gate/failed workflow-id
                       (str "Gate " (name gate-id) " failed"))
      (assoc :gate/id gate-id)
      (cond-> violations (assoc :gate/violations violations)
              class (assoc :failure/class class))))

(defn gate-rule-applied
  "Per-rule policy evidence event: records that policy `rule-id` was evaluated
   in `phase` with `status` (one of :passed, :failed, :skipped-by-phase,
   :not-applicable). Emitted for considered AND skipped rules so the full
   applied set for a run is reconstructable (closes the rule-visibility gap).

   `extra` may carry :severity, :enforcement (the rule's enforcement action),
   and :violation (a NON-SENSITIVE summary for a :failed rule — callers must
   not pass raw match content; see gate/policy-pack's violation-summary)."
  [stream workflow-id phase rule-id status & [extra]]
  (-> (create-envelope stream :gate/rule-applied workflow-id
                       (str "Rule " rule-id " " (name status) " in " (name phase)))
      (assoc :gate/phase   phase
             :rule/id       rule-id
             :rule/status   status)
      (cond-> (:severity extra)    (assoc :rule/severity (:severity extra))
              (:enforcement extra) (assoc :rule/enforcement (:enforcement extra))
              (:violation extra)   (assoc :rule/violation (:violation extra)))))

;------------------------------------------------------------------------------ Layer 4
;; Tool lifecycle events

(defn tool-invoked [stream workflow-id agent-id tool-id & [params-summary]]
  (-> (create-envelope stream :tool/invoked workflow-id
                       (str "Tool " (name tool-id) " invoked by " (name agent-id)))
      (assoc :agent/id agent-id
             :tool/id tool-id)
      (cond-> params-summary (assoc :tool/params-summary params-summary))))

(defn tool-completed [stream workflow-id agent-id tool-id & [result-summary]]
  (-> (create-envelope stream :tool/completed workflow-id
                       (str "Tool " (name tool-id) " completed"))
      (assoc :agent/id agent-id
             :tool/id tool-id)
      (cond-> result-summary (assoc :tool/result-summary result-summary))))

;------------------------------------------------------------------------------ Layer 4
;; Milestone event

(defn milestone-reached [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :workflow/milestone-reached workflow-id
                       (or description (str "Milestone " (name milestone-id) " reached")))
      (assoc :milestone/id milestone-id)))

(defn milestone-started [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :phase/milestone-started workflow-id
                       (or description (str "Milestone " (name milestone-id) " started")))
      (assoc :milestone/id milestone-id)))

(defn milestone-completed [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :phase/milestone-completed workflow-id
                       (or description (str "Milestone " (name milestone-id) " completed")))
      (assoc :milestone/id milestone-id)))

(defn milestone-failed [stream workflow-id milestone-id & [reason]]
  (-> (create-envelope stream :phase/milestone-failed workflow-id
                       (or reason (str "Milestone " (name milestone-id) " failed")))
      (assoc :milestone/id milestone-id)))

;------------------------------------------------------------------------------ Layer 4
;; Task lifecycle (DAG) events

(defn task-state-changed [stream workflow-id dag-id task-id from-state to-state & [context]]
  (-> (create-envelope stream :task/state-changed workflow-id
                       (str "Task " task-id " " (name from-state) " -> " (name to-state)))
      (assoc :dag/id dag-id
             :task/id task-id
             :task/from-state from-state
             :task/to-state to-state)
      (cond-> context (assoc :task/context context))))

(defn task-frontier-entered [stream workflow-id dag-id task-id & [frontier-size]]
  (-> (create-envelope stream :task/frontier-entered workflow-id
                       (str "Task " task-id " entered frontier"))
      (assoc :dag/id dag-id
             :task/id task-id)
      (cond-> frontier-size (assoc :task/frontier-size frontier-size))))

(defn task-skip-propagated [stream workflow-id dag-id task-id & [cause-task]]
  (-> (create-envelope stream :task/skip-propagated workflow-id
                       (str "Task " task-id " skip propagated"))
      (assoc :dag/id dag-id
             :task/id task-id)
      (cond-> cause-task (assoc :task/cause-task cause-task))))

;------------------------------------------------------------------------------ Layer 4
;; Inter-agent messaging events

(defn inter-agent-message-sent [stream workflow-id from-agent to-agent & [message-type]]
  (-> (create-envelope stream :agent/message-sent workflow-id
                       (str (name from-agent) " -> " (name to-agent)
                            (when message-type (str " (" (name message-type) ")"))))
      (assoc :from-agent/id from-agent
             :to-agent/id to-agent)
      (cond-> message-type (assoc :message/type message-type))))

(defn inter-agent-message-received [stream workflow-id from-agent to-agent & [message-type]]
  (-> (create-envelope stream :agent/message-received workflow-id
                       (str (name to-agent) " <- " (name from-agent)
                            (when message-type (str " (" (name message-type) ")"))))
      (assoc :from-agent/id from-agent
             :to-agent/id to-agent)
      (cond-> message-type (assoc :message/type message-type))))

;------------------------------------------------------------------------------ Layer 4
;; Listener lifecycle events (N8)

(defn listener-attached [stream workflow-id listener-id & [listener-type capability]]
  (-> (create-envelope stream :listener/attached workflow-id
                       (str "Listener " listener-id " attached"))
      (assoc :listener/id listener-id)
      (cond->
        listener-type (assoc :listener/type listener-type)
        capability (assoc :listener/capability capability))))

(defn listener-detached [stream workflow-id listener-id & [reason]]
  (-> (create-envelope stream :listener/detached workflow-id
                       (str "Listener " listener-id " detached"))
      (assoc :listener/id listener-id)
      (cond-> reason (assoc :listener/reason reason))))

(defn annotation-created [stream workflow-id listener-id annotation-type & [content]]
  (-> (create-envelope stream :annotation/created workflow-id
                       (str "Annotation from " listener-id ": " (name annotation-type)))
      (assoc :listener/id listener-id
             :annotation/type annotation-type)
      (cond-> content (assoc :annotation/content content))))

;------------------------------------------------------------------------------ Layer 4
;; Chain lifecycle events
;; Chains are not workflow-scoped, so workflow-id is nil.
;; Message is derived from the event-type keyword.

(defn chain-envelope
  "Create an envelope for chain events. Chains are not workflow-scoped,
   so workflow-id is nil. Message is derived from the event-type keyword."
  [stream event-type]
  (create-envelope stream event-type nil (name event-type)))

(defn chain-started [stream chain-id step-count]
  (-> (chain-envelope stream :chain/started)
      (assoc :chain/id chain-id
             :chain/step-count step-count)))

(defn chain-step-started [stream chain-id step-id step-index workflow-id]
  (-> (chain-envelope stream :chain/step-started)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :step/workflow-id workflow-id)))

(defn chain-step-completed [stream chain-id step-id step-index]
  (-> (chain-envelope stream :chain/step-completed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index)))

(defn chain-step-failed [stream chain-id step-id step-index error & [{:keys [failure/class]}]]
  (-> (chain-envelope stream :chain/step-failed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

(defn chain-completed [stream chain-id duration-ms step-count]
  (-> (chain-envelope stream :chain/completed)
      (assoc :chain/id chain-id
             :chain/duration-ms duration-ms
             :chain/step-count step-count)))

(defn chain-failed [stream chain-id step-id error & [{:keys [failure/class]}]]
  (-> (chain-envelope stream :chain/failed)
      (assoc :chain/id chain-id
             :chain/failed-step step-id
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 4
;; Control action events (N8)

(defn control-action-requested [stream workflow-id action-id action-type & [requester]]
  (-> (create-envelope stream :control-action/requested workflow-id
                       (str "Control action " (name action-type) " requested"))
      (assoc :action/id action-id
             :action/type action-type)
      (cond-> requester (assoc :action/requester requester))))

(defn control-action-executed [stream workflow-id action-id & [result]]
  (-> (create-envelope stream :control-action/executed workflow-id
                       (str "Control action " action-id " executed"))
      (assoc :action/id action-id)
      (cond-> result (assoc :action/result result))))

;------------------------------------------------------------------------------ Layer 4
;; OCI container events (N8)

(defn container-started [stream workflow-id container-id & [opts]]
  (-> (create-envelope stream :oci/container-started workflow-id
                       (str "Container " container-id " started"))
      (assoc :oci/container-id container-id)
      (cond->
        (:image-digest opts) (assoc :oci/image-digest (:image-digest opts))
        (:trust-level opts)  (assoc :oci/trust-level (:trust-level opts)))))

(defn container-completed [stream workflow-id container-id exit-code & [duration-ms]]
  (-> (create-envelope stream :oci/container-completed workflow-id
                       (str "Container " container-id " completed (exit " exit-code ")"))
      (assoc :oci/container-id container-id
             :oci/exit-code exit-code)
      (cond-> duration-ms (assoc :oci/duration-ms duration-ms))))

;------------------------------------------------------------------------------ Layer 4
;; Tool supervision events (N6/N8)

(defn tool-use-evaluated
  "Emit when a tool-use request is evaluated by the supervisor.

   Captures both regex and meta-eval decisions for evidence trail."
  [stream workflow-id tool-name decision & [opts]]
  (-> (create-envelope stream :supervision/tool-use-evaluated workflow-id
                       (str "Tool " tool-name " evaluated: " (name (keyword decision))))
      (assoc :tool/name tool-name
             :supervision/decision decision)
      (cond->
        (:reasoning opts)  (assoc :supervision/reasoning (:reasoning opts))
        (:meta-eval? opts) (assoc :supervision/meta-eval? true)
        (:confidence opts) (assoc :supervision/confidence (:confidence opts))
        (:phase opts)      (assoc :workflow/phase (:phase opts)))))

(defn meta-loop-halt-requested
  "Emit when a meta-agent signals the meta-loop must halt the workflow.

   The REFUSE act for meta-supervision: makes the halt first-class on the stream
   rather than only a value in the coordinator result. `reason-code` is a
   RefusalReason keyword; `opts` may carry :detail (the halting agent's free-text
   message) and :phase (current workflow phase)."
  [stream workflow-id halting-agent reason-code & [opts]]
  (-> (create-envelope stream :meta-loop/halt-requested workflow-id
                       (str "Meta-loop halt requested by " (name halting-agent)
                            ": " (name reason-code)))
      (assoc :halt/halting-agent halting-agent
             :halt/reason-code reason-code)
      (cond->
        (:detail opts) (assoc :halt/detail (:detail opts))
        (:phase opts)  (assoc :workflow/phase (:phase opts)))))

;------------------------------------------------------------------------------ Layer 5
;; Control plane events

(defn cp-agent-registered
  "Emit when an external agent registers with the control plane."
  [stream workflow-id agent-id vendor & [opts]]
  (-> (create-envelope stream :control-plane/agent-registered workflow-id
                       (str (name vendor) " agent " agent-id " registered"))
      (assoc :cp/agent-id agent-id
             :cp/vendor vendor)
      (cond->
        (:name opts) (assoc :cp/agent-name (:name opts))
        (:external-id opts) (assoc :cp/external-id (:external-id opts))
        (:capabilities opts) (assoc :cp/capabilities (vec (:capabilities opts)))
        (:metadata opts) (assoc :cp/metadata (:metadata opts))
        (:tags opts) (assoc :cp/tags (vec (:tags opts)))
        (:heartbeat-interval-ms opts)
        (assoc :cp/heartbeat-interval-ms (:heartbeat-interval-ms opts)))))

(defn cp-agent-heartbeat
  "Emit when the control plane receives a heartbeat from an agent."
  [stream workflow-id agent-id status & [opts]]
  (-> (create-envelope stream :control-plane/agent-heartbeat workflow-id
                       (str "Heartbeat from " agent-id ": " (name status)))
      (assoc :cp/agent-id agent-id
             :cp/status status)
      (cond->
        (:task opts) (assoc :cp/task (:task opts))
        (:metrics opts) (assoc :cp/metrics (:metrics opts)))))

(defn cp-agent-state-changed
  "Emit when an agent's normalized state changes."
  [stream workflow-id agent-id from-status to-status]
  (-> (create-envelope stream :control-plane/agent-state-changed workflow-id
                       (str "Agent " agent-id ": " (name from-status) " → " (name to-status)))
      (assoc :cp/agent-id agent-id
             :cp/from-status from-status
             :cp/to-status to-status)))

(defn cp-decision-created
  "Emit when an agent submits a decision request."
  [stream workflow-id agent-id decision-id summary & [priority-or-opts]]
  (let [opts (if (map? priority-or-opts)
               priority-or-opts
               {:priority priority-or-opts})]
  (-> (create-envelope stream :control-plane/decision-created workflow-id
                       (str "Decision needed from " agent-id ": " summary))
      (assoc :cp/agent-id agent-id
             :cp/decision-id decision-id
             :cp/summary summary)
      (cond->
        (:priority opts) (assoc :cp/priority (:priority opts))
        (:type opts) (assoc :cp/type (:type opts))
        (:context opts) (assoc :cp/context (:context opts))
        (:options opts) (assoc :cp/options (vec (:options opts)))
        (:deadline opts) (assoc :cp/deadline (:deadline opts))))))

(defn cp-decision-resolved
  "Emit when a human resolves a decision."
  [stream workflow-id decision-id resolution & [comment]]
  (-> (create-envelope stream :control-plane/decision-resolved workflow-id
                       (str "Decision " decision-id " resolved: " resolution))
      (assoc :cp/decision-id decision-id
             :cp/resolution resolution)
      (cond-> comment (assoc :cp/comment comment))))

(defn intervention-requested
  "Emit when a bounded supervisory intervention is created."
  [stream workflow-id intervention]
  (let [message (messages/t :supervisory/intervention-requested
                            {:type (name (:intervention/type intervention))})]
    (-> (create-envelope stream :supervisory/intervention-requested workflow-id
                         message)
        (merge intervention))))

(defn intervention-state-changed
  "Emit when an InterventionRequest changes lifecycle state."
  [stream workflow-id intervention-id to-state & [opts]]
  (let [message (messages/t :supervisory/intervention-state-changed
                            {:intervention-id intervention-id
                             :state (name to-state)})]
    (-> (create-envelope stream :supervisory/intervention-state-changed workflow-id
                         message)
        (assoc :intervention/id intervention-id
               :intervention/state to-state)
        (cond->
          (:intervention/from-state opts)
          (assoc :intervention/from-state (:intervention/from-state opts))

          (:intervention/type opts)
          (assoc :intervention/type (:intervention/type opts))

          (:intervention/target-type opts)
          (assoc :intervention/target-type (:intervention/target-type opts))

          (contains? opts :intervention/target-id)
          (assoc :intervention/target-id (:intervention/target-id opts))

          (:intervention/requested-by opts)
          (assoc :intervention/requested-by (:intervention/requested-by opts))

          (:intervention/request-source opts)
          (assoc :intervention/request-source (:intervention/request-source opts))

          (:intervention/justification opts)
          (assoc :intervention/justification (:intervention/justification opts))

          (:intervention/details opts)
          (assoc :intervention/details (:intervention/details opts))

          (:intervention/reason opts)
          (assoc :intervention/reason (:intervention/reason opts))

          (:intervention/outcome opts)
          (assoc :intervention/outcome (:intervention/outcome opts))

          (:intervention/requested-at opts)
          (assoc :intervention/requested-at (:intervention/requested-at opts))

          (:intervention/updated-at opts)
          (assoc :intervention/updated-at (:intervention/updated-at opts))

          (contains? opts :intervention/approval-required?)
          (assoc :intervention/approval-required?
                 (:intervention/approval-required? opts))))))

;------------------------------------------------------------------------------ Layer 5.5
;; Zettelkasten lifecycle events (added for miniforge-fleet's Phase
;; E.3 outbox path — Fleet's ingest consumes these to grow the
;; cross-instance event log).

(defn zettel-promoted
  "Emit when a zettel revision transitions to `:trusted` state and
   becomes eligible to ride the Fleet event log.

   Schema in `schema/ZettelPromoted`. Required positional args
   carry the Decision-6 revision-keyed identity + the content the
   Fleet ingest path validates against the privacy gates.

   Fleet-share intent (`:fleet/shareable`, `:fleet/share-scope`,
   `:privacy/classification`) is read FROM THE ZETTEL itself —
   producers attach those fields to the zettel (via the
   `knowledge/create-zettel` kwargs) and they ride through to the
   event automatically when present. Absence on the zettel means
   the promotion is local-only.

   Args:
     stream            — event-stream atom
     workflow-id       — owning workflow run UUID
     zettel            — the trusted zettel map (must carry
                          `:zettel/id`, `:zettel/revision-id`,
                          `:zettel/digest`, `:zettel/uid`,
                          `:zettel/title`, `:zettel/content`,
                          `:zettel/type`; optional Fleet-share
                          intent fields ride through if present)
     oss-version       — OSS version pin (Decision 13).

   Optional opts (envelope identity, all may be omitted):
     :org/id, :workspace/id, :repo/id, :auth/context"
  [stream workflow-id zettel oss-version & [opts]]
  (let [opts (or opts {})
        base (create-envelope stream :zettel/promoted workflow-id
                              (messages/t :zettel/promoted
                                          {:uid (:zettel/uid zettel)}))]
    (cond-> (assoc base
                   :zettel/id          (:zettel/id zettel)
                   :zettel/revision-id (:zettel/revision-id zettel)
                   :zettel/digest      (:zettel/digest zettel)
                   :zettel/uid         (:zettel/uid zettel)
                   :zettel/title       (:zettel/title zettel)
                   :zettel/content     (:zettel/content zettel)
                   :zettel/type        (:zettel/type zettel)
                   :fleet/oss-version  oss-version)
      ;; Fleet-share intent (Decision 8) rides through from the zettel
      ;; when present. Absence means local-only promotion.
      (some? (:fleet/shareable zettel))
      (assoc :fleet/shareable (:fleet/shareable zettel))

      (:fleet/share-scope zettel)
      (assoc :fleet/share-scope (:fleet/share-scope zettel))

      (:privacy/classification zettel)
      (assoc :privacy/classification (:privacy/classification zettel))

      ;; Identity propagation (Decision 14) rides through from the
      ;; opts map. Once the envelope-side identity-propagation PR
      ;; (the matching prerequisite #10 PR) lands, callers will pass
      ;; these via `create-envelope`'s opts arity instead.
      (:org/id opts)       (assoc :org/id (:org/id opts))
      (:workspace/id opts) (assoc :workspace/id (:workspace/id opts))
      (:repo/id opts)      (assoc :repo/id (:repo/id opts))
      (:auth/context opts) (assoc :auth/context (:auth/context opts)))))

;------------------------------------------------------------------------------ Layer 6
;; PR scoring events (N5-delta-2 §4.1)

(defn pr-created
  "Emit when a workflow-owned PR is created.

   This is the canonical fine-grained event that lets `supervisory-state`
   attach a PR back to the owning workflow run instead of forcing
   consumers to infer that relationship from summary payloads later."
  [stream workflow-id {:pr/keys [repo number url branch title author merge-order] :as _pr}]
  (-> (create-envelope stream :pr/created workflow-id
                       (str "PR " repo "#" number " created"))
      (assoc :pr/repo repo
             :pr/number number
             :pr/url url
             :pr/branch branch)
      (cond->
        title       (assoc :pr/title title)
        author      (assoc :pr/author author)
        merge-order (assoc :pr/merge-order merge-order))))

(defn pr-scored
  "Emit when the pr-scoring component has computed readiness, risk, and
   policy scores for a PR, per N5-delta-2 §4.1. Produces the only event
   carrying the four `:pr/readiness`, `:pr/risk`, `:pr/policy`, and
   `:pr/recommendation` fields for downstream consumption by
   `supervisory-state`.

   Args:
     stream      — event-stream atom
     repo        — \"owner/repo\" string
     number      — PR number (long)
     scores      — map with keys :readiness, :risk, :policy,
                   :recommendation (any subset MAY be present; consumers
                   render absent fields as \"not yet scored\", §5.4)
     opts        — optional map; :workflow/id scopes the event to a
                   run, otherwise nil (PR scoring is not inherently
                   workflow-scoped)"
  [stream repo number {:keys [readiness risk policy recommendation]}
   & [{:workflow/keys [id] :as _opts}]]
  (-> (create-envelope stream :pr/scored id
                       (str "PR " repo "#" number " scored"))
      (assoc :pr/repo repo
             :pr/number number)
      (cond->
        readiness      (assoc :pr/readiness readiness)
        risk           (assoc :pr/risk risk)
        policy         (assoc :pr/policy policy)
        recommendation (assoc :pr/recommendation recommendation))))

;------------------------------------------------------------------------------ Layer 6
;; Reliability metric events (N3 §3.17, N1 §5.5)

(defn sli-computed
  "Emit when an SLI value is computed over a rolling window."
  [stream sli-name value window & [opts]]
  (-> (create-envelope stream :reliability/sli-computed nil
                       (messages/t :reliability/sli-computed
                                   {:sli-name (name sli-name)
                                    :value    value
                                    :window   (name window)}))
      (assoc :sli/name sli-name
             :sli/value value
             :sli/window window)
      (cond->
        (:tier opts)       (assoc :sli/tier (:tier opts))
        (:dimensions opts) (assoc :sli/dimensions (:dimensions opts)))))

(defn slo-breach
  "Emit when an SLO target is missed for :standard or :critical tiers."
  [stream sli-name target actual tier window]
  (-> (create-envelope stream :reliability/slo-breach nil
                       (messages/t :reliability/slo-breach
                                   {:sli-name (name sli-name)
                                    :target   target
                                    :actual   actual
                                    :tier     (name tier)}))
      (assoc :slo/sli-name sli-name
             :slo/target target
             :slo/actual actual
             :slo/tier tier
             :slo/window window)))

(defn error-budget-update
  "Emit when error budget state is recomputed."
  [stream tier sli remaining burn-rate window]
  (-> (create-envelope stream :reliability/error-budget-update nil
                       (messages/t :reliability/error-budget-update
                                   {:tier      (name tier)
                                    :sli       (name sli)
                                    :remaining remaining
                                    :burn-rate burn-rate}))
      (assoc :budget/tier tier
             :budget/sli sli
             :budget/remaining remaining
             :budget/burn-rate burn-rate
             :budget/window window)))

(defn degradation-mode-changed
  "Emit when the system transitions between degradation modes (N1 §5.5.5)."
  [stream from-mode to-mode trigger]
  (-> (create-envelope stream :reliability/degradation-mode-changed nil
                       (messages/t :reliability/degradation-mode-changed
                                   {:from    (name from-mode)
                                    :to      (name to-mode)
                                    :trigger trigger}))
      (assoc :degradation/from from-mode
             :degradation/to to-mode
             :degradation/trigger trigger)))

(defn safe-mode-entered
  "Emit when safe-mode is activated (N8 §3.4.4)."
  [stream trigger & [details]]
  (-> (create-envelope stream :safe-mode/entered nil
                       (str "Safe-mode entered: " (name trigger)))
      (assoc :safe-mode/trigger trigger)
      (cond-> details (assoc :safe-mode/trigger-details details))))

(defn safe-mode-exited
  "Emit when safe-mode is deactivated (N8 §3.4.4)."
  [stream exited-by justification duration-ms workflows-queued]
  (-> (create-envelope stream :safe-mode/exited nil
                       (format "Safe-mode exited after %dms: %s" duration-ms justification))
      (assoc :safe-mode/exited-by exited-by
             :safe-mode/justification justification
             :safe-mode/duration-ms duration-ms
             :safe-mode/workflows-queued workflows-queued)))

(defn- dependency-id-string
  [dependency-id]
  (if (keyword? dependency-id)
    (name dependency-id)
    (str dependency-id)))

(defn- dependency-event
  [stream event-type dependency previous-status message-key]
  (let [dependency-id (:dependency/id dependency)
        status (:dependency/status dependency)
        message (messages/t message-key
                            {:dependency-id (dependency-id-string dependency-id)
                             :status (name status)})]
    (-> (create-envelope stream event-type nil message)
        (merge dependency)
        (cond-> previous-status
          (assoc :dependency/previous-status previous-status)))))

(defn dependency-health-updated
  "Emit when a dependency health projection changes."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/health-updated
                    dependency
                    previous-status
                    :dependency/health-updated))

(defn dependency-recovered
  "Emit when a dependency returns to healthy status."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/recovered
                    dependency
                    previous-status
                    :dependency/recovered))

;------------------------------------------------------------------------------ Layer 6.1
;; Repository intelligence event constructors (RN-19/20)

(defn repo-index-quality-measured
  "Build a :repo-index/quality-measured event.

   Emitted by the index quality tracker (RN-19) when it samples the
   composite quality score for a named index.

   Arguments:
   - stream:        event-stream atom
   - index-id:      string slug identifying the index (e.g. \"main-code-index\")
   - quality-score: number in [0.0, 1.0] — composite quality ratio
   - coverage:      number in [0.0, 1.0] — fraction of files indexed
   - staleness-ms:  int — age of oldest document in the index (milliseconds)
   - opts:          optional map; `:measured-at` adds an explicit inst timestamp"
  [stream index-id quality-score coverage staleness-ms & [opts]]
  (-> (create-envelope stream :repo-index/quality-measured nil
                       (messages/t :repo-index/quality-measured
                                   {:index-id index-id
                                    :quality-score quality-score}))
      (assoc :index/id index-id
             :index/quality-score quality-score
             :index/coverage coverage
             :index/staleness-ms staleness-ms)
      (cond-> (:measured-at opts) (assoc :index/measured-at (:measured-at opts)))))

(defn repo-index-coverage-changed
  "Build a :repo-index/coverage-changed event.

   Emitted by the index quality tracker (RN-20) when the coverage ratio
   of a named index changes beyond the configured threshold.

   Arguments:
   - stream:             event-stream atom
   - index-id:           string slug identifying the index
   - previous-coverage:  number in [0.0, 1.0] — coverage before the change
   - coverage:           number in [0.0, 1.0] — coverage after the change
   - opts:               optional map; `:changed-files` adds the count of
                         files whose index state changed in this transition"
  [stream index-id previous-coverage coverage & [opts]]
  (-> (create-envelope stream :repo-index/coverage-changed nil
                       (messages/t :repo-index/coverage-changed
                                   {:index-id index-id
                                    :coverage coverage}))
      (assoc :index/id index-id
             :index/previous-coverage previous-coverage
             :index/coverage coverage)
      (cond-> (:changed-files opts) (assoc :index/changed-files (:changed-files opts)))))

;------------------------------------------------------------------------------ Layer 6.2
;; Meta-loop events

(defn meta-loop-cycle-completed
  "Emit when a meta-loop cycle completes."
  [stream summary]
  (-> (create-envelope stream :meta-loop/cycle-completed nil
                       (format "Meta-loop cycle: %d signals, %d diagnoses, %d proposals"
                               (:signals summary 0)
                               (:diagnoses summary 0)
                               (:proposals summary 0)))
      (merge summary)))

(defn meta-loop-cycle-failed
  "Emit when a meta-loop cycle throws an unhandled exception."
  [stream error]
  (-> (create-envelope stream :meta-loop/cycle-failed nil
                       (str "Meta-loop cycle failed: " (ex-message error)))
      (assoc :meta-loop/error (ex-message error)
             :meta-loop/error-class (.getName (class error)))))

;------------------------------------------------------------------------------ Layer 6.5
;; Agent stream-stall and session events (GROUP 1+4, GROUP 2)

(defn agent-session-captured
  "Build an :agent/session-captured event recording the backend session ID
   captured from the initial agent handshake.

   Must be emitted synchronously before the first tool call so that
   resume-on-kill has a valid session ID to pass to the backend's
   --resume flag.

   Arguments:
   - stream:      event-stream atom
   - workflow-id: owning workflow UUID
   - phase-id:    keyword identifying the current phase (e.g. :implement)
   - session-id:  string session identifier returned by the backend handshake
   - backend:     keyword identifying the backend (:codex, :claude-code, etc.)"
  [stream workflow-id phase-id session-id backend]
  (-> (create-envelope stream :agent/session-captured workflow-id
                       (str "Session captured for " (name backend)
                            " in phase " (name phase-id)))
      (assoc :workflow/phase phase-id
             :agent/backend backend
             :agent/session-id session-id)))

(defn agent-stream-stalled
  "Build an :agent/stream-stalled event indicating the agent output stream
   has gone silent beyond the configured gap threshold.

   Consumed by the self-healing supervisor to decide whether to kill the
   hung backend process and retry on the next eligible backend.

   Arguments:
   - stream:          event-stream atom
   - workflow-id:     owning workflow UUID
   - phase-id:        keyword identifying the current phase (e.g. :implement)
   - gap-duration-ms: measured silence gap in milliseconds
   - backend:         keyword identifying the backend (:codex, :claude, etc.)

   Valid `:phase/termination-reason` enum values for `phase-completed`:
     :agent-stalled, :curator-rejected, :tool-error, :normal"
  [stream workflow-id phase-id gap-duration-ms backend]
  (-> (create-envelope stream :agent/stream-stalled workflow-id
                       (str "Agent stream stalled in " (name phase-id)
                            " after " gap-duration-ms "ms"
                            " (backend: " (name backend) ")"))
      (assoc :workflow/phase phase-id
             :stream/gap-duration-ms gap-duration-ms
             :agent/backend backend)))

;------------------------------------------------------------------------------ Layer 7
;; Observer / knowledge failure events

(defn observer-signal-failed
  "Emit when observe-workflow-signal! fails to forward a signal to the meta-loop."
  [stream workflow-id error]
  (-> (create-envelope stream :observer/signal-failed workflow-id
                       (str "Observer signal failed: " (ex-message error)))
      (assoc :observer/error (ex-message error))))

(defn knowledge-synthesis-failed
  "Emit when synthesize-patterns! fails."
  [stream error]
  (-> (create-envelope stream :knowledge/synthesis-failed nil
                       (str "Knowledge synthesis failed: " (ex-message error)))
      (assoc :knowledge/error (ex-message error))))

(defn knowledge-promotion-failed
  "Emit when promote-mature-learnings! fails."
  [stream error]
  (-> (create-envelope stream :knowledge/promotion-failed nil
                       (str "Knowledge promotion failed: " (ex-message error)))
      (assoc :knowledge/error (ex-message error))))

;------------------------------------------------------------------------------ Layer 9
;; Routing trigger events (N5-delta-4 §4.2)
;;
;; The automation-edge-correlator classifies these via
;; `triggers/classify-trigger` and opens `:observed` AutomationEdge entries.
;; `:workflow/id` is intentionally nil on these envelopes — routing
;; triggers are PR-scoped, not workflow-scoped; the handler workflow's
;; `:workflow/started` (with `:routing/trigger-event-id` per N15-4) is
;; what brings the workflow id into scope on the correlator side.

(defn pr-monitor-review-comments-arrived
  "Emit when the GitHub webhook (or polling fallback) reports new review
   comments on a PR Miniforge owns (N5-delta-4 §4.2.1).

   `:comments/agent-session-id`, when supplied, names the agent owning
   the PR per the PR↔agent index (AA-2). Omit when no session is
   known — the correlator's affected-agent-session-ids vector goes
   empty and surfaces a warn log on the consumer side."
  ([stream repo number comments-count]
   (pr-monitor-review-comments-arrived stream repo number comments-count nil))
  ([stream repo number comments-count agent-session-id]
   (cond-> (-> (create-envelope stream :pr-monitor/review-comments-arrived nil
                                (str "Review comments on " repo "#" number
                                     " (" comments-count ")"))
               (assoc :pr/repo repo
                      :pr/number number
                      :comments/count comments-count))
     agent-session-id (assoc :comments/agent-session-id agent-session-id))))

(defn pr-monitor-ci-failed
  "Emit when a CI status transitions to a non-success terminal state on a
   PR Miniforge owns (N5-delta-4 §4.2.2).

   `conclusion` is an open keyword — known values: `:failure`,
   `:timed-out`, `:cancelled`. Producers MAY emit additional keywords;
   downstream consumers MUST tolerate them for forward compatibility."
  [stream repo number check-name conclusion]
  (-> (create-envelope stream :pr-monitor/ci-failed nil
                       (str "CI " (name conclusion) " on "
                            repo "#" number " — " check-name))
      (assoc :pr/repo repo
             :pr/number number
             :ci/check-name check-name
             :ci/conclusion conclusion)))

(defn standards-review-posted
  "Emit when a standards-review comment lands on a PR (N5-delta-4 §4.2.3).

   `severity` is an open keyword — known values: `:advisory`, `:blocking`.
   `affected-workflow-run-id`, when supplied, names the workflow the
   reviewer's comment scopes; the correlator maps this through a
   workflow→agent index when one is available (§3.4)."
  ([stream repo number severity]
   (standards-review-posted stream repo number severity nil))
  ([stream repo number severity affected-workflow-run-id]
   (cond-> (-> (create-envelope stream :standards-review/posted nil
                                (str "Standards review " (name severity)
                                     " on " repo "#" number))
               (assoc :pr/repo repo
                      :pr/number number
                      :review/severity severity))
     affected-workflow-run-id (assoc :affected/workflow-run-id
                                     affected-workflow-run-id))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create event stream
  (def stream (create-event-stream))

  ;; Subscribe to events
  (subscribe! stream :console-logger
              (fn [event] (println "Event:" (:event/type event) "-" (:message event))))

  ;; Workflow lifecycle
  (def wf-id (random-uuid))
  (publish! stream (workflow-started stream wf-id {:name "test-workflow"}))
  (publish! stream (phase-started stream wf-id :plan))
  (publish! stream (agent-status stream wf-id :planner :thinking "Analyzing specification"))
  (publish! stream (agent-chunk stream wf-id :planner "Creating plan..."))
  (publish! stream (phase-completed stream wf-id :plan {:outcome :success :duration-ms 5000}))
  (publish! stream (workflow-completed stream wf-id :success 10000))

  ;; Query events
  (get-events stream {:workflow-id wf-id})
  (get-latest-status stream wf-id :planner)

  ;; Unsubscribe
  (unsubscribe! stream :console-logger)

  :leave-this-here)
