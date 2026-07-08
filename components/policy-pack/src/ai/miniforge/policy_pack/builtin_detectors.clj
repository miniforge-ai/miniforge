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

(ns ai.miniforge.policy-pack.builtin-detectors
  "Built-in :custom detectors, registered at namespace load. These exist so a
   rule's policy parameters stay DATA — read from the rule's
   `:rule/detection :detector-config` — instead of being encoded in a regex.
   The rule reaches the detector via `:policy-pack/rule` in context (threaded in
   by `detection/run-resolved-custom`)."
  (:require
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Approved EC2 instance types

(def ^:private default-approved-instance-families
  "Fallback approved EC2 instance-type families, used only when a rule supplies
   no :approved-families in its :detector-config."
  ["t3" "t4g" "m5" "m6i" "c5" "c6i" "r5" "r6i"])

(defn- approved-families
  "The approved-family set for this rule. A configured `:approved-families` must
   be a sequential collection of strings; anything else is a misconfigured pack
   and throws (surfaced as a :custom-error by `run-resolved-custom`) rather than
   silently coercing — e.g. a bare string would `set` into a char-set."
  [context]
  (let [configured (get-in context [:policy-pack/rule :rule/detection :detector-config :approved-families])]
    (when (and configured (not (and (sequential? configured) (every? string? configured))))
      (throw (ex-info "approved-families must be a sequential collection of strings"
                      {:approved-families configured})))
    (set (or configured default-approved-instance-families))))

(defn- instance-type-literals
  "String literals assigned to instance_type in Terraform content, e.g.
   `instance_type = \"t3.micro\"` -> \"t3.micro\". Variable references
   (`instance_type = var.x`) carry no literal and are not evaluated here — the
   same limitation the prior regex had."
  [content]
  (map second (re-seq #"instance_type\s*=\s*\"([^\"]+)\"" (str content))))

(defn- approved?
  "True when `literal` has a `family.size` shape whose family is approved. A
   dotless/placeholder literal (\"t3\", \"\") has no family and is never approved
   — matching the prior regex, which required a `family.` prefix."
  [approved-set literal]
  (boolean (when-let [[_ fam] (re-matches #"([^.]+)\..+" literal)]
             (contains? approved-set fam))))

(defn check-approved-instance-types
  "Flag EC2 `instance_type` literals whose family is not approved. The approved
   families come from the rule's `:detector-config :approved-families` (data),
   falling back to `default-approved-instance-families`."
  [artifact context]
  (let [approved  (approved-families context)
        offenders (remove #(approved? approved %)
                          (instance-type-literals (:artifact/content artifact)))]
    (when (seq offenders)
      {:matches  (vec offenders)
       :message  (get-in context [:policy-pack/rule :rule/enforcement :message])})))

;------------------------------------------------------------------------------ Layer 1
;; Registration (load-time side effect — see ns docstring)

(defn- register!
  []
  (detection/register-custom-fn!
   'ai.miniforge.policy-pack.builtin-detectors/check-approved-instance-types
   check-approved-instance-types))

(register!)
