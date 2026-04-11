// Title: Miniforge.ai
// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! Event contract types matching the N3 event stream specification.
//!
//! These types mirror the Clojure schemas defined in
//! `components/event-stream/src/ai/miniforge/event_stream/schema.clj`
//! and the authoritative registry in `event_type_registry.clj`.

use std::collections::BTreeMap;
use std::fmt;

/// All event types emitted by the miniforge event stream.
///
/// Corresponds to the `:event/type` keyword in the EDN envelope.
/// The string representation uses the `namespace/name` format
/// (e.g., `"workflow/started"`).
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum EventType {
    // ── Workflow lifecycle ───────────────────────────────────────────────
    WorkflowStarted,
    WorkflowPhaseStarted,
    WorkflowPhaseCompleted,
    WorkflowCompleted,
    WorkflowFailed,
    WorkflowMilestoneReached,

    // ── Agent lifecycle ─────────────────────────────────────────────────
    AgentStarted,
    AgentCompleted,
    AgentFailed,
    AgentStatus,
    AgentChunk,
    AgentMessageSent,
    AgentMessageReceived,

    // ── Gate events ─────────────────────────────────────────────────────
    GateStarted,
    GatePassed,
    GateFailed,

    // ── Tool events ─────────────────────────────────────────────────────
    ToolInvoked,
    ToolCompleted,

    // ── Task/DAG events ─────────────────────────────────────────────────
    TaskStateChanged,
    TaskFrontierEntered,
    TaskSkipPropagated,

    // ── Listener/annotation events ──────────────────────────────────────
    ListenerAttached,
    ListenerDetached,
    AnnotationCreated,

    // ── Control action events ───────────────────────────────────────────
    ControlActionRequested,
    ControlActionExecuted,

    // ── Chain events ────────────────────────────────────────────────────
    ChainStarted,
    ChainStepStarted,
    ChainStepCompleted,
    ChainStepFailed,
    ChainCompleted,
    ChainFailed,

    // ── OCI container events ────────────────────────────────────────────
    OciContainerStarted,
    OciContainerCompleted,

    // ── Supervision events ──────────────────────────────────────────────
    SupervisionToolUseEvaluated,

    // ── Control plane events ────────────────────────────────────────────
    ControlPlaneAgentRegistered,
    ControlPlaneAgentHeartbeat,
    ControlPlaneAgentStateChanged,
    ControlPlaneDecisionCreated,
    ControlPlaneDecisionResolved,

    // ── LLM events ──────────────────────────────────────────────────────
    LlmRequest,
    LlmResponse,

    // ── Self-healing events ─────────────────────────────────────────────
    SelfHealingWorkaroundApplied,
    SelfHealingBackendSwitched,

    // ── Meta-loop events ────────────────────────────────────────────────
    MetaLoopCycleCompleted,

    // ── Reliability/SLI events ──────────────────────────────────────────
    ReliabilitySliComputed,
    ReliabilitySloBreach,
    ReliabilityErrorBudgetUpdate,
    ReliabilityDegradationModeChanged,
    SafeModeEntered,
    SafeModeExited,

    /// Catch-all for event types not yet in this enum.
    /// Stores the raw `"namespace/name"` string.
    Unknown(String),
}

