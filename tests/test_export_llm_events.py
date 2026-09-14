"""Tests for export_llm_events: raw *_llm.jsonl events -> exported game-log shape."""

import json
import tempfile
from pathlib import Path

from magebench.game.export_llm_events import read_llm_events


def _write_llm_jsonl(game_dir: Path, player: str, lines: list[dict]) -> None:
    path = game_dir / f"{player}_llm.jsonl"
    path.write_text("\n".join(json.dumps(line) for line in lines) + "\n")


def test_read_llm_events_includes_action_summary():
    """An action_summary raw event should round-trip through read_llm_events with its
    summary/turn/action_taken fields intact, so the replay viewer can render it."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _write_llm_jsonl(
            game_dir,
            "LLM",
            [
                {
                    "type": "action_summary",
                    "ts": "2026-08-19T10:00:00",
                    "seq": 1,
                    "player": "LLM",
                    "turn": 3,
                    "action_taken": "played_forest",
                    "summary": "Opponent attacked with a Bear; I played a Forest and passed.",
                    "game_seq": 7,
                },
            ],
        )

        events, _costs, _tools, _tool_calls, _thinking = read_llm_events(game_dir)

        assert len(events) == 1
        event = events[0]
        assert event["type"] == "action_summary"
        assert event["player"] == "LLM"
        assert event["turn"] == 3
        assert event["action_taken"] == "played_forest"
        assert event["summary"] == "Opponent attacked with a Bear; I played a Forest and passed."
        assert event["game_seq"] == 7


def test_read_llm_events_action_summary_omits_missing_optional_fields():
    """turn/action_taken are optional on action_summary — absence shouldn't crash or
    fabricate values."""
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _write_llm_jsonl(
            game_dir,
            "LLM",
            [
                {
                    "type": "action_summary",
                    "ts": "2026-08-19T10:00:00",
                    "seq": 1,
                    "player": "LLM",
                    "summary": "Kept my hand.",
                },
            ],
        )

        events, *_ = read_llm_events(game_dir)

        assert len(events) == 1
        event = events[0]
        assert event["summary"] == "Kept my hand."
        assert "turn" not in event
        assert "action_taken" not in event


def test_read_llm_events_keeps_the_serving_provider():
    """The host OpenRouter routed a call to must survive export.

    The pilot records it on every llm_response, and LlmEvent declares it, but the copy
    step never carried it across, so every exported game had provider silently dropped.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        game_dir = Path(tmpdir)
        _write_llm_jsonl(
            game_dir,
            "LLM",
            [
                {
                    "type": "llm_response",
                    "ts": "2026-09-14T10:00:00",
                    "seq": 1,
                    "player": "LLM",
                    "reasoning": "pass",
                    "usage": {"prompt_tokens": 10, "completion_tokens": 2},
                    "cost_usd": 0.001,
                    "provider": "StreamLake",
                },
                {
                    "type": "llm_response",
                    "ts": "2026-09-14T10:00:01",
                    "seq": 2,
                    "player": "LLM",
                    "reasoning": "pass",
                    "usage": {"prompt_tokens": 10, "completion_tokens": 2},
                    "cost_usd": 0.001,
                },
            ],
        )

        events, _costs, _tools, _tool_calls, _thinking = read_llm_events(game_dir)

        assert [e.get("provider") for e in events] == ["StreamLake", None]
        assert "provider" not in events[1], "a call with no recorded provider exports no key"
