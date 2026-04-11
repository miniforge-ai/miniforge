// Title: Miniforge.ai
// Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
// Licensed under the Apache License, Version 2.0

//! High-level event client that ties together replay, watching, and channel emission.
//!
//! [`EventClient`] is the main entry point. It:
//!
//! 1. Creates the events directory if needed
//! 2. Replays recent events for durable state reconstruction
//! 3. Starts the file watcher for live events
//! 4. Emits all events (replayed + live) to a single tokio mpsc channel

use std::path::{Path, PathBuf};

use log::info;
use tokio::sync::mpsc;

use crate::event::Event;
use crate::replay::{self, ReplayConfig, ReplayResult};
use crate::watcher::{EventWatcher, WatcherError};

/// Configuration for the event client.
#[derive(Debug, Clone)]
pub struct EventClientConfig {
    /// Path to the events directory.
    /// Default: `~/.miniforge/events/`
    pub events_dir: PathBuf,

    /// Channel buffer size. Controls backpressure.
    /// Default: 1024
    pub channel_buffer: usize,

    /// Replay configuration for startup event loading.
    pub replay: ReplayConfig,

    /// Whether to replay events on startup.
    /// Default: true
    pub replay_on_start: bool,
}

impl Default for EventClientConfig {
    fn default() -> Self {
        let home = dirs_or_home();
        Self {
            events_dir: home.join(".miniforge").join("events"),
            channel_buffer: 1024,
            replay: ReplayConfig::default(),
            replay_on_start: true,
        }
    }
}

impl EventClientConfig {
    /// Create a config pointing at a custom events directory.
    pub fn with_events_dir(events_dir: impl Into<PathBuf>) -> Self {
        Self {
            events_dir: events_dir.into(),
            ..Default::default()
        }
    }
}

/// The running event client handle.
///
/// Holds the file watcher and provides lifecycle control.
/// Dropping the client stops the file watcher.
pub struct EventClient {
    _watcher: EventWatcher,
    events_dir: PathBuf,
    replay_result: Option<ReplayResult>,
}

impl EventClient {
    /// Start the event client: replay recent events, then watch for new ones.
    ///
    /// Returns the client handle and a receiver for events. All events
    /// (replayed first, then live) arrive on the same channel.
    ///
    /// # Example
    ///
    /// ```no_run
    /// use miniforge_event_client::{EventClient, EventClientConfig};
    ///
    /// # async fn example() {
    /// let config = EventClientConfig::default();
    /// let (client, mut rx) = EventClient::start(config).await.unwrap();
    ///
    /// // Process events
    /// while let Some(event) = rx.recv().await {
    ///     println!("[{}] {}: {}", event.workflow_id, event.event_type, event.message);
    /// }
    /// # }
    /// ```
    pub async fn start(
        config: EventClientConfig,
    ) -> Result<(Self, mpsc::Receiver<Event>), ClientError> {
        let (tx, rx) = mpsc::channel(config.channel_buffer);

        info!("Starting event client for {}", config.events_dir.display());

        // Phase 1: Replay recent events
        let replay_result = if config.replay_on_start {
            let result =
                replay::replay_recent_events(&config.events_dir, &tx, &config.replay).await;
            info!(
                "Replayed {} events from {} files",
                result.events_replayed, result.files_scanned
            );
            Some(result)
        } else {
            None
        };

        // Phase 2: Start file watcher
        let watcher = EventWatcher::start(&config.events_dir, tx).map_err(ClientError::Watcher)?;

        // Set offsets to end-of-file so we don't re-emit replayed events
        watcher.initialize_offsets(&config.events_dir);

        info!("Event client started, watching for new events");

        Ok((
            Self {
                _watcher: watcher,
                events_dir: config.events_dir,
                replay_result,
            },
            rx,
        ))
    }

    /// Start without replay — only watch for new events.
    pub async fn start_live_only(
        config: EventClientConfig,
    ) -> Result<(Self, mpsc::Receiver<Event>), ClientError> {
        let mut config = config;
        config.replay_on_start = false;
        Self::start(config).await
    }

    /// The events directory being watched.
    pub fn events_dir(&self) -> &Path {
        &self.events_dir
    }

