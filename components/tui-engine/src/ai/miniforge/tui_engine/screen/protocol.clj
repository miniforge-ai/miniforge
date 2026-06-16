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

(ns ai.miniforge.tui-engine.screen.protocol
  "Terminal screen protocol shared by concrete screen implementations.")

(defprotocol IScreen
  "Abstraction over terminal screen for rendering and input.
   Implementations: LanternaScreen (real terminal), MockScreen (testing)."
  (start-screen! [this] "Enter alternate screen mode.")
  (stop-screen! [this] "Exit alternate screen mode, restore terminal.")
  (get-size [this] "Return [cols rows].")
  (put-string! [this col row text fg bg bold?] "Write styled string at position.")
  (clear! [this] "Clear the screen buffer.")
  (refresh! [this] "Flush buffer to terminal (delta rendering).")
  (poll-input [this] "Non-blocking read. Returns key map or nil."))
