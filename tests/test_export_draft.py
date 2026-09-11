"""Tests for the draft replay export, especially pack threading.

The threading is the part worth testing: it reconstructs booster identity that XMage never
recorded, from card instance ids alone, and a wheel that is off by one pass is worse than
no wheel at all because it looks plausible.
"""

import json
from pathlib import Path

from magebench.game.export_draft import build_draft

POD = 8
PACK_SIZE = 14
ROUNDS = 3
LLM_SEATS = ("modelA-A", "modelB-B")


def _simulate_pod(tmp_path: Path, rounds: int = ROUNDS) -> Path:
    """Write a draft_picks.jsonl for a faithful 8-seat pod.

    Each seat opens a booster, takes a card, and passes; a pack therefore returns to the
    same seat every POD picks with POD fewer cards. Only the two LLM seats are logged,
    exactly as the real harness does — the heuristic filler bots make no LLM calls.
    """
    lines = []
    for rnd in range(rounds):
        # packs[p] is the physical booster opened by seat p this round
        packs = [[{"name": f"r{rnd}p{p}c{c}", "id": f"{rnd}-{p}-{c}"} for c in range(PACK_SIZE)] for p in range(POD)]
        for pick in range(PACK_SIZE):
            for seat in range(POD):
                # Passing left: on pick k, seat s holds the pack opened by (s - k) mod POD
                pack = packs[(seat - pick) % POD]
                taken = pack[0]
                if seat < len(LLM_SEATS):
                    lines.append(
                        {
                            "ts": f"2026-09-10T12:00:{len(lines) % 60:02d}Z",
                            "seat": LLM_SEATS[seat],
                            "stage": "pick",
                            "model": f"vendor/model-{seat}",
                            "elapsed_secs": 1.0,
                            "usage": {"cost": 0.01},
                            "reasoning": f"thinking about {taken['name']}",
                            "content": "1",
                            "pack": [c["name"] for c in pack],
                            "pack_ids": [c["id"] for c in pack],
                            "pool": [],
                            "picked": taken["name"],
                            "picked_id": taken["id"],
                        }
                    )
                pack.remove(taken)

    path = tmp_path / "draft_picks.jsonl"
    path.write_text("\n".join(json.dumps(row) for row in lines) + "\n", encoding="utf-8")
    return tmp_path


def test_missing_log_returns_none(tmp_path: Path):
    assert build_draft(tmp_path) is None


def test_empty_log_returns_none(tmp_path: Path):
    (tmp_path / "draft_picks.jsonl").write_text("", encoding="utf-8")
    assert build_draft(tmp_path) is None


def test_pick_counts_and_seats(tmp_path: Path):
    draft = build_draft(_simulate_pod(tmp_path))
    assert draft is not None
    assert len(draft["picks"]) == len(LLM_SEATS) * PACK_SIZE * ROUNDS
    assert [s["seat"] for s in draft["seats"]] == sorted(LLM_SEATS)
    for seat in draft["seats"]:
        assert seat["picks"] == PACK_SIZE * ROUNDS
        assert seat["fallbacks"] == 0


def test_each_physical_pack_is_one_thread(tmp_path: Path):
    """POD packs per round, each seen by both LLM seats, must group into POD*ROUNDS packs."""
    draft = build_draft(_simulate_pod(tmp_path))
    labels = {p["pack"] for p in draft["picks"]}
    assert len(labels) == POD * ROUNDS


def test_a_pack_is_never_threaded_across_rounds(tmp_path: Path):
    """Card ids are unique per round here, so no label may span two rounds."""
    draft = build_draft(_simulate_pod(tmp_path))
    rounds_by_label: dict[str, set[int]] = {}
    for pick in draft["picks"]:
        rounds_by_label.setdefault(pick["pack"], set()).add(pick["round"])
    assert all(len(rs) == 1 for rs in rounds_by_label.values())