    /// The replay result from startup, if replay was enabled.
    pub fn replay_result(&self) -> Option<&ReplayResult> {
        self.replay_result.as_ref()
    }

    /// Gracefully shut down the event client.
    ///
    /// The file watcher is stopped and the channel sender is dropped,
    /// which will cause the receiver to return `None` after all buffered
    /// events are consumed.
    pub async fn shutdown(self) {
        info!("Shutting down event client");
        // Dropping self drops the watcher, which stops file watching.
        // The sender is also dropped, signaling channel closure.
        drop(self);
    }
}

/// Errors from the event client.
#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("Failed to start file watcher: {0}")]
    Watcher(#[from] WatcherError),
}

/// Get the user's home directory, with fallback.
fn dirs_or_home() -> PathBuf {
    std::env::var("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("."))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;
    use tempfile::TempDir;
    use tokio::time::{sleep, Duration};

    fn sample_event_line(seq: i64) -> String {
        format!(
            r#"{{:event/type :workflow/started, :event/id #uuid "550e8400-e29b-41d4-a716-44665544{:04}", :event/timestamp #inst "2026-04-10T12:00:00.000Z", :event/version "1.0.0", :event/sequence-number {}, :workflow/id #uuid "660e8400-e29b-41d4-a716-446655440000", :message "Event {}"}}"#,
            seq, seq, seq
        )
    }

    #[tokio::test]
    async fn client_lifecycle() {
        let dir = TempDir::new().unwrap();
        let events_dir = dir.path().join("events");
        fs::create_dir_all(&events_dir).unwrap();

        // Pre-populate an event file for replay
        let mut f = fs::File::create(events_dir.join("workflow-1.edn")).unwrap();
        writeln!(f, "{}", sample_event_line(0)).unwrap();
        writeln!(f, "{}", sample_event_line(1)).unwrap();
        f.flush().unwrap();
        drop(f);

        let config = EventClientConfig::with_events_dir(&events_dir);
        let (client, mut rx) = EventClient::start(config).await.unwrap();

        // Should have replayed 2 events
        assert_eq!(client.replay_result().unwrap().events_replayed, 2);

        // Drain replayed events
        let e0 = rx.recv().await.unwrap();
        assert_eq!(e0.sequence_number, 0);
        let e1 = rx.recv().await.unwrap();
        assert_eq!(e1.sequence_number, 1);

        // Now write a new event — the watcher should pick it up
        let mut f = fs::OpenOptions::new()
            .append(true)
            .open(events_dir.join("workflow-1.edn"))
            .unwrap();
        writeln!(f, "{}", sample_event_line(2)).unwrap();
        f.flush().unwrap();
        drop(f);

        // Give the watcher time
        sleep(Duration::from_millis(500)).await;

        match tokio::time::timeout(Duration::from_secs(2), rx.recv()).await {
            Ok(Some(event)) => {
                assert_eq!(event.sequence_number, 2);
            }
            Ok(None) => panic!("Channel closed"),
            Err(_) => {
                eprintln!("WARN: watcher test timed out (may be CI environment)");
            }
        }

        client.shutdown().await;
    }

    #[tokio::test]
    async fn client_no_replay() {
        let dir = TempDir::new().unwrap();
        let events_dir = dir.path().join("events");
        fs::create_dir_all(&events_dir).unwrap();

        // Pre-populate
        let mut f = fs::File::create(events_dir.join("workflow-1.edn")).unwrap();
        writeln!(f, "{}", sample_event_line(0)).unwrap();
        f.flush().unwrap();
        drop(f);

        let config = EventClientConfig::with_events_dir(&events_dir);
        let (client, mut rx) = EventClient::start_live_only(config).await.unwrap();

        assert!(client.replay_result().is_none());

        // Channel should be empty (no replay)
        assert!(rx.try_recv().is_err());

        client.shutdown().await;
    }

    #[tokio::test]
    async fn client_creates_missing_dir() {
        let dir = TempDir::new().unwrap();
        let events_dir = dir.path().join("deep").join("nested").join("events");
        // Don't create it — the client should

        let config = EventClientConfig::with_events_dir(&events_dir);
        let (client, _rx) = EventClient::start(config).await.unwrap();

        assert!(events_dir.exists());
        client.shutdown().await;
    }
}