impl EventType {
    /// Parse an event type from the EDN keyword string.
    ///
    /// Accepts both `:namespace/name` (with colon) and `namespace/name` forms.
    pub fn from_keyword(s: &str) -> Self {
        let s = s.strip_prefix(':').unwrap_or(s);
        match s {
            // Workflow
            "workflow/started" => Self::WorkflowStarted,
            "workflow/phase-started" => Self::WorkflowPhaseStarted,
            "workflow/phase-completed" => Self::WorkflowPhaseCompleted,
            "workflow/completed" => Self::WorkflowCompleted,
            "workflow/failed" => Self::WorkflowFailed,
            "workflow/milestone-reached" => Self::WorkflowMilestoneReached,

            // Agent
            "agent/started" => Self::AgentStarted,
            "agent/completed" => Self::AgentCompleted,
            "agent/failed" => Self::AgentFailed,
            "agent/status" => Self::AgentStatus,
            "agent/chunk" => Self::AgentChunk,
            "agent/message-sent" => Self::AgentMessageSent,
            "agent/message-received" => Self::AgentMessageReceived,

            // Gate
            "gate/started" => Self::GateStarted,
            "gate/passed" => Self::GatePassed,
            "gate/failed" => Self::GateFailed,

            // Tool
            "tool/invoked" => Self::ToolInvoked,
            "tool/completed" => Self::ToolCompleted,

            // Task
            "task/state-changed" => Self::TaskStateChanged,
            "task/frontier-entered" => Self::TaskFrontierEntered,
            "task/skip-propagated" => Self::TaskSkipPropagated,

            // Listener / annotation
            "listener/attached" => Self::ListenerAttached,
            "listener/detached" => Self::ListenerDetached,
            "annotation/created" => Self::AnnotationCreated,

            // Control action
            "control-action/requested" => Self::ControlActionRequested,
            "control-action/executed" => Self::ControlActionExecuted,

            // Chain
            "chain/started" => Self::ChainStarted,
            "chain/step-started" => Self::ChainStepStarted,
            "chain/step-completed" => Self::ChainStepCompleted,
            "chain/step-failed" => Self::ChainStepFailed,
            "chain/completed" => Self::ChainCompleted,
            "chain/failed" => Self::ChainFailed,

            // OCI
            "oci/container-started" => Self::OciContainerStarted,
            "oci/container-completed" => Self::OciContainerCompleted,

            // Supervision
            "supervision/tool-use-evaluated" => Self::SupervisionToolUseEvaluated,

            // Control plane
            "control-plane/agent-registered" => Self::ControlPlaneAgentRegistered,
            "control-plane/agent-heartbeat" => Self::ControlPlaneAgentHeartbeat,
            "control-plane/agent-state-changed" => Self::ControlPlaneAgentStateChanged,
            "control-plane/decision-created" => Self::ControlPlaneDecisionCreated,
            "control-plane/decision-resolved" => Self::ControlPlaneDecisionResolved,

            // LLM
            "llm/request" => Self::LlmRequest,
            "llm/response" => Self::LlmResponse,

            // Self-healing
            "self-healing/workaround-applied" => Self::SelfHealingWorkaroundApplied,
            "self-healing/backend-switched" => Self::SelfHealingBackendSwitched,

            // Meta-loop
            "meta-loop/cycle-completed" => Self::MetaLoopCycleCompleted,

            // Reliability
            "reliability/sli-computed" => Self::ReliabilitySliComputed,
            "reliability/slo-breach" => Self::ReliabilitySloBreach,
            "reliability/error-budget-update" => Self::ReliabilityErrorBudgetUpdate,
            "reliability/degradation-mode-changed" => Self::ReliabilityDegradationModeChanged,
            "safe-mode/entered" => Self::SafeModeEntered,
            "safe-mode/exited" => Self::SafeModeExited,

            other => Self::Unknown(other.to_string()),
        }
    }

