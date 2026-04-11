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

//! Supervisory entity contracts for the miniforge platform.
//!
//! Defines the core supervisory domain types that mirror the Clojure
//! Malli schemas in `components/schema/src/ai/miniforge/schema/supervisory.clj`
//! and `components/schema/src/ai/miniforge/schema/core.clj`.
//!
//! All structs derive `Serialize` and `Deserialize` for JSON interop
//! with the Clojure event stream. Field names use `serde(rename)`
//! to match the namespaced-keyword JSON encoding (`"workflow/id"` etc.).

pub mod workflow_run;
pub mod policy_evaluation;
pub mod attention_item;
pub mod agent_session;
pub mod pr_fleet_entry;

pub use workflow_run::*;
pub use policy_evaluation::*;
pub use attention_item::*;
pub use agent_session::*;
pub use pr_fleet_entry::*;
