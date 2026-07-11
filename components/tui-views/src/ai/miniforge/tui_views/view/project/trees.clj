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

(ns ai.miniforge.tui-views.view.project.trees
  "Tree node primitives and builders for readiness, risk, policy,
   CI, gates, evidence, and PR detail trees.

   Layer 0: Pure tree-node constructors.
   Layer 1: Composite tree builders that assemble node sequences."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-views.messages :as msg]
   [ai.miniforge.tui-views.palette :as palette]
   [ai.miniforge.tui-views.view.project.helpers :as helpers]))

;------------------------------------------------------------------------------ Layer 0
;; Tree node primitives and semantic status colors

(defn tree-node
  "Build a tree node for the tree widget.
   Optional :fg sets per-node color (theme-independent status color)."
  ([label depth] {:label label :depth depth :expandable? false})
  ([label depth expandable?] {:label label :depth depth :expandable? expandable?})
  ([label depth expandable? fg] {:label label :depth depth :expandable? expandable? :fg fg}))

;; Re-export palette colors for backward compatibility (tests reference sut/status-pass etc.)
(def status-pass    palette/status-pass)
(def status-fail    palette/status-fail)
(def status-warning palette/status-warning)
(def status-info    palette/status-info)

(defn readiness-state-color
  "Map readiness state to fixed status color."
  [state]
  (case state
    :merged       status-pass
    :closed       nil
    :merge-ready  status-pass
    :ci-failing   status-fail
    :behind-main  status-warning
    :needs-review status-warning
    :changes-requested status-fail
    :draft        nil
    :policy-failing status-fail
    :merge-conflicts status-fail
    nil))

(defn risk-level-color
  "Map risk level to fixed status color."
  [level]
  (case level
    :low      status-pass
    :medium   status-warning
    :high     status-fail
    :critical status-fail
    :unevaluated nil
    nil))

(defn recommend-action-color
  "Map recommendation action to fixed status color."
  [action]
  (case action
    :merge        status-pass
    :approve      status-pass
    :do-not-merge status-fail
    :remediate    status-fail
    :review       status-warning
    :evaluate     status-info
    :decompose    status-warning
    :wait         status-warning
    nil))

(defn factor-label
  "Format a readiness/risk factor for display."
  [{:keys [factor weight score contribution]}]
  (str (msg/t :readiness/factor-label
              {:factor  (name factor)
               :score   (int (* 100 (or score 0)))
               :weight  (int (* 100 (or weight 0)))})
       (when contribution (msg/t :readiness/factor-contribution
                                 {:contribution (format "%.2f" (double contribution))}))
       ")"))

(defn ci-check-node
  "Build a tree node for a single CI check result."
  [{:keys [name conclusion]}]
  (let [icon (case conclusion :success "\u2713" :failure "\u2718" :neutral "\u2500" "\u25cb")
        fg   (case conclusion :success status-pass :failure status-fail nil)]
    (tree-node (str icon " " name) 2 false fg)))

(defn ci-section-nodes
  "Build CI status header + individual check nodes."
  [ci-status ci-checks]
  (let [ci-fg (case ci-status :passed status-pass :failed status-fail status-warning)]
    (into [(tree-node (msg/t :ci/status
                             {:status (case ci-status
                                        :passed  (msg/t :ci/passed)
                                        :failed  (msg/t :ci/failed)
                                        :running (msg/t :ci/running)
                                        (msg/t :ci/pending))})
                      1 true ci-fg)]
          (mapv ci-check-node ci-checks))))

(defn behind-main-node
  "Build the behind-main indicator node."
  [behind? merge-st]
  (tree-node (if behind?
               (msg/t :readiness/behind-main-yes
                      {:state (if merge-st (name merge-st) (msg/t :readiness/behind-main-fallback))})
               (msg/t :readiness/behind-main-no))
             1 false (if behind? status-fail status-pass)))

(defn review-node
  "Build the review/approval status node."
  [pr-status]
  (let [[review-label review-fg]
        (case pr-status
          :approved           [(msg/t :review/approved)           status-pass]
          :changes-requested  [(msg/t :review/changes-requested)  status-fail]
          :reviewing          [(msg/t :review/reviewing)          status-warning]
          :draft              [(msg/t :review/draft)              nil]
                              [(msg/t :review/pending)            status-warning])]
    (tree-node (msg/t :review/label {:status review-label}) 1 false review-fg)))