    /// Serialize back to the canonical `namespace/name` string.
    pub fn as_str(&self) -> &str {
        match self {
            Self::WorkflowStarted => "workflow/started",
            Self::WorkflowPhaseStarted => "workflow/phase-started",
            Self::WorkflowPhaseCompleted => "workflow/phase-completed",
            Self::WorkflowCompleted => "workflow/completed",
            Self::WorkflowFailed => "workflow/failed",
            Self::WorkflowMilestoneReached => "workflow/milestone-reached",

            Self::AgentStarted => "agent/started",
            Self::AgentCompleted => "agent/completed",
            Self::AgentFailed => "agent/failed",
            Self::AgentStatus => "agent/status",
            Self::AgentChunk => "agent/chunk",
            Self::AgentMessageSent => "agent/message-sent",
            Self::AgentMessageReceived => "agent/message-received",

            Self::GateStarted => "gate/started",
            Self::GatePassed => "gate/passed",
            Self::GateFailed => "gate/failed",

            Self::ToolInvoked => "tool/invoked",
            Self::ToolCompleted => "tool/completed",

            Self::TaskStateChanged => "task/state-changed",
            Self::TaskFrontierEntered => "task/frontier-entered",
            Self::TaskSkipPropagated => "task/skip-propagated",

            Self::ListenerAttached => "listener/attached",
            Self::ListenerDetached => "listener/detached",
            Self::AnnotationCreated => "annotation/created",

            Self::ControlActionRequested => "control-action/requested",
            Self::ControlActionExecuted => "control-action/executed",

            Self::ChainStarted => "chain/started",
            Self::ChainStepStarted => "chain/step-started",
            Self::ChainStepCompleted => "chain/step-completed",
            Self::ChainStepFailed => "chain/step-failed",
            Self::ChainCompleted => "chain/completed",
            Self::ChainFailed => "chain/failed",

            Self::OciContainerStarted => "oci/container-started",
            Self::OciContainerCompleted => "oci/container-completed",

            Self::SupervisionToolUseEvaluated => "supervision/tool-use-evaluated",

            Self::ControlPlaneAgentRegistered => "control-plane/agent-registered",
            Self::ControlPlaneAgentHeartbeat => "control-plane/agent-heartbeat",
            Self::ControlPlaneAgentStateChanged => "control-plane/agent-state-changed",
            Self::ControlPlaneDecisionCreated => "control-plane/decision-created",
            Self::ControlPlaneDecisionResolved => "control-plane/decision-resolved",

            Self::LlmRequest => "llm/request",
            Self::LlmResponse => "llm/response",

            Self::SelfHealingWorkaroundApplied => "self-healing/workaround-applied",
            Self::SelfHealingBackendSwitched => "self-healing/backend-switched",

            Self::MetaLoopCycleCompleted => "meta-loop/cycle-completed",

            Self::ReliabilitySliComputed => "reliability/sli-computed",
            Self::ReliabilitySloBreach => "reliability/slo-breach",
            Self::ReliabilityErrorBudgetUpdate => "reliability/error-budget-update",
            Self::ReliabilityDegradationModeChanged => "reliability/degradation-mode-changed",
            Self::SafeModeEntered => "safe-mode/entered",
            Self::SafeModeExited => "safe-mode/exited",

            Self::Unknown(s) => s.as_str(),
        }
    }

    /// Privacy level per N3 spec defaults.
    pub fn privacy(&self) -> PrivacyLevel {
        match self {
            // Public events — safe for external dashboards
            Self::WorkflowStarted
            | Self::WorkflowCompleted
            | Self::WorkflowFailed
            | Self::WorkflowPhaseStarted
            | Self::WorkflowPhaseCompleted
            | Self::WorkflowMilestoneReached
            | Self::GateStarted
            | Self::GatePassed
            | Self::GateFailed
            | Self::ChainStarted
            | Self::ChainCompleted
            | Self::ChainFailed => PrivacyLevel::Public,

            // Confidential — restricted to control-plane operators
            Self::ControlActionRequested | Self::ControlActionExecuted => {
                PrivacyLevel::Confidential
            }

            // Everything else is internal
            _ => PrivacyLevel::Internal,
        }
    }
}

impl fmt::Display for EventType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

/// Privacy classification for events per the N3 spec.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PrivacyLevel {
    /// Safe for external dashboards and audit logs.
    Public,
    /// Visible to team members and internal tools.
    Internal,
    /// Restricted to control-plane operators only.
    Confidential,
}

impl fmt::Display for PrivacyLevel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Public => write!(f, "public"),
            Self::Internal => write!(f, "internal"),
            Self::Confidential => write!(f, "confidential"),
        }
    }
}

/// A parsed miniforge event, matching the N3 event envelope schema.
///
/// Required fields are always present; optional fields use `Option`.
/// Any EDN keys not mapped to named fields land in `extra`.
#[derive(Debug, Clone)]
pub struct Event {
    // ── Required fields ─────────────────────────────────────────────────
    /// The event type (`:event/type` in EDN).
    pub event_type: EventType,

    /// Unique event identifier (`:event/id`).
    pub id: String,

    /// ISO-8601 timestamp string (`:event/timestamp`).
    pub timestamp: String,

    /// Schema version string, e.g., `"1.0.0"` (`:event/version`).
    pub version: String,

    /// Monotonic sequence number within the workflow (`:event/sequence-number`).
    pub sequence_number: i64,

    /// Workflow this event belongs to (`:workflow/id`).
    pub workflow_id: String,

