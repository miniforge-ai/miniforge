//! Contract types for miniforge event-stream events.
//!
//! Events are EDN maps written by Clojure agents to `~/.miniforge/events/`.
//! Each map uses namespace-qualified keyword keys following the `:ns/name`
//! convention (`:event/type`, `:task/id`, …).

use std::path::PathBuf;

// ─── Event type ──────────────────────────────────────────────────────────────

/// Every recognized event type in the miniforge event stream.
///
/// Variants map 1-to-1 with the `:event/type` keyword written by Clojure agents.
/// Unrecognized keywords are preserved as [`EventType::Unknown`] so consumers
/// can forward them without data loss.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum EventType {
    // ── Task lifecycle ───────────────────────────────────────────────────────
    TaskStarted,
    TaskCompleted,
    TaskFailed,
    TaskCancelled,
    // ── Agent lifecycle ──────────────────────────────────────────────────────
    AgentSpawned,
    AgentStarted,
    AgentCompleted,
    AgentFailed,
    // ── Workflow lifecycle ───────────────────────────────────────────────────
    WorkflowStarted,
    WorkflowCompleted,
    WorkflowFailed,
    WorkflowStep,
    // ── Plan lifecycle ───────────────────────────────────────────────────────
    PlanCreated,
    PlanUpdated,
    PlanApproved,
    // ── Review / gate ────────────────────────────────────────────────────────
    ReviewStarted,
    ReviewCompleted,
    ReviewApproved,
    ReviewRejected,
    GatePassed,
    GateFailed,
    // ── PR lifecycle ─────────────────────────────────────────────────────────
    PrCreated,
    PrMerged,
    PrClosed,
    // ── System ───────────────────────────────────────────────────────────────
    SystemStarted,
    SystemStopped,
    /// Any unrecognized event type; carries the raw keyword string (colon stripped).
    Unknown(String),
}

impl EventType {
    /// Parse a Clojure EDN keyword into an [`EventType`].
    ///
    /// Accepts both `:task/started` and `task/started` (colon stripped internally).
    pub fn from_keyword(kw: &str) -> Self {
        let kw = kw.trim_start_matches(':');
        match kw {
            "task/started"       => Self::TaskStarted,
            "task/completed"     => Self::TaskCompleted,
            "task/failed"        => Self::TaskFailed,
            "task/cancelled"     => Self::TaskCancelled,
            "agent/spawned"      => Self::AgentSpawned,
            "agent/started"      => Self::AgentStarted,
            "agent/completed"    => Self::AgentCompleted,
            "agent/failed"       => Self::AgentFailed,
            "workflow/started"   => Self::WorkflowStarted,
            "workflow/completed" => Self::WorkflowCompleted,
            "workflow/failed"    => Self::WorkflowFailed,
            "workflow/step"      => Self::WorkflowStep,
            "plan/created"       => Self::PlanCreated,
            "plan/updated"       => Self::PlanUpdated,
            "plan/approved"      => Self::PlanApproved,
            "review/started"     => Self::ReviewStarted,
            "review/completed"   => Self::ReviewCompleted,
            "review/approved"    => Self::ReviewApproved,
            "review/rejected"    => Self::ReviewRejected,
            "gate/passed"        => Self::GatePassed,
            "gate/failed"        => Self::GateFailed,
            "pr/created"         => Self::PrCreated,
            "pr/merged"          => Self::PrMerged,
            "pr/closed"          => Self::PrClosed,
            "system/started"     => Self::SystemStarted,
            "system/stopped"     => Self::SystemStopped,
            other                => Self::Unknown(other.to_string()),
        }
    }

