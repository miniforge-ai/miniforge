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

(ns ai.miniforge.agent.context-budget
  "Shared N12 §5 context-window budgeting for agent prompt assembly.

   A role assembles its user-prompt with eagerly-inlined bodies — the
   planner's in-scope files, the reviewer's artifact-under-review. When
   that prompt would overflow the model's context window, the inlined
   bodies are shed to a manifest (paths + size): the bodies stay reachable
   via the MCP context cache (`context_read`, which reads through to the
   worktree/source-root on a miss), so the agent keeps a query surface,
   and only if the shed form still overflows do we mark an irreducible
   overflow so the caller can bail pre-flight rather than pay for a request
   the model will reject.

   This namespace holds the role-agnostic ladder. Each role supplies its
   full/shed prompt builders and its sheddable-unit count via
   `assemble-within-budget`; the planner and reviewer each wrap it with a
   role-specific signature (and tests) over this core."
  (:require [ai.miniforge.llm.interface :as llm]))

(defn assemble-within-budget
  "Assemble a user-prompt within `model`'s context window (N12 §5).

   Options map:
   - `:effective-system` — the system prompt as it will be sent (including
     any standards addendum). Counted toward the estimate; never trimmed
     here — shedding the inlined bodies is preferred, and an irreducible
     overflow bails rather than silently dropping system content.
   - `:model` — backend model-id string. A nil/uncatalogued window means
     the budget is unknown, so nothing is ever shed (files stay inlined).
   - `:reserve` — headroom kept below the real window for the agent CLI's
     own unmeasured baseline (its system prompt, tool schemas, host
     plugins/skills). The shed/bail decision fires against
     `effective-window = window - reserve`. Clamped to at most half the
     window so a misconfigured reserve >= window can't zero the effective
     window and make every prompt look over-budget; `:reserve-clamped?`
     flags that.
   - `:build-full` — 0-arg fn returning the user-prompt with bodies inlined.
   - `:build-shed` — 0-arg fn returning the user-prompt with bodies shed to
     a manifest. Only called when the full prompt is over budget and there
     is something to shed.
   - `:shed-count` — number of sheddable units (e.g. inlined file bodies).
     Zero means nothing to shed, so an over-budget prompt is irreducibly
     over.
   - `:max-inline-fraction` — ceiling on the eagerly-inlined form as a
     fraction of the effective window (default 1.0 = shed only on window
     overflow). Window-FIT alone is a trap: the 2026-07-21 dogfood sent a
     planner prompt at 99.2% of the effective window — it fit, and the turn
     could not complete inside the phase timeout. Roles that inline large
     bodies should cap well below 1.0; the shed keeps the bodies reachable
     via `context_read`, so shedding early costs a fetch, not information.

   Returns {:user-prompt :shed? :over-after-shed? :window :reserve
            :effective-window :reserve-clamped? :inline-cap :est-full
            :est-final :file-count}; `:over-after-shed?` marks an
   irreducible overflow of the WINDOW (not of the inline cap)."
  [{:keys [effective-system model reserve build-full build-shed shed-count
           max-inline-fraction]
    :or   {max-inline-fraction 1.0}}]
  (let [window      (llm/context-window-for-model-id model)
        eff-reserve (when window (min reserve (quot window 2)))
        clamped?    (boolean (and window (> reserve eff-reserve)))
        effective   (when window (- window eff-reserve))
        inline-fraction (if (and (number? max-inline-fraction)
                                 (Double/isFinite (double max-inline-fraction)))
                          (-> (double max-inline-fraction) (max 0.0) (min 1.0))
                          1.0)
        inline-cap  (when effective (long (* effective inline-fraction)))
        est         (fn [user] (:estimated-input-tokens
                                (llm/prompt-size-telemetry effective-system user)))
        full        (build-full)
        est-full    (est full)
        base        {:window window :reserve reserve :effective-window effective
                     :reserve-clamped? clamped? :inline-cap inline-cap
                     :est-full est-full :file-count shed-count}]
    (cond
      (or (nil? effective) (< est-full inline-cap))
      (merge base {:user-prompt full :shed? false :over-after-shed? false :est-final est-full})

      (zero? shed-count)                 ; nothing to shed — over only if past the window
      (merge base {:user-prompt full :shed? false
                   :over-after-shed? (>= est-full effective) :est-final est-full})

      :else
      (let [shed     (build-shed)
            est-shed (est shed)]
        (merge base {:user-prompt shed :shed? true
                     :over-after-shed? (>= est-shed effective) :est-final est-shed})))))
