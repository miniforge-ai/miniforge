;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.experiment
  "Development comparisons. Fixed schedules and private C7 boards prevent peer-state
   leakage through scheduling, versions, validation, feedback, or artifact lineage."
  (:require [ai.miniforge.deliberation-workspace.interface :as board]
            [ai.miniforge.deliberation-lab.codec :as codec]
            [ai.miniforge.deliberation-lab.fixtures :as fixtures]
            [clojure.string :as str]))

(def conditions #{:single :independent :relay :workspace :workspace-ablated})
(def participants [:proposer :skeptic :synthesizer])
(defn initial-workspace [task]
  {:workspace/version 0 :workspace/log [] :workspace/roles participants
   :workspace/objects {"goal" (board/new-object {:id "goal" :type :goal :statement (:brief task)
                                               :role :interpreter :activation "seed" :version 0})}})

(defn- evidence [ws task requested participant]
  (reduce
    (fn [state id]
      (if-let [statement (get-in task [:experiments id])]
        (let [receipt (str "receipt-" id)
              evidence-id (str "evidence-" id "-" (name participant))]
          (if (get-in state [:workspace/objects evidence-id]) state
            (let [state (assoc-in state [:workspace/receipts receipt]
                                  {:source-class :execution :statement statement})
                  tx {:tx/role :verifier :tx/participant participant :tx/activation "fixture-reader"
                      :tx/operations [{:op :attach-evidence :targets ["goal"]
                                       :creates [{:id evidence-id :type :evidence :statement statement
                                                  :attrs {:source-class :execution :receipt receipt}}]}]}]
              ;; The synthetic fixture reader is a trusted runtime capability. Its log
              ;; is replayed by the same reducer; agents cannot manufacture receipts.
              (board/replay-transaction state tx)))) state)) ws requested))

(defn- input-object [o]
  ;; Global sequence numbers and activation identities are audit metadata, not model input.
  (select-keys o [:object/id :object/type :object/status :object/statement :object/links :object/attrs :object/contested?]))
(defn- model-view [ws]
  (let [projection (board/project ws :synthesizer {:visibility :full})]
    {:objects (mapv input-object (:projection/objects projection))}))
(defn- actor [condition turn final?]
  (cond final? :synthesizer
        (= condition :single) :synthesizer
        (= condition :independent) (keyword (str "candidate-" turn))
        :else (nth participants (mod turn (count participants)))))
(defn- role-of [participant]
  (if (str/starts-with? (name participant) "candidate-") :synthesizer participant))
(defn normalized-cost [{:keys [input output]} {:keys [input-per-million output-per-million]}]
  (/ (+ (* input input-per-million) (* output output-per-million)) 1000000.0))

(defn run-cell
  "call receives only the public request and returns a payload plus trusted usage.
   record! must persist before the next call. No live retry follows unknown metering."
  [manifest task condition tier replicate call record!]
  (let [started (System/nanoTime)
        seed (initial-workspace task)
        allowance (:activations tier)]
    (loop [turn 0 boards {} history [] inspected #{} usage {:input 0 :output 0 :provider-usd 0}
           cost 0.0 last-payload nil audit []]
      (let [final? (= turn (dec allowance))
            expired? (>= (/ (- (System/nanoTime) started) 1e9) (:wall-seconds tier))]
        (if (or (= turn allowance) expired? (>= cost (:max-normalized-usd tier)))
          (let [scored (fixtures/score task (:solution last-payload) inspected)
                complete? (and (= turn allowance)
                               (<= cost (:max-normalized-usd tier))
                               (not expired?)
                               (not (:invalid? last-payload))
                               (not (str/blank? (:rationale last-payload))))
                result {:task (:id task) :condition condition :tier (:id tier) :replicate replicate
                        :manifest-hash (codec/digest manifest) :activations turn :usage usage
                        :normalized-usd cost :wall-seconds (/ (- (System/nanoTime) started) 1e9)
                        :status (if complete? :completed :budget-boundary)
                        :score (if complete? scored (assoc scored :success 0))
                        :decision-record (select-keys last-payload [:solution :rationale])
                        :snapshots boards :audit audit}]
            (record! {:kind :cell-completed :result result}) result)
          (let [participant (actor condition turn final?)
                shared? (= condition :workspace)
                key (if shared? :shared participant)
                ws (get boards key seed)
                request {:task (fixtures/public-task task) :role (role-of participant)
                         :closing? final?
                         :id-prefix (str (name participant) "-" (inc (count (filter #(= participant (:participant %)) audit))))
                         :available-experiments (vec (sort (keys (:experiments task))))
                         :context (model-view ws)
                         :feedback (get-in ws [:workspace/feedback participant])
                         :prior (cond (= condition :relay) history
                                      (and (= condition :independent) final?) history
                                      :else [])}
                request-size (alength (.getBytes (codec/encode request) "UTF-8"))
                _ (record! {:kind :activation-started :task (:id task) :condition condition
                            :tier (:id tier) :replicate replicate :turn turn
                            :participant participant :request-hash (codec/digest request)})
                response (if (> request-size (:max-input-bytes manifest))
                           {:payload {:operations [] :inspect [] :solution {}
                                      :rationale "Context overflow" :invalid? true}
                            :usage {:input 0 :output 0 :provider-usd 0} :failure :context-overflow}
                           (try (call request)
                                (catch Exception e
                                  {:payload {:operations [] :inspect [] :solution {}
                                             :rationale "Adapter failed; no retry" :invalid? true}
                                   :usage {:input 0 :output 0 :provider-usd 0}
                                   :failure (or (:category (ex-data e)) :adapter-failure)
                                   :usage-unknown? true})))
                payload (:payload response)
                next-ws (board/activate
                          (assoc-in ws [:workspace/participant-roles participant] (role-of participant))
                          (fn [_] (when (seq (:operations payload)) {:tx/operations (:operations payload)}))
                          participant (if final? :closure :fixed-schedule))
                next-ws (evidence next-ws task (:inspect payload) participant)
                row {:kind :activation :task (:id task) :condition condition :tier (:id tier)
                     :replicate replicate :turn turn :participant participant :request request
                     :response response :workspace next-ws}
                next-usage (merge-with + usage (:usage response))
                next-cost (+ cost (normalized-cost (:usage response) (:price-table manifest)))]
            (record! row)
            (if (or (:failure response) (:usage-unknown? response))
              (let [result {:task (:id task) :condition condition :tier (:id tier) :replicate replicate
                            :manifest-hash (codec/digest manifest) :activations (inc turn)
                            :status :failed :failure (:failure response) :usage-unknown? (:usage-unknown? response)
                            :usage next-usage :normalized-usd next-cost :score {:success 0}
                            :snapshots (assoc boards key next-ws)}]
                (record! {:kind :cell-completed :result result}) result)
              (recur (inc turn) (assoc boards key next-ws)
                     (conj history (select-keys payload [:solution :rationale]))
                     (into inspected (filter (set (keys (:experiments task))) (:inspect payload)))
                     next-usage next-cost payload (conj audit (select-keys row [:turn :participant]))))))))))

(defn report [manifest results]
  (let [cells (sort-by (comp pr-str key) (group-by (juxt :condition :task :tier) results))]
    (str "# Deliberation development experiment\n\nManifest: `" (codec/digest manifest) "`\n\n"
         "**G0: not evaluated.** Short synthetic tasks, development controls, and observed-cost guards; "
         "not a calibrated N15 experiment. Mock runs establish plumbing only.\n\n"
         "| Condition | Task | Tier | Successes / runs | Mean known normalized USD | Unsettled usage |\n|---|---|---|---:|---:|---:|\n"
         (apply str (for [[[condition task tier] rows] cells]
                      (str "| " (name condition) " | " (name task) " | " (name tier) " | "
                           (reduce + (map #(get-in % [:score :success]) rows)) " / " (count rows) " | "
                           (format "%.6f" (/ (reduce + (map :normalized-usd rows)) (count rows))) " | "
                           (count (filter :usage-unknown? rows)) " |\n")))
         "\nShared-minus-blind success deltas (descriptive; no significance claim):\n\n"
         (apply str
           (for [[[task tier replicate] rows] (sort-by (comp pr-str key)
                    (group-by (juxt :task :tier :replicate) results))
                 :let [by-condition (into {} (map (juxt :condition identity)) rows)]
                 :when (every? by-condition [:workspace :workspace-ablated])]
             (str "- " (name task) "/" (name tier) "/" replicate ": "
                  (- (get-in by-condition [:workspace :score :success])
                     (get-in by-condition [:workspace-ablated :score :success])) "\n"))))))
