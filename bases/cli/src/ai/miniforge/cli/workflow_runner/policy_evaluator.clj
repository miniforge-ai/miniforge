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
(ns ai.miniforge.cli.workflow-runner.policy-evaluator
  "The policy evaluator `:re-evaluate` interventions run (Phase D D-3b),
   registered by `workflow-runner.control`: `evaluate-external-pr` over
   the packs installed under `<home>/packs`, against the diff `gh pr diff`
   returns. That is the installed set `mf policy list` shows; the
   classpath built-ins it also lists are not evaluated here.

   Anything short of a real verdict comes back as an anomaly whose
   `:failure/reason` the operator puts on the failed intervention, and
   no PolicyEvaluation is published: no PR in the request, a pack that
   failed to load (a verdict without it is not the verdict asked for),
   no packs installed (a pass over zero packs is a verdict nothing
   computed), or no diff."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.web.github :as github]
   [ai.miniforge.messages.interface :as messages]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private system-message
  (messages/create-translator "config/cli/messages/system.edn" :cli/system))

(defn ^{:stratum 0} pr-coordinates
  "`{:repo :number}` of the PR a request targets: the console's
   `repo` / `number` details, else a `<repo>#<number>` target id. Nil when
   neither names a PR."
  [{:policy/keys [details target-id]}]
  (let [[_ target-repo target-number] (some->> target-id (re-matches #"(.+)#(\d+)"))
        ;; Details keys arrive as strings off the wire (transit leaves
        ;; nested map keys untagged); in-process callers use keywords.
        repo (get details "repo" (get details :repo target-repo))
        number (some-> (get details "number" (get details :number target-number))
                       str
                       parse-long)]
    (when (and (not (str/blank? repo)) number)
      {:repo repo :number number})))

(defn- ^{:stratum 0} pr-data
  "The `evaluate-external-pr` input. Changed files come from the diff:
   packs scoped by `:file-globs` apply only when they match one."
  [{:keys [repo number]} diff]
  {:diff diff
   :repo repo
   :pr-number number
   :changed-files (mapv :artifact/path (policy-pack/parse-pr-diff diff))})

(defn- ^{:stratum 0} installed-packs
  []
  (policy-pack/load-all-packs (app-config/packs-dir)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} refusal
  [anomaly-type reason request]
  (anomaly/anomaly anomaly-type
                   (system-message :anomaly/re-evaluate-refused
                                   {:target (:policy/target-id request)
                                    :reason (name reason)})
                   {:failure/reason reason}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate
  "The registered evaluator, `(fn [request] → evaluation)`. The two-arity
   form takes the evaluator, the pack loader (returning
   `load-all-packs`' `{:loaded … :failed …}`), and the diff fetcher."
  ([request]
   (evaluate {:evaluate-pr policy-pack/evaluate-external-pr
              :load-packs installed-packs
              :fetch-diff github/fetch-pr-diff}
             request))
  ([{:keys [evaluate-pr load-packs fetch-diff]} request]
   (let [pr (pr-coordinates request)
         packs (when pr (load-packs))
         usable? (and (seq (:loaded packs)) (empty? (:failed packs)))
         diff (when usable? (fetch-diff (:repo pr) (:number pr)))]
     (cond
       (nil? pr) (refusal :invalid-input :no-pr request)
       (seq (:failed packs)) (refusal :fault :pack-load-failed request)
       (empty? (:loaded packs)) (refusal :not-found :no-policy-packs request)
       (nil? diff) (refusal :unavailable :no-diff request)
       :else (evaluate-pr (vec (:loaded packs)) (pr-data pr diff))))))
