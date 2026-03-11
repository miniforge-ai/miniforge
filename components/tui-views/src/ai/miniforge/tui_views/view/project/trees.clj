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
  (str (name factor) ": "
       (int (* 100 (or score 0))) "%"
       " (w=" (int (* 100 (or weight 0))) "%"
       (when contribution (str ", c=" (format "%.2f" (double contribution))))
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
    (into [(tree-node (str "CI: " (case ci-status
                                    :passed "\u2713 passed" :failed "\u2718 failed"
                                    :running "\u25cb running" "\u25cb pending"))
                      1 true ci-fg)]
          (mapv ci-check-node ci-checks))))

(defn behind-main-node
  "Build the behind-main indicator node."
  [behind? merge-st]
  (tree-node (str "Behind main: " (if behind?
                                    (str "yes (" (if merge-st (name merge-st) "BEHIND") ")")
                                    "no"))
             1 false (if behind? status-fail status-pass)))

(defn review-node
  "Build the review/approval status node."
  [pr-status]
  (let [[review-label review-fg]
        (case pr-status
          :approved           ["\u2713 approved"           status-pass]
          :changes-requested  ["\u25d0 changes requested"  status-fail]
          :reviewing          ["\u25cb review required"    status-warning]
          :draft              ["\u25d1 draft"              nil]
                              ["\u25cb pending"            status-warning])]
    (tree-node (str "Review: " review-label) 1 false review-fg)))

(defn gates-section-nodes
  "Build gate status header + individual gate nodes."
  [gates]
  (if (seq gates)
    (let [passed (count (filter :gate/passed? gates))
          all?   (= passed (count gates))]
      (into [(tree-node (str "Gates: " passed "/" (count gates) " passed")
                        1 true (if all? status-pass status-warning))]
            (mapv #(tree-node (str (if (:gate/passed? %) "\u2713 " "\u2718 ")
                                   (name (:gate/id %)))
                              2 false (if (:gate/passed? %) status-pass status-fail))
                  gates)))
    [(tree-node "Gates: none" 1)]))

(defn risk-factor-label
  "Format a risk factor for display."
  [{:keys [factor explanation weight score]}]
  (str (name factor) ": " (or explanation "")
       (when weight
         (str " (w=" (int (* 100 weight)) "%, s=" (int (* 100 (or score 0))) "%)"))))