    /// Human-readable message (`:message`).
    pub message: String,

    // ── Optional envelope fields ────────────────────────────────────────
    /// Current workflow phase (`:workflow/phase`).
    pub workflow_phase: Option<String>,

    /// Agent that emitted this event (`:agent/id`).
    pub agent_id: Option<String>,

    /// Specific agent instance (`:agent/instance-id`).
    pub agent_instance_id: Option<String>,

    /// Parent event ID for causality tracking (`:event/parent-id`).
    pub parent_id: Option<String>,

    // ── Payload ─────────────────────────────────────────────────────────
    /// All additional fields from the EDN map not captured above.
    /// Keys are the full EDN keyword strings (e.g., `"agent/duration-ms"`).
    pub extra: BTreeMap<String, EdnValue>,

    // ── Source metadata ─────────────────────────────────────────────────
    /// Path to the .edn file this event was read from.
    pub source_file: Option<String>,
}

/// Lightweight representation of an EDN value for the `extra` map.
///
/// Keeps the event struct independent of the `edn-rs` crate at the API boundary.
#[derive(Debug, Clone, PartialEq)]
pub enum EdnValue {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    String(String),
    Keyword(String),
    Symbol(String),
    Uuid(String),
    Inst(String),
    Vector(Vec<EdnValue>),
    Map(BTreeMap<String, EdnValue>),
    Set(Vec<EdnValue>),
}

impl fmt::Display for EdnValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Nil => write!(f, "nil"),
            Self::Bool(b) => write!(f, "{}", b),
            Self::Int(n) => write!(f, "{}", n),
            Self::Float(n) => write!(f, "{}", n),
            Self::String(s) => write!(f, "\"{}\"", s),
            Self::Keyword(k) => write!(f, ":{}", k),
            Self::Symbol(s) => write!(f, "{}", s),
            Self::Uuid(u) => write!(f, "#uuid \"{}\"", u),
            Self::Inst(i) => write!(f, "#inst \"{}\"", i),
            Self::Vector(v) => {
                write!(f, "[")?;
                for (i, item) in v.iter().enumerate() {
                    if i > 0 {
                        write!(f, " ")?;
                    }
                    write!(f, "{}", item)?;
                }
                write!(f, "]")
            }
            Self::Map(m) => {
                write!(f, "{{")?;
                for (i, (k, v)) in m.iter().enumerate() {
                    if i > 0 {
                        write!(f, ", ")?;
                    }
                    write!(f, ":{} {}", k, v)?;
                }
                write!(f, "}}")
            }
            Self::Set(s) => {
                write!(f, "#{{")?;
                for (i, item) in s.iter().enumerate() {
                    if i > 0 {
                        write!(f, " ")?;
                    }
                    write!(f, "{}", item)?;
                }
                write!(f, "}}")
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_type_round_trip() {
        let cases = vec![
            ("workflow/started", EventType::WorkflowStarted),
            ("agent/chunk", EventType::AgentChunk),
            ("control-action/requested", EventType::ControlActionRequested),
            ("oci/container-started", EventType::OciContainerStarted),
            ("llm/response", EventType::LlmResponse),
        ];
        for (s, expected) in cases {
            let parsed = EventType::from_keyword(s);
            assert_eq!(parsed, expected, "parsing {}", s);
            assert_eq!(parsed.as_str(), s, "round-trip {}", s);
        }
    }

    #[test]
    fn event_type_with_colon_prefix() {
        assert_eq!(
            EventType::from_keyword(":workflow/started"),
            EventType::WorkflowStarted
        );
    }

    #[test]
    fn unknown_event_type() {
        let et = EventType::from_keyword("custom/new-thing");
        assert_eq!(et, EventType::Unknown("custom/new-thing".to_string()));
        assert_eq!(et.as_str(), "custom/new-thing");
    }

    #[test]
    fn privacy_levels() {
        assert_eq!(EventType::WorkflowStarted.privacy(), PrivacyLevel::Public);
        assert_eq!(EventType::AgentChunk.privacy(), PrivacyLevel::Internal);
        assert_eq!(
            EventType::ControlActionRequested.privacy(),
            PrivacyLevel::Confidential
        );
    }
}
