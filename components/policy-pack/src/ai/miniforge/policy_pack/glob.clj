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
(ns ai.miniforge.policy-pack.glob
  "Glob-pattern matching for rule path scope.

   Split out of `registry.support` (rule 210): glob translation is its own
   concern, four namespaces reach it, and keeping the regex builder as a
   separate var there would have pushed that file to four layers.

   The syntax is the one `meta/rule-format` documents for a rule's `globs`
   and `excludeGlobs`:

   | Pattern   | Matches                                              |
   |-----------|------------------------------------------------------|
   | `*`       | any run of characters within one path segment        |
   | `?`       | exactly one character within one path segment        |
   | `**`      | any run of characters, crossing segment boundaries   |
   | `**/`     | zero or more leading segments                        |
   | `{a,b}`   | either alternative (no nesting)                      |
   | `[abc]`   | one of the listed characters; `[!abc]` negates        |

   No wildcard crosses a segment boundary. `*`, `?` and a character
   class — negated or not — all stop at `/`; only `**` and `**/` cross.

   One deliberate difference from `java.nio.file.PathMatcher`: `**/x`
   matches a root-level `x` here, because `**/` stands for *zero* or more
   segments. PathMatcher requires the separator and so excludes the root,
   which silently drops every root-level file from a `**/*.clj` rule. The
   zero-segment reading is the one authors mean, and `rule-format` states
   it.

   Layer 0: glob->regex (the translator)
   Layer 1: pattern->matcher (memoized compile)
   Layer 2: matches?"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} glob->regex
  "Translate a glob pattern to an anchored regex string.

   One left-to-right pass. A `{a,b}` group is emitted inline as
   `(?:a|b)` with a depth flag rather than by recursing, since the syntax
   does not nest; every other character is either a glob metacharacter or
   regex-quoted.

   Arguments:
   - pattern - Glob pattern, '/'-delimited

   Returns:
   - Anchored regex string."
  [pattern]
  (let [n (count pattern)]
    (loop [i 0, in-group? false, out (StringBuilder. "^")]
      (if (>= i n)
        (str (.append out "$"))
        (let [c    (.charAt pattern i)
              next (when (< (inc i) n) (.charAt pattern (inc i)))]
          (cond
            ;; `**/` is zero or more segments; bare `**` crosses them
            (and (= c \*) (= next \*))
            (if (and (< (+ i 2) n) (= (.charAt pattern (+ i 2)) \/))
              (recur (+ i 3) in-group? (.append out "(.*/)?"))
              (recur (+ i 2) in-group? (.append out ".*")))

            (= c \*) (recur (inc i) in-group? (.append out "[^/]*"))
            (= c \?) (recur (inc i) in-group? (.append out "[^/]"))

            (= c \{) (recur (inc i) true (.append out "(?:"))
            (= c \}) (recur (inc i) false (.append out ")"))
            (and (= c \,) in-group?) (recur (inc i) in-group? (.append out "|"))

            ;; A character class passes through, with `!` negation
            ;; translated. A `]` in the first position is literal.
            (= c \[)
            (let [body-start (if (= next \!) (+ i 2) (inc i))
                  close      (loop [j (if (= (.charAt pattern body-start) \]) (inc body-start) body-start)]
                               (cond (>= j n) nil
                                     (= (.charAt pattern j) \]) j
                                     :else (recur (inc j))))]
              (if close
                ;; `/` is the segment separator and no class may cross it.
                ;; A negated class gets it added to the negation set; a
                ;; positive class has it stripped, so `[a/b]` cannot match a
                ;; separator either. (PathMatcher rejects the pattern
                ;; outright instead; matching nothing is the same outcome
                ;; without a throw.)
                (recur (inc close) in-group?
                       (-> out
                           (.append "[")
                           (.append (if (= next \!) "^/" ""))
                           (.append (-> (subs pattern body-start close)
                                        (str/replace "\\" "\\\\")
                                        (str/replace "/" "")))
                           (.append "]")))
                ;; Unterminated class: a literal `[`
                (recur (inc i) in-group? (.append out "\\["))))

            :else
            (recur (inc i) in-group?
                   (.append out (java.util.regex.Pattern/quote (str c))))))))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} pattern->matcher
  "Compiled matcher for a glob pattern, memoized.

   A scan tests every rule's patterns against every file in the repo
   index, so the same handful of patterns are translated tens of
   thousands of times otherwise. The pattern set is bounded by the pack,
   so the cache cannot grow without bound."
  (memoize (fn [pattern]
             (try
               (re-pattern (glob->regex pattern))
               (catch Exception _
                 nil)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} matches?
  "Whether `path` matches the glob `pattern`.

   A pattern that cannot be compiled matches nothing rather than
   throwing — a bad glob must not take down a scan.

   Arguments:
   - pattern - Glob pattern
   - path    - '/'-delimited path

   Returns:
   - true on a match, false otherwise"
  [pattern path]
  (boolean (when-let [re (pattern->matcher pattern)]
             (re-matches re (str path)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (glob->regex "**/*.clj")        ;; => "^(.*/)?[^/]*\\Q.\\Eclj$"
  (matches? "**/*.clj" "build.clj")              ;; => true  (zero segments)
  (matches? "**/*.{js,ts}" "src/a/b.ts")         ;; => true
  (matches? "examples/**" "examples/demo/x.sh")  ;; => true
  (matches? "src/?.clj" "src/a.clj")             ;; => true

  :leave-this-here)
