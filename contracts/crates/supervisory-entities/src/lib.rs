// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! Supervisory entity structs for the miniforge control plane.
//!
//! These types mirror the canonical supervisory entities defined in
//! N5-delta-supervisory-control-plane §3. They are designed to
//! round-trip with the Clojure event stream's JSON serialization
//! (namespaced keys use `/` separators, e.g. `"workflow-run/id"`).
//!
//! All structs derive `Serialize` and `Deserialize` so they can be
//! read from `~/.miniforge/events/` JSON files or exchanged over
//! the fleet HTTP sink.

pub mod entities;

pub use entities::{
    AgentSession, AttentionItem, PolicyEvaluation, PolicyViolation, PrFleetEntry, WorkflowRun,
};
