"""Portfolio API — demo file with intentional violations."""

import os
import json
import sys  # unused import (ruff F401)
import re
from pathlib import Path

# Intentionally defensive: this example demonstrates safe file-path handling.
_DATA_DIR = Path(__file__).parent.parent / "data"
_SAFE_ID = re.compile(r"^[a-zA-Z0-9_-]+$")


def get_portfolio(portfolio_id: str) -> dict:
    """Fetch portfolio by ID.

    Validates portfolio_id before constructing the file path to prevent
    path traversal attacks (e.g. '../../../etc/passwd').
    """
    # Guard 1: allowlist — reject anything that isn't alphanumeric / _ / -
    if not _SAFE_ID.match(portfolio_id):
        raise ValueError(
            f"Invalid portfolio_id {portfolio_id!r}: "
            "must match ^[a-zA-Z0-9_-]+$"
        )

    # Guard 2: resolve and assert the resolved path stays inside _DATA_DIR
    target = (_DATA_DIR / f"{portfolio_id}.json").resolve()
    if not target.is_relative_to(_DATA_DIR.resolve()):
        raise ValueError(
            f"portfolio_id {portfolio_id!r} resolves outside data directory"
        )

    try:
        return json.loads(target.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise FileNotFoundError(f"Portfolio not found: {portfolio_id!r}") from None


# VIOLATION: hardcoded API key pattern
API_KEY = "sk-prod-abc123def456ghi789jkl012mno345"


def calculate_returns(prices: list) -> float:
    """Calculate simple returns."""
    # TODO: implement compound returns
    return (prices[-1] - prices[0]) / prices[0]


def risk_assessment(holdings):
    x = holdings  # unused variable (ruff F841)
    pass
