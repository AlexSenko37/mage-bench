"""Tests for attaching the draft record to the game it produced.

This is the one step in the draft->replay chain that only runs after a real game, so it
is covered here rather than by paying for a full draft-and-play run to exercise one
file copy.
"""

import json
from pathlib import Path

from magebench.cli.draft_match import DRAFT_LOG_NAME, attach_draft_record
from magebench.game.export_draft import build_draft


def test_record_is_copied_into_the_game_dir(tmp_path: Path):
    draft_dir = tmp_path / "draft"
    game_dir = tmp_path / "game"
    draft_dir.mkdir()
    game_dir.mkdir()
    (draft_dir / DRAFT_LOG_NAME).write_text('{"seat":"a"}\n', encoding="utf-8")

    assert attach_draft_record(draft_dir, game_dir) is True
    assert (game_dir / DRAFT_LOG_NAME).read_text(encoding="utf-8") == '{"seat":"a"}\n'


def test_missing_record_is_reported_not_swallowed(tmp_path: Path):
    """A draft that recorded nothing must not look like a game that simply had no draft."""
    draft_dir = tmp_path / "draft"
    game_dir = tmp_path / "game"
    draft_dir.mkdir()
    game_dir.mkdir()

    assert attach_draft_record(draft_dir, game_dir) is False
    assert not (game_dir / DRAFT_LOG_NAME).exists()


def test_attached_record_is_where_the_exporter_looks(tmp_path: Path):
    """The copy target and build_draft's source must agree on the filename.

    They are two constants in two modules; if they drift, the draft tab silently stops
    appearing on published games and nothing fails loudly.
    """
    draft_dir = tmp_path / "draft"
    game_dir = tmp_path / "game"
    draft_dir.mkdir()
    game_dir.mkdir()
    record = {
        "seat": "a",
        "stage": "pick",
        "model": "m/x",
        "usage": {"cost": 0.01},
        "reasoning": "",
        "content": "1",
        "pack": ["Alpha", "Beta"],
        "pack_ids": ["i1", "i2"],
        "pool": [],
        "picked": "Alpha",
    }
    (draft_dir / DRAFT_LOG_NAME).write_text(json.dumps(record) + "\n", encoding="utf-8")

    assert attach_draft_record(draft_dir, game_dir) is True
    draft = build_draft(game_dir)
    assert draft is not None
    assert draft["picks"][0]["picked"] == "Alpha"


def test_record_name_does_not_collide_with_pilot_event_logs():
    """The game dir is scanned with glob("*_llm.jsonl") for pilot event logs.

    Naming the draft record draft_llm.jsonl made read_llm_events parse it as a pilot log
    and die on the missing "type" key, which is how this constant got its current value.
    """
    assert not DRAFT_LOG_NAME.endswith("_llm.jsonl")