(defn gates-section-nodes
  "Build gate status header + individual gate nodes."
  [gates]
  (if (seq gates)
    (let [passed (count (filter :gate/passed? gates))
          all?   (= passed (count gates))]
      (into [(tree-node (msg/t :gates/summary {:passed passed :total (count gates)})
                        1 true (if all? status-pass status-warning))]
            (mapv #(tree-node (str (if (:gate/passed? %) "\u2713 " "\u2718 ")
                                   (name (:gate/id %)))
                              2 false (if (:gate/passed? %) status-pass status-fail))
                  gates)))
    [(tree-node (msg/t :gates/none) 1)]))

(defn risk-factor-label
  "Format a risk factor for display."
  [{:keys [factor explanation weight score]}]
  (str (name factor) ": " (or explanation "")
       (when weight
         (msg/t :risk/factor-weight
                {:weight (int (* 100 weight)) :score (int (* 100 (or score 0)))}))))

(defn risk-factor-detail-nodes
  "Build expandable detail nodes for a risk factor."
  [{:keys [factor value explanation]}]
  (case factor
    :change-size
    (let [{:keys [additions deletions total]} (if (map? value) value {})]
      (cond-> [(tree-node (str (msg/t :risk/change-size {:total (or total "?")})
                                (when (and total (> total 500)) (msg/t :risk/change-size-large)))
                           1 true)]
        (and additions deletions)
        (conj (tree-node (str "+" additions " / -" deletions) 2))))

    :dependency-fanout
    [(tree-node (msg/t :risk/fanout {:count (or value 0)}) 1)]

    :test-coverage-delta
    [(tree-node (msg/t :risk/coverage-delta
                       {:sign (if (and value (pos? value)) "+" "") :value (or value "?")})
                1)]

    :author-experience
    (let [{:keys [total-commits recent-commits]} (if (map? value) value {})]
      [(tree-node (msg/t :risk/author-experience
                         {:total (or total-commits "?") :recent (or recent-commits "?")})
                  1)])

    :review-staleness
    [(tree-node (msg/t :risk/review-staleness {:hours (or value "?")}) 1)]

    :complexity-delta
    [(tree-node (msg/t :risk/complexity-delta
                       {:sign (if (and value (pos? value)) "+" "") :value (or value "?")})
                1)]

    :critical-files
    (let [{:keys [critical-files count]} (if (map? value) value {})]
      (into [(tree-node (msg/t :risk/critical-files {:count (or count 0)}) 1 true)]
            (mapv #(tree-node (str "  " %) 2) (or critical-files []))))

    ;; Default: show explanation
    [(tree-node (str (name factor) ": " (or explanation "")) 1)]))

(defn severity-prefix [severity]
  (case severity
    :critical (msg/t :violation/severity-critical) :high (msg/t :violation/severity-high)
    :medium   (msg/t :violation/severity-medium)   :low  (msg/t :violation/severity-low)
    :info     (msg/t :violation/severity-info) "\u26a0 "))

(defn severity-color [severity]
  (case severity
    :critical status-fail :high status-fail
    :medium   status-warning :low status-warning
    :info status-info nil))

(defn pack-detail-nodes
  "Build detail child nodes for a pack at depth 3.
   Packs may be strings (name only) or maps with :name, :version, :description."
  [pack]
  (if (map? pack)
    (cond-> []
      (:version pack)     (conj (tree-node (msg/t :policy/pack-version {:version (:version pack)}) 3))
      (:description pack) (conj (tree-node (str "  " (:description pack)) 3)))
    []))

(defn packs-applied-nodes
  "Build tree nodes for policy packs applied.
   Each pack is expandable if it has version or description detail."
  [packs]
  (when (seq packs)
    (into [(tree-node (msg/t :policy/packs-applied {:count (count packs)}) 1 true)]
          (mapcat (fn [pack]
                    (let [pack-name (if (map? pack) (or (:name pack) (str pack)) (str pack))
                          details   (pack-detail-nodes pack)
                          expandable? (seq details)]
                      (into [(tree-node (str "  " pack-name) 2 (boolean expandable?))]
                            details)))
                  packs))))

(defn severity-summary-nodes
  "Build a summary node listing violation counts by severity."
  [summary]
  (when summary
    (let [parts (cond-> []
                  (pos? (:critical summary 0)) (conj (msg/t :violation/count-critical {:count (:critical summary)}))
                  (pos? (:high summary 0))     (conj (msg/t :violation/count-high {:count (:high summary)}))
                  (pos? (:medium summary 0))   (conj (msg/t :violation/count-medium {:count (:medium summary)}))
                  (pos? (:low summary 0))      (conj (msg/t :violation/count-low {:count (:low summary)}))
                  (pos? (:info summary 0))     (conj (msg/t :violation/count-info {:count (:info summary)})))]
      (when (seq parts)
        [(tree-node (msg/t :violation/summary {:parts (str/join ", " parts)}) 1)]))))

(defn violation-detail-nodes
  "Build detail child nodes for a single violation at depth 3."
  [v]
  (let [artifact  (:artifact-path v)
        rule-id   (:rule-id v)
        det-type  (:detection-type v)
        matches   (:matches v [])]
    (cond-> []
      artifact (conj (tree-node (msg/t :violation/file {:path artifact}) 3))
      rule-id  (conj (tree-node (msg/t :violation/rule {:rule (str rule-id)}) 3))
      det-type (conj (tree-node (msg/t :violation/type {:type (name det-type)}) 3))
      (seq matches)
      (into (mapv (fn [m]
                    (tree-node (msg/t :violation/match-location
                                      {:line   (:line m "?")
                                       :column (:column m "?")
                                       :text   (or (:context m) (:text m) "")})
                               3))
                  matches)))))

(defn violation-nodes
  "Build tree nodes for individual policy violations.
   Each violation is expandable with detail children showing artifact path,
   rule ID, detection type, and match locations."
  [violations]
  (when (seq violations)
    (into [(tree-node (msg/t :violation/header {:count (count violations)}) 1 true)]
          (mapcat (fn [v]
                    (let [details (violation-detail-nodes v)
                          expandable? (seq details)]
                      (into [(tree-node (str (severity-prefix (:severity v))
                                             (or (:message v) (name (get v :rule-id "")))
                                             (when (:auto-fixable? v) (msg/t :violation/auto-fix)))
                                        2 (boolean expandable?) (severity-color (:severity v)))]
                            details)))
                  violations))))

(defn policy-tree [policy]
  (let [summary    (:evaluation/summary policy)
        violations (:evaluation/violations policy [])
        packs      (:evaluation/packs-applied policy [])
        passed?    (:evaluation/passed? policy)]
    (into [(tree-node (msg/t :policy/tree-header
                             {:status (if passed? (msg/t :policy/passed) (msg/t :policy/failed))
                              :count  (:total summary 0)})
                      0 true (if passed? status-pass status-fail))]
          (concat
           (packs-applied-nodes packs)
           (severity-summary-nodes summary)
           (violation-nodes violations)))))

(defn gates-tree [gates]
  (let [passed (count (filter :gate/passed? gates))
        total  (count gates)
        all?   (= passed total)]
    (into [(tree-node (msg/t :gates/tree-header {:passed passed :total total})
                      0 true (if all? status-pass status-warning))]
          (mapcat (fn [g]
                    (let [has-msg? (seq (:gate/message g))]
                      (into [(tree-node (str (if (:gate/passed? g) "\u2713 " "\u2718 ")
                                             (name (:gate/id g)))
                                        1 (boolean has-msg?)
                                        (if (:gate/passed? g) status-pass status-fail))]
                            (when has-msg?
                              [(tree-node (str "  " (:gate/message g)) 2)]))))
                  gates))))

(defn intent-nodes
  "Build intent section nodes for evidence tree."
  [evidence]
  [{:label (msg/t :evidence/intent) :depth 0 :expandable? true}
   {:label (or (get-in evidence [:intent :description])
               (msg/t :evidence/no-intent))
    :depth 1 :expandable? false}])

(defn phase-nodes
  "Build phase section nodes for evidence tree."
  [phases]
  (into [{:label (msg/t :evidence/phases) :depth 0 :expandable? true}]
        (mapv (fn [{:keys [phase status]}]
                {:label (str (name phase)
                             (case status
                               :running  (msg/t :phase/running)
                               :success  (msg/t :phase/passed)
                               :failed   (msg/t :phase/failed)
                               ""))
                 :depth 1 :expandable? false})
              phases)))

(defn validation-nodes
  "Build validation section nodes for evidence tree."
  [evidence]
  [{:label (msg/t :evidence/validation) :depth 0 :expandable? true}
   {:label (if (get-in evidence [:validation :passed?])
             (msg/t :evidence/all-gates-passed)
             (msg/t :evidence/error-count
                    {:count (count (get-in evidence [:validation :errors] []))}))
    :depth 1 :expandable? false}])

(defn policy-evidence-nodes
  "Build policy section nodes for evidence tree."
  [evidence]
  [{:label (msg/t :evidence/policy) :depth 0 :expandable? true}
   {:label (if (get-in evidence [:policy :compliant?])
             (msg/t :evidence/policy-compliant)
             (msg/t :evidence/policy-violations))
    :depth 1 :expandable? false}])

;------------------------------------------------------------------------------ Layer 1
;; Composite tree projections (model -> tree nodes)

(defn resolve-detail-enrichment
  "Resolve enrichment data for the detail view's selected PR.
   Falls back to naive derivation when enrichment is absent."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])
        enrichment (when pr-data (helpers/resolve-enrichment pr-data))]
    {:pr        pr-data
     :readiness (:readiness enrichment)
     :risk      (:risk enrichment)
     :policy    (:pr/policy pr-data)
     :gates     (get pr-data :pr/gate-results [])}))

(defn project-readiness-tree
  "Build readiness tree nodes for the tree widget.
   Each factor is expandable with detail nodes at depth 1+."
  [model]
  (let [{:keys [pr readiness]} (resolve-detail-enrichment model)
        score     (get readiness :readiness/score 0)
        ready?    (:readiness/ready? readiness)
        recommend (when pr (helpers/derive-recommendation pr))]
    (into
     (cond-> [(tree-node (str (msg/t :readiness/score {:score (int (* 100 score))})
                               (when ready? (msg/t :readiness/ready-suffix)))
                          0 true (if ready? status-pass status-warning))]
       recommend
       (conj (tree-node (msg/t :readiness/recommend
                              {:label (:label recommend) :reason (:reason recommend)})
                         0 false (recommend-action-color (:action recommend)))))
     (concat
      (ci-section-nodes (:pr/ci-status pr) (get pr :pr/ci-checks []))
      [(behind-main-node (:pr/behind-main? pr) (:pr/merge-state pr))]
      [(review-node (:pr/status pr))]
      (when (seq (get pr :pr/depends-on []))
        [(tree-node (msg/t :readiness/dependent-prs {:count (count (get pr :pr/depends-on))}) 1 true)])
      (gates-section-nodes (get pr :pr/gate-results []))))))

(defn project-risk-tree
  "Build risk tree nodes for the tree widget.
   Shows agent risk assessment (when available) and mechanical risk factors."
  [model]
  (let [{:keys [risk]} (resolve-detail-enrichment model)
        pr      (get-in model [:detail :selected-pr])
        pr-id   (when pr [(:pr/repo pr) (:pr/number pr)])
        agent-r (when pr-id (get-in model [:agent-risk pr-id]))
        wf-id   (:pr/workflow-id pr)
        level   (get risk :risk/level :unevaluated)
        score   (:risk/score risk)
        factors (:risk/factors risk [])]
    (concat
      ;; Provenance indicator
      (when wf-id
        [(tree-node (msg/t :risk/miniforge-sourced) 0 false status-info)])
      ;; Agent risk assessment (if available)
      (when agent-r
        [(tree-node (msg/t :risk/agent-risk {:level (name (:level agent-r))})
                    0 true (risk-level-color (:level agent-r)))
         (tree-node (str "  " (:reason agent-r)) 1)])
      ;; Mechanical risk with factors
      [(tree-node (str (msg/t :risk/mechanical-risk {:level (name level)})
                       (when score (str " (" (format "%.2f" (double score)) ")")))
                  0 true (risk-level-color level))]
      (mapcat risk-factor-detail-nodes factors))))

(defn project-gate-list
  "Build gate/policy result list for the tree widget."
  [model]
  (let [{:keys [policy gates]} (resolve-detail-enrichment model)]
    (cond
      policy      (policy-tree policy)
      (seq gates) (gates-tree gates)
      :else       [(tree-node (msg/t :policy/not-evaluated) 0)
                   (tree-node (msg/t :policy/evaluate-hint) 1)])))

(defn project-pr-summary
  "Build summary tree nodes for the PR detail top pane.
   Shows PR metadata, status, and linked workflow at a glance."
  [model]
  (let [{:keys [pr readiness risk]} (resolve-detail-enrichment model)
        r-state  (get readiness :readiness/state :unknown)
        risk-lvl (get risk :risk/level :unevaluated)
        recommend (when pr (helpers/derive-recommendation pr))
        ;; Find linked workflow: direct lookup via workflow-id, fallback to branch name match
        wf-id    (:pr/workflow-id pr)
        branch   (:pr/branch pr)
        wfs      (get model :workflows [])
        linked-wf (helpers/find-linked-workflow wfs wf-id branch)
        additions (get pr :pr/additions 0)
        deletions (get pr :pr/deletions 0)
        total     (+ additions deletions)
        files     (get pr :pr/changed-files-count 0)]
    (cond-> [(tree-node (str (:pr/repo pr "") " #" (:pr/number pr "?"))
                        0 false status-info)
             (tree-node (str "  " (:pr/title pr "")) 0)
             (tree-node (str (msg/t :readiness/branch {:branch (or branch "?")})
                             (when-let [author (:pr/author pr)]
                               (when (seq author) (msg/t :readiness/branch-author {:author author}))))
                        0)
             (tree-node (msg/t :readiness/state-status
                               {:state     (helpers/pr-state-label (:pr/status pr))
                                :indicator (helpers/readiness-indicator r-state)})
                        0 false (readiness-state-color r-state))
             (tree-node (str (msg/t :readiness/risk-score
                                    {:level (name risk-lvl)
                                     :score (int (* 100 (get readiness :readiness/score 0)))})
                             (when (pos? total)
                               (str (msg/t :readiness/diff-stat {:additions additions :deletions deletions})
                                    (when (pos? files) (msg/t :readiness/files-count {:files files})))))
                        0 false (risk-level-color risk-lvl))]
      recommend
      (conj (tree-node (msg/t :readiness/action
                              {:label (:label recommend) :reason (:reason recommend)})
                       0 false (recommend-action-color (:action recommend))))
      linked-wf
      (conj (tree-node (msg/t :readiness/workflow-linked
                              {:name   (:name linked-wf)
                               :status (name (get linked-wf :status :unknown))})
                       0 false status-info))
      (not linked-wf)
      (conj (tree-node (msg/t :readiness/workflow-not-linked) 0)))))

(defn- aggregate-evidence-nodes
  "Build evidence summary nodes across all workflows for the top-level view."
  [workflows]
  (if (empty? workflows)
    [{:label (msg/t :evidence/no-workflows) :depth 0 :expandable? false}]
    (into []
      (mapcat (fn [wf]
                (let [gate-results (get wf :gate-results [])
                      total  (count gate-results)
                      passed (count (filter :passed? gate-results))
                      status (:status wf)
                      phase  (:phase wf)
                      gates-str (if (zero? total)
                                  (msg/t :evidence/no-gates-recorded)
                                  (msg/t :evidence/gates-passed {:passed passed :total total}))
                      row-fg (case status
                               (:success :completed) status-pass
                               :running              status-info
                               :failed               status-fail
                               nil)]
                  [{:label (str (helpers/status-char status) " " (:name wf "?"))
                    :depth 0 :expandable? false :fg row-fg}
                   {:label (str "  " gates-str
                                (when phase (str " \u2502 " (name phase))))
                    :depth 1 :expandable? false}]))
              workflows))))

(defn project-evidence-tree
  "Build evidence tree nodes.
   When a workflow is selected (leaf context), shows that workflow's evidence detail.
   At the top-level aggregate view (no workflow selected), shows a summary across all workflows."
  [model]
  (let [workflow-id (get-in model [:detail :workflow-id])]
    (if workflow-id
      (let [detail   (:detail model)
            evidence (:evidence detail)
            phases   (:phases detail)]
        (into []
          (concat
           (intent-nodes evidence)
           (phase-nodes phases)
           (validation-nodes evidence)
           (policy-evidence-nodes evidence))))
      (aggregate-evidence-nodes (get model :workflows [])))))

(defn project-phase-tree
  "Project workflow phases as tree nodes for the detail view."
  [model]
  (let [detail (:detail model)
        phases (:phases detail)
        wf-id (:workflow-id detail)
        wf (some #(when (= (:id %) wf-id) %) (:workflows model []))]
    (if (empty? phases)
      [{:label (msg/t :phase/workflow-no-phases
                      {:name (or (:name wf) (some-> wf-id str (subs 0 8)))})
        :depth 0 :expandable? false}]
      (mapv (fn [{:keys [phase status]}]
              {:label (str (name (or phase "?"))
                           (case status
                             :running  (msg/t :phase/running)
                             :success  (msg/t :phase/passed)
                             :failed   (msg/t :phase/failed)
                             :skipped  (msg/t :phase/skipped)
                             ""))
               :depth 0 :expandable? false})
            phases))))

(defn clean-agent-content
  "Clean raw agent content for display:
   - Unescape literal \\n sequences to real newlines
   - Remove JSON SSE event lines (lines starting with '{\"type\":')
   - Strip markdown link syntax [text](url) → text"
  [content]
  (->> (-> (or content "")
           (str/replace "\\n" "\n"))
       str/split-lines
       (remove #(str/starts-with? (str/trim %) "{\"type\":"))
       (map #(str/replace % #"\[([^\]]+)\]\([^)]+\)" "$1"))))

(defn project-chat-messages
  "Project chat messages as tree nodes for the agent panel.
   Shows conversation history with role-based styling and numbered actions.
   Uses :_panel-cols (injected by interpreter) for word-wrapping."
  [model]
  (let [messages (get-in model [:chat :messages] [])
        pending? (get-in model [:chat :pending?] false)
        actions  (get-in model [:chat :suggested-actions] [])
        ;; Panel cols minus tree indent overhead (depth*2 + icon "  " + label prefix "  ")
        panel-cols (or (:_panel-cols model) 60)
        wrap-width (max 20 (- panel-cols 6))]
    (if (empty? messages)
      [(tree-node (msg/t :chat/start-conversation) 0 false status-info)
       (tree-node (msg/t :chat/start-hint-1) 1)
       (tree-node (msg/t :chat/start-hint-2) 1)]
      (let [msg-nodes (mapcat
                        (fn [{:keys [role content]}]
                          (let [prefix (if (= :user role) (msg/t :chat/role-you) (msg/t :chat/role-agent))
                                fg     (if (= :user role) status-info status-pass)
                                lines  (clean-agent-content content)]
                            (into [(tree-node (str prefix ":") 0 false fg)]
                                  (mapcat (fn [line]
                                            (mapv #(tree-node (str "  " %) 1)
                                                  (helpers/wrap-text line wrap-width)))
                                          lines))))
                        messages)
            action-nodes (when (and (seq actions) (not pending?))
                           (into [(tree-node "" 0)
                                  (tree-node (msg/t :chat/actions-header) 0 false status-warning)]
                                 (mapcat
                                   (fn [i {:keys [label description]}]
                                     (let [text (str (inc i) ") " label
                                                     (when description
                                                       (str " — " description)))]
                                       (mapv #(tree-node (str "  " %) 1 false status-info)
                                             (helpers/wrap-text text wrap-width))))
                                   (range) actions)))
            nodes (cond-> (vec msg-nodes)
                    (seq action-nodes) (into action-nodes))
            since (get-in model [:chat :pending-since])
            elapsed (if since
                      (quot (- (System/currentTimeMillis) since) 1000)
                      0)
            spinner (get ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
                         (mod elapsed 10))
            label   (msg/t :chat/agent-thinking {:spinner spinner :elapsed elapsed})]
        (if pending?
          (conj nodes (tree-node label 0 false status-warning))
          nodes)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (tree-node "test" 0)
  (ci-section-nodes :passed [{:name "lint" :conclusion :success}])
  (review-node :approved)
  :leave-this-here)
