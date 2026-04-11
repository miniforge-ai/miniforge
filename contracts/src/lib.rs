// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Supervisory entity contracts for miniforge.
//!
//! Rust-native representations of the Clojure event-stream types defined in
//! `ai.miniforge.schema.supervisory` and `ai.miniforge.control-plane.registry`.
//!
//! All structs derive `Serialize` and `Deserialize` and use `#[serde(flatten)]`
//! to accept extra fields — matching the open-map semantics of the Malli schemas
//! on the Clojure side.
//!
//! # Entity overview
//!
//! | Rust struct        | Clojure schema                       |
//! |--------------------|--------------------------------------|
//! | `WorkflowRun`      | `ai.miniforge.schema.supervisory/WorkflowRun` |
//! | `PolicyEvaluation` | `ai.miniforge.schema.supervisory/PolicyEvaluation` |
//! | `AttentionItem`    | `ai.miniforge.schema.supervisory/AttentionItem` |
//! | `AgentSession`     | `ai.miniforge.control-plane.registry` agent record |
//! | `PrFleetEntry`     | PR items rendered by `tui-views.views.pr-fleet` |

mod types;

pub use types::*;