    /// Returns the canonical EDN keyword string for this event type (colon included).
    pub fn as_keyword(&self) -> &str {
        match self {
            Self::TaskStarted       => ":task/started",
            Self::TaskCompleted     => ":task/completed",
            Self::TaskFailed        => ":task/failed",
            Self::TaskCancelled     => ":task/cancelled",
            Self::AgentSpawned      => ":agent/spawned",
            Self::AgentStarted      => ":agent/started",
            Self::AgentCompleted    => ":agent/completed",
            Self::AgentFailed       => ":agent/failed",
            Self::WorkflowStarted   => ":workflow/started",
            Self::WorkflowCompleted => ":workflow/completed",
            Self::WorkflowFailed    => ":workflow/failed",
            Self::WorkflowStep      => ":workflow/step",
            Self::PlanCreated       => ":plan/created",
            Self::PlanUpdated       => ":plan/updated",
            Self::PlanApproved      => ":plan/approved",
            Self::ReviewStarted     => ":review/started",
            Self::ReviewCompleted   => ":review/completed",
            Self::ReviewApproved    => ":review/approved",
            Self::ReviewRejected    => ":review/rejected",
            Self::GatePassed        => ":gate/passed",
            Self::GateFailed        => ":gate/failed",
            Self::PrCreated         => ":pr/created",
            Self::PrMerged          => ":pr/merged",
            Self::PrClosed          => ":pr/closed",
            Self::SystemStarted     => ":system/started",
            Self::SystemStopped     => ":system/stopped",
            // Unknown stores the colon-stripped form; re-add it for display
            Self::Unknown(kw)       => kw.as_str(),
        }
    }
}

impl std::fmt::Display for EventType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_keyword())
    }
}

// ─── ParsedEvent ─────────────────────────────────────────────────────────────

/// A fully parsed miniforge event, ready for consumption by downstream processors.
///
/// Fields are `Option` because not every event carries every field.  The raw
/// EDN string is always preserved so pass-through or logging consumers lose
/// nothing.
#[derive(Debug, Clone)]
pub struct ParsedEvent {
    /// Unique event identifier (``:event/id``)
    pub id: Option<String>,
    /// Discriminated event type (```:event/type```)
    pub kind: EventType,
    /// ISO-8601 / `#inst` timestamp string (``:event/timestamp``)
    pub timestamp: Option<String>,
    /// Originating component name (``:event/source``)
    pub source: Option<String>,
    /// Task identifier (``:task/id``)
    pub task_id: Option<String>,
    /// Agent identifier (``:agent/id``)
    pub agent_id: Option<String>,
    /// Workflow identifier (``:workflow/id``)
    pub workflow_id: Option<String>,
    /// Human-readable message (``:event/message`` or ``:message``)
    pub message: Option<String>,
    /// Raw EDN string of the complete event payload
    pub raw_edn: String,
    /// Path to the `.edn` file this event was read from
    pub source_file: PathBuf,
    /// `true` when this event came from the startup replay rather than live watching
    pub is_replay: bool,
}

// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_known_event_types() {
        let cases = [
            (":task/started",       EventType::TaskStarted),
            (":task/completed",     EventType::TaskCompleted),
            (":task/failed",        EventType::TaskFailed),
            (":agent/spawned",      EventType::AgentSpawned),
            (":agent/completed",    EventType::AgentCompleted),
            (":workflow/started",   EventType::WorkflowStarted),
            (":workflow/completed", EventType::WorkflowCompleted),
            (":gate/passed",        EventType::GatePassed),
            (":gate/failed",        EventType::GateFailed),
            (":pr/merged",          EventType::PrMerged),
        ];
        for (kw, expected) in &cases {
            assert_eq!(EventType::from_keyword(kw), *expected, "from_keyword({kw})");
        }
    }

    #[test]
    fn unknown_event_type_preserves_keyword() {
        let et = EventType::from_keyword(":custom/my-event");
        assert!(
            matches!(&et, EventType::Unknown(s) if s == "custom/my-event"),
            "expected Unknown(\"custom/my-event\"), got {et:?}"
        );
    }

    #[test]
    fn from_keyword_strips_leading_colon() {
        assert_eq!(
            EventType::from_keyword(":task/started"),
            EventType::from_keyword("task/started"),
        );
    }

    #[test]
    fn display_uses_colon_prefixed_keyword() {
        assert_eq!(EventType::TaskStarted.to_string(), ":task/started");
        assert_eq!(EventType::GatePassed.to_string(),  ":gate/passed");
    }
}