(defn risk-factor-detail-nodes
  "Build expandable detail nodes for a risk factor."
  [{:keys [factor value explanation]}]
  (case factor
    :change-size
    (let [{:keys [additions deletions total]} (if (map? value) value {})]
      (cond-> [(tree-node (str "Change size: " (or total "?") " lines"
                                (when (and total (> total 500)) " (large)"))
                           1 true)]
        (and additions deletions)
        (conj (tree-node (str "+" additions " / -" deletions) 2))))

    :dependency-fanout
    [(tree-node (str "Fanout: " (or value 0) " downstream PRs") 1)]

    :test-coverage-delta
    [(tree-node (str "Coverage: " (when (and value (pos? value)) "+")
                      (or value "?") "% delta")
                1)]

    :author-experience
    (let [{:keys [total-commits recent-commits]} (if (map? value) value {})]
      [(tree-node (str "Author: " (or total-commits "?") " commits, "
                        (or recent-commits "?") " recent")
                  1)])

    :review-staleness
    [(tree-node (str "Last review: " (or value "?") "h ago") 1)]

    :complexity-delta
    [(tree-node (str "Complexity delta: " (if (and value (pos? value)) "+" "")
                      (or value "?"))
                1)]

    :critical-files
    (let [{:keys [critical-files count]} (if (map? value) value {})]
      (into [(tree-node (str "Critical files: " (or count 0) " modified") 1 true)]
            (mapv #(tree-node (str "  " %) 2) (or critical-files []))))

    ;; Default: show explanation
    [(tree-node (str (name factor) ": " (or explanation "")) 1)]))

(defn severity-prefix [severity]
  (case severity
    :critical "\u2718 CRIT " :major "\u2718 MAJR "
    :minor    "\u26a0 MINR " :info  "\u2139 INFO " "\u26a0 "))

(defn severity-color [severity]
  (case severity :critical status-fail :major status-fail :minor status-warning :info status-info nil))

(defn pack-detail-nodes
  "Build detail child nodes for a pack at depth 3.
   Packs may be strings (name only) or maps with :name, :version, :description."
  [pack]
  (if (map? pack)
    (cond-> []
      (:version pack)     (conj (tree-node (str "  Version: " (:version pack)) 3))
      (:description pack) (conj (tree-node (str "  " (:description pack)) 3)))
    []))

(defn packs-applied-nodes
  "Build tree nodes for policy packs applied.
   Each pack is expandable if it has version or description detail."
  [packs]
  (when (seq packs)
    (into [(tree-node (str "Packs applied (" (count packs) "):") 1 true)]
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
                  (pos? (:critical summary 0)) (conj (str (:critical summary) " critical"))
                  (pos? (:major summary 0))    (conj (str (:major summary) " major"))
                  (pos? (:minor summary 0))    (conj (str (:minor summary) " minor"))
                  (pos? (:info summary 0))     (conj (str (:info summary) " info")))]
      (when (seq parts)
        [(tree-node (str "Summary: " (str/join ", " parts)) 1)]))))

(defn violation-detail-nodes
  "Build detail child nodes for a single violation at depth 3."
  [v]
  (let [artifact  (:artifact-path v)
        rule-id   (:rule-id v)
        det-type  (:detection-type v)
        matches   (:matches v [])]
    (cond-> []
      artifact (conj (tree-node (str "  File: " artifact) 3))
      rule-id  (conj (tree-node (str "  Rule: " (if (keyword? rule-id) (str rule-id) (str rule-id))) 3))
      det-type (conj (tree-node (str "  Type: " (name det-type)) 3))
      (seq matches)
      (into (mapv (fn [m]
                    (tree-node (str "  L" (:line m "?") ":" (:column m "?")
                                    " " (or (:context m) (:text m) ""))
                               3))
                  matches)))))

(defn violation-nodes
  "Build tree nodes for individual policy violations.
   Each violation is expandable with detail children showing artifact path,
   rule ID, detection type, and match locations."
  [violations]
  (when (seq violations)
    (into [(tree-node (str "Violations (" (count violations) "):") 1 true)]
          (mapcat (fn [v]
                    (let [details (violation-detail-nodes v)
                          expandable? (seq details)]
                      (into [(tree-node (str (severity-prefix (:severity v))
                                             (or (:message v) (name (get v :rule-id "")))
                                             (when (:auto-fixable? v) " [auto-fix]"))
                                        2 (boolean expandable?) (severity-color (:severity v)))]
                            details)))
                  violations))))

(defn policy-tree [policy]
  (let [summary    (:evaluation/summary policy)
        violations (:evaluation/violations policy [])
        packs      (:evaluation/packs-applied policy [])
        passed?    (:evaluation/passed? policy)]
    (into [(tree-node (str "Policy: "
                           (if passed? "\u2714 passed" "\u2718 FAILED")
                           " (" (:total summary 0) " violations)")
                      0 true (if passed? status-pass status-fail))]
          (concat
           (packs-applied-nodes packs)
           (severity-summary-nodes summary)
           (violation-nodes violations)))))

(defn gates-tree [gates]
  (let [passed (count (filter :gate/passed? gates))
        total  (count gates)
        all?   (= passed total)]
    (into [(tree-node (str "Gates (" passed "/" total " passed):")
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
  [{:label "Intent" :depth 0 :expandable? true}
   {:label (or (get-in evidence [:intent :description])
               "No intent data available")
    :depth 1 :expandable? false}])

(defn phase-nodes
  "Build phase section nodes for evidence tree."
  [phases]
  (into [{:label "Phases" :depth 0 :expandable? true}]
        (mapv (fn [{:keys [phase status]}]
                {:label (str (name phase)
                             (case status
                               :running  " ● running"
                               :success  " ✓ passed"
                               :failed   " ✗ failed"
                               ""))
                 :depth 1 :expandable? false})
              phases)))

(defn validation-nodes
  "Build validation section nodes for evidence tree."
  [evidence]
  [{:label "Validation" :depth 0 :expandable? true}
   {:label (if (get-in evidence [:validation :passed?])
             "✓ All gates passed"
             (str "✗ " (count (get-in evidence [:validation :errors] [])) " error(s)"))
    :depth 1 :expandable? false}])

(defn policy-evidence-nodes
  "Build policy section nodes for evidence tree."
  [evidence]
  [{:label "Policy" :depth 0 :expandable? true}
   {:label (if (get-in evidence [:policy :compliant?])
             "✓ Policy compliant"
             "✗ Policy violations detected")
    :depth 1 :expandable? false}])

;------------------------------------------------------------------------------ Layer 1
;; Composite tree projections (model -> tree nodes)

(defn resolve-detail-enrichment
  "Resolve enrichment data for the detail view's selected PR.
   Falls back to naive derivation when enrichment is absent."
  [model]
  (let [pr-data (get-in model [:detail :selected-pr])]
    {:pr        pr-data
     :readiness (or (:pr/readiness pr-data) (when pr-data (helpers/derive-readiness pr-data)))
     :risk      (or (:pr/risk pr-data) (when pr-data (helpers/derive-risk pr-data)))
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
     (cond-> [(tree-node (str "Readiness: " (int (* 100 score)) "%"
                               (when ready? " \u2714 ready"))
                          0 true (if ready? status-pass status-warning))]
       recommend
       (conj (tree-node (str "Recommend: " (:label recommend) " \u2014 " (:reason recommend))
                         0 false (recommend-action-color (:action recommend)))))
     (concat
      (ci-section-nodes (:pr/ci-status pr) (get pr :pr/ci-checks []))
      [(behind-main-node (:pr/behind-main? pr) (:pr/merge-state pr))]
      [(review-node (:pr/status pr))]
      (when (seq (get pr :pr/depends-on []))
        [(tree-node (str "Dependent PRs: " (count (get pr :pr/depends-on))) 1 true)])
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
        [(tree-node "Miniforge-sourced PR" 0 false status-info)])
      ;; Agent risk assessment (if available)
      (when agent-r
        [(tree-node (str "Agent risk: " (name (:level agent-r)))
                    0 true (risk-level-color (:level agent-r)))
         (tree-node (str "  " (:reason agent-r)) 1)])
      ;; Mechanical risk with factors
      [(tree-node (str "Mechanical risk: " (name level)
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
      :else       [(tree-node "Policy not yet evaluated" 0)
                   (tree-node "Use :review to evaluate policy packs" 1)])))

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
             (tree-node (str "Branch: " (or branch "?")
                             (when-let [author (:pr/author pr)]
                               (when (seq author) (str " by " author))))
                        0)
             (tree-node (str "State: " (helpers/pr-state-label (:pr/status pr))
                             " │ Status: " (helpers/readiness-indicator r-state))
                        0 false (readiness-state-color r-state))
             (tree-node (str "Risk: " (name risk-lvl)
                             " │ Score: " (int (* 100 (get readiness :readiness/score 0))) "%"
                             (when (pos? total)
                               (str " │ +" additions "/-" deletions
                                    (when (pos? files) (str " " files " files")))))
                        0 false (risk-level-color risk-lvl))]
      recommend
      (conj (tree-node (str "Action: " (:label recommend) " — " (:reason recommend))
                       0 false (recommend-action-color (:action recommend))))
      linked-wf
      (conj (tree-node (str "Workflow: " (:name linked-wf)
                            " (" (name (get linked-wf :status :unknown)) ")")
                       0 false status-info))
      (not linked-wf)
      (conj (tree-node "Workflow: not linked" 0)))))

(defn project-evidence-tree
  "Build evidence tree nodes."
  [model]
  (let [detail (:detail model)
        evidence (:evidence detail)
        phases (:phases detail)]
    (into []
      (concat
       (intent-nodes evidence)
       (phase-nodes phases)
       (validation-nodes evidence)
       (policy-evidence-nodes evidence)))))

(defn project-phase-tree
  "Project workflow phases as tree nodes for the detail view."
  [model]
  (let [detail (:detail model)
        phases (:phases detail)
        wf-id (:workflow-id detail)
        wf (some #(when (= (:id %) wf-id) %) (:workflows model []))]
    (if (empty? phases)
      [{:label (str "Workflow " (or (:name wf) (some-> wf-id str (subs 0 8))) " — no phases")
        :depth 0 :expandable? false}]
      (mapv (fn [{:keys [phase status]}]
              {:label (str (name (or phase "?"))
                           (case status
                             :running  " ● running"
                             :success  " ✓ passed"
                             :failed   " ✗ failed"
                             :skipped  " – skipped"
                             ""))
               :depth 0 :expandable? false})
            phases))))

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
      [(tree-node "Press c to start a conversation" 0 false status-info)
       (tree-node "Ask about this PR, request analysis," 1)
       (tree-node "or take actions." 1)]
      (let [msg-nodes (mapcat
                        (fn [{:keys [role content]}]
                          (let [prefix (if (= :user role) "You" "Agent")
                                fg     (if (= :user role) status-info status-pass)
                                lines  (str/split-lines (or content ""))]
                            (into [(tree-node (str prefix ":") 0 false fg)]
                                  (mapcat (fn [line]
                                            (mapv #(tree-node (str "  " %) 1)
                                                  (helpers/wrap-text line wrap-width)))
                                          lines))))
                        messages)
            action-nodes (when (and (seq actions) (not pending?))
                           (into [(tree-node "" 0)
                                  (tree-node "Actions (press number to run):" 0 false status-warning)]
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
            label   (str spinner " Agent thinking... (" elapsed "s)")]
        (if pending?
          (conj nodes (tree-node label 0 false status-warning))
          nodes)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (tree-node "test" 0)
  (ci-section-nodes :passed [{:name "lint" :conclusion :success}])
  (review-node :approved)
  :leave-this-here)
