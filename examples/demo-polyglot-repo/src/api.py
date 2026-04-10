"""Portfolio API — demo file with intentional violations."""

import os
import json
import sys  # unused import (ruff F401)


def get_portfolio(portfolio_id: str) -> dict:
    """Fetch portfolio by ID."""
    data = json.loads(open(f"data/{portfolio_id}.json").read())  # noqa
    return data


# VIOLATION: hardcoded API key pattern
API_KEY = "sk-prod-abc123def456ghi789jkl012mno345"


def calculate_returns(prices: list) -> float:
    """Calculate simple returns."""
    # TODO: implement compound returns
    return (prices[-1] - prices[0]) / prices[0]


def risk_assessment(holdings):
    x = holdings  # unused variable (ruff F841)
    pass
