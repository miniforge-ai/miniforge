//! High-level [`EventClient`] API.
//!
//! Orchestrates startup replay + live file watching into a single async
//! entry point that returns a stream of [`ParsedEvent`] values.
//!
//! ## Typical usage
//!
//! ```rust,no_run
//! use event_client::{EventClient, EventClientConfig};
//!
//! #[tokio::main]
//! async fn main() -> anyhow::Result<()> {
//!     let cfg = EventClientConfig::default_events_dir()?;
//!     let (mut rx, _handle) = EventClient::new(cfg).start().await?;
//!
//!     while let Some(event) = rx.recv().await {
//!         println!(
//!             "[{}] {} id={:?}",
//!             if event.is_replay { "replay" } else { "live" },
//!             event.kind,
//!             event.id,
//!         );
//!     }
//!     Ok(())
//! }
//! ```

use anyhow::Result;
use std::path::PathBuf;
use tokio::sync::mpsc;
use tracing::info;

use crate::events::ParsedEvent;
use crate::replay::replay_events;
use crate::watcher::{start_watcher, WatcherHandle};

// ─── Configuration ────────────────────────────────────────────────────────────

/// Configuration for the [`EventClient`].
#[derive(Debug, Clone)]
pub struct EventClientConfig {
    /// Directory to watch for `.edn` event files.
    ///
    /// Defaults to `~/.miniforge/events/` via [`Self::default_events_dir`].
    pub events_dir: PathBuf,
    /// Internal channel buffer capacity (number of events that can be queued
    /// before the sender blocks).  Defaults to `1024`.
    pub channel_capacity: usize,
}

impl EventClientConfig {
    /// Build a config that points at the default miniforge events directory
    /// (`~/.miniforge/events/`).
    pub fn default_events_dir() -> Result<Self> {
        let home = dirs::home_dir()
            .ok_or_else(|| anyhow::anyhow!("could not determine home directory"))?;
        Ok(Self {
            events_dir: home.join(".miniforge").join("events"),
            channel_capacity: 1024,
        })
    }

    /// Override the events directory.
    pub fn with_events_dir(mut self, path: impl Into<PathBuf>) -> Self {
        self.events_dir = path.into();
        self
    }

    /// Override the channel buffer capacity.
    pub fn with_channel_capacity(mut self, capacity: usize) -> Self {
        self.channel_capacity = capacity;
        self
    }
}

// ─── Client ───────────────────────────────────────────────────────────────────

/// Miniforge event stream client.
///
/// On [`start()`][Self::start]:
/// 1. Ensures `events_dir` exists (creates it if necessary).
/// 2. Replays all existing `.edn` files in chronological (filename-sorted)
///    order, tagging each with `is_replay = true`.
/// 3. Starts a live file-system watcher for new `.edn` files
///    (`is_replay = false`).
///
/// The returned [`mpsc::Receiver`] delivers events in the order they are
/// produced: replayed events first, then live events as they arrive.
pub struct EventClient {
    config: EventClientConfig,
}

impl EventClient {
    /// Create a new client with the given configuration.
    pub fn new(config: EventClientConfig) -> Self {
        Self { config }
    }

    /// Start the client: replay history, then watch for live events.
    ///
    /// Returns `(receiver, handle)`.  The receiver streams [`ParsedEvent`]
    /// values; the handle must be kept alive to continue watching (drop it to
    /// shut down).
    pub async fn start(self) -> Result<(mpsc::Receiver<ParsedEvent>, EventClientHandle)> {
        let events_dir = &self.config.events_dir;

        // Ensure the events directory exists so both replay and watcher work
        std::fs::create_dir_all(events_dir)?;
        info!(dir = %events_dir.display(), "event client starting");

        let (tx, rx) = mpsc::channel::<ParsedEvent>(self.config.channel_capacity);

        // ── Phase 1: Replay existing events ──────────────────────────────────
        let replayed = replay_events(events_dir, &tx).await?;
        info!(replayed, "startup replay complete");

        // ── Phase 2: Start live watcher ───────────────────────────────────────
        let watcher = start_watcher(events_dir, tx)?;
        info!(dir = %events_dir.display(), "live file watching active");

        Ok((rx, EventClientHandle { _watcher: watcher }))
    }
}

// ─── Handle ───────────────────────────────────────────────────────────────────

/// Keeps the live file-system watcher alive.
///
/// Drop this value to stop watching.  The background watcher thread exits
/// cleanly when the internal channel is closed by the drop.
pub struct EventClientHandle {
    _watcher: WatcherHandle,
}

// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::TempDir;

    fn make_config(dir: &TempDir) -> EventClientConfig {
        EventClientConfig {
            events_dir: dir.path().to_path_buf(),
            channel_capacity: 64,
        }
    }

    // ── Configuration builder ─────────────────────────────────────────────────

    #[test]
    fn with_events_dir_overrides_path() {
        let cfg = EventClientConfig {
            events_dir: PathBuf::from("/original"),
            channel_capacity: 16,
        }
        .with_events_dir("/new/path");
        assert_eq!(cfg.events_dir, PathBuf::from("/new/path"));
    }

    #[test]
    fn with_channel_capacity_overrides_capacity() {
        let cfg = EventClientConfig {
            events_dir: PathBuf::from("/tmp"),
            channel_capacity: 16,
        }
        .with_channel_capacity(512);
        assert_eq!(cfg.channel_capacity, 512);
    }

    // ── EventClient lifecycle ─────────────────────────────────────────────────

    #[tokio::test]
    async fn start_creates_events_dir_if_absent() {
        let parent = TempDir::new().unwrap();
        let events_dir = parent.path().join("events");
        assert!(!events_dir.exists());

        let cfg = EventClientConfig {
            events_dir: events_dir.clone(),
            channel_capacity: 16,
        };
        let (_rx, _handle) = EventClient::new(cfg).start().await.unwrap();
        assert!(events_dir.exists(), "events dir should have been created");
    }

    #[tokio::test]
    async fn replays_existing_events_before_live() {
        let dir = TempDir::new().unwrap();
        fs::write(
            dir.path().join("001.edn"),
            r#"{:event/type :task/started :event/id "r1"}"#,
        )
        .unwrap();
        fs::write(
            dir.path().join("002.edn"),
            r#"{:event/type :task/completed :event/id "r2"}"#,
        )
        .unwrap();

        let (mut rx, _handle) = EventClient::new(make_config(&dir)).start().await.unwrap();

        let e1 = rx.recv().await.unwrap();
        assert_eq!(e1.id.as_deref(), Some("r1"));
        assert!(e1.is_replay);

        let e2 = rx.recv().await.unwrap();
        assert_eq!(e2.id.as_deref(), Some("r2"));
        assert!(e2.is_replay);
    }

    #[tokio::test]
    async fn empty_dir_starts_with_no_events_then_accepts_live() {
        let dir = TempDir::new().unwrap();
        let (mut rx, _handle) = EventClient::new(make_config(&dir)).start().await.unwrap();

        // No replay events — channel should be empty
        let result = tokio::time::timeout(
            tokio::time::Duration::from_millis(50),
            rx.recv(),
        )
        .await;
        assert!(result.is_err(), "expected no events for empty directory");
    }
}