def test_round_numbers_are_one_to_three(tmp_path: Path):
    draft = build_draft(_simulate_pod(tmp_path))
    for seat in LLM_SEATS:
        picks = [p for p in draft["picks"] if p["seat"] == seat]
        assert [p["round"] for p in picks] == sorted(p["round"] for p in picks)
        assert {p["round"] for p in picks} == {1, 2, 3}
        for rnd in (1, 2, 3):
            assert len([p for p in picks if p["round"] == rnd]) == PACK_SIZE


def test_first_pass_has_no_wheel(tmp_path: Path):
    """Nothing can have wheeled before a pack has come back round."""
    draft = build_draft(_simulate_pod(tmp_path))
    for seat in LLM_SEATS:
        first_pass = [p for p in draft["picks"] if p["seat"] == seat][:POD]
        assert all(p["wheeled"] == [] for p in first_pass)


def test_wheel_appears_exactly_one_lap_later(tmp_path: Path):
    """A seat's pick POD later is the same pack, and every card still in it wheeled."""
    draft = build_draft(_simulate_pod(tmp_path))
    picks = [p for p in draft["picks"] if p["seat"] == LLM_SEATS[0]]
    first, wheeled_back = picks[0], picks[POD]
    assert wheeled_back["pack"] == first["pack"]
    assert len(wheeled_back["pack_cards"]) == PACK_SIZE - POD
    # The table took POD cards; whatever is left was seen and passed by this seat before.
    assert set(wheeled_back["wheeled"]) == set(wheeled_back["pack_cards"])
    assert first["picked"] not in wheeled_back["pack_cards"]


def test_pack_shrinks_by_one_per_seat_between_visits(tmp_path: Path):
    draft = build_draft(_simulate_pod(tmp_path))
    picks = [p for p in draft["picks"] if p["seat"] == LLM_SEATS[0]]
    assert [len(p["pack_cards"]) for p in picks[:PACK_SIZE]] == list(range(PACK_SIZE, 0, -1))


def test_picked_index_points_at_the_taken_card(tmp_path: Path):
    draft = build_draft(_simulate_pod(tmp_path))
    for pick in draft["picks"]:
        assert pick["pack_cards"][pick["picked_index"]] == pick["picked"]


def test_fallbacks_are_counted_but_not_picks(tmp_path: Path):
    """A heuristic fallback made the pick, so it must not be reported as a model pick."""
    path = _simulate_pod(tmp_path) / "draft_picks.jsonl"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(
            json.dumps({"ts": "z", "seat": LLM_SEATS[0], "stage": "pick_fallback", "detail": "timeout"}) + "\n"
        )
    draft = build_draft(tmp_path)
    seat = next(s for s in draft["seats"] if s["seat"] == LLM_SEATS[0])
    assert seat["fallbacks"] == 1
    assert seat["picks"] == PACK_SIZE * ROUNDS


def test_deckbuild_rows_are_separated_from_picks(tmp_path: Path):
    path = _simulate_pod(tmp_path) / "draft_picks.jsonl"
    with path.open("a", encoding="utf-8") as handle:
        for stage in ("spells", "lands"):
            handle.write(
                json.dumps(
                    {
                        "ts": "z",
                        "seat": LLM_SEATS[0],
                        "stage": stage,
                        "model": "vendor/model-0",
                        "usage": {"cost": 0.25},
                        "content": "{}",
                        "reasoning": "why",
                    }
                )
                + "\n"
            )
    draft = build_draft(tmp_path)
    assert [d["stage"] for d in draft["deckbuild"]] == ["spells", "lands"]
    assert all(p["seat"] in LLM_SEATS for p in draft["picks"])
    seat = next(s for s in draft["seats"] if s["seat"] == LLM_SEATS[0])
    # 42 picks at $0.01 plus two deckbuild calls at $0.25
    assert round(seat["cost_usd"], 4) == round(PACK_SIZE * ROUNDS * 0.01 + 0.5, 4)


def test_malformed_lines_are_skipped(tmp_path: Path):
    path = _simulate_pod(tmp_path) / "draft_picks.jsonl"
    with path.open("a", encoding="utf-8") as handle:
        handle.write("{not json\n\n")
    draft = build_draft(tmp_path)
    assert len(draft["picks"]) == len(LLM_SEATS) * PACK_SIZE * ROUNDS
