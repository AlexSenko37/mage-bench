"""Turn the draft's per-call record into a reviewable pick-by-pick history.

LlmDraftPlayer writes one JSON line per OpenRouter call to draft_picks.jsonl (see
draft_match.DRAFT_LOG_NAME, copied into the game directory once the game exists). This
module reshapes that into the structure the website's draft replay consumes: an ordered
list of picks, each carrying the pack exactly as the model saw it, the card taken, and
the reasoning that connects them.

The interesting part is threading. A booster is passed around the pod, so the same
physical pack comes back to a seat several picks later minus what the table took -- that
return trip is the "wheel", and reading it is most of what draft skill consists of. XMage
gives a booster no identity, so packs are threaded here instead, using the card instance
ids LlmDraftPlayer records: ids are globally unique and travel with the physical pack, so
two observations of one booster always share ids and two different boosters never do.
"""

import json
from pathlib import Path
from typing import Any

from magebench.common.log import get_logger

logger = get_logger(__name__)

DRAFT_LOG_NAME = "draft_picks.jsonl"

# Stages that represent a booster pick; everything else is deckbuilding.
_PICK_STAGE = "pick"
_FALLBACK_STAGE = "pick_fallback"

# A pick is only replayable if it carries the pack it was made from. Drafts recorded
# before the pack was captured have picks without one, and a replay built from those
# would render an empty pack next to a confident-looking reasoning trace.
_REPLAYABLE_PICK_KEYS = ("seat", "pack", "pack_ids", "picked", "pool", "reasoning")


def _load(path: Path) -> list[dict[str, Any]]:
    """Parse the JSONL record, skipping (and counting) any line that will not parse."""
    records: list[dict[str, Any]] = []
    malformed = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            malformed += 1
    if malformed:
        logger.warning("Skipped %d unparseable line(s) in %s", malformed, path)
    return records


def _call_cost(record: dict[str, Any]) -> float:
    """Cost of one call in USD, as OpenRouter reported it.

    A pick_fallback record has no usage block because no call was billed -- the heuristic
    made that pick locally.
    """
    if "usage" not in record:
        return 0.0
    return float(record["usage"].get("cost", 0.0))


def _replayable_picks(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    picks = []
    incomplete = 0
    for record in records:
        if record.get("stage") != _PICK_STAGE:
            continue
        if all(key in record for key in _REPLAYABLE_PICK_KEYS):
            picks.append(record)
        else:
            incomplete += 1
    if incomplete:
        logger.warning(
            "%d pick record(s) predate pack capture and cannot be replayed; the draft replay will be incomplete",
            incomplete,
        )
    return picks


def _thread_packs(picks: list[dict[str, Any]]) -> dict[int, str]:
    """Assign a pack label to each pick, grouping observations of the same booster.

    Union-find over "shares at least one card instance id". Two observations of one pack
    always overlap (a pack loses one card per seat per pass, and packs are larger than the
    number of seats between visits), and two different packs never do.
    """
    parent: dict[int, int] = {i: i for i in range(len(picks))}

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    owner: dict[str, int] = {}
    for idx, pick in enumerate(picks):
        for card_id in pick["pack_ids"]:
            if card_id in owner:
                union(owner[card_id], idx)
            else:
                owner[card_id] = idx

    # Label groups in first-appearance order so pack names are stable and readable.
    labels: dict[int, str] = {}
    out: dict[int, str] = {}
    for idx in range(len(picks)):
        root = find(idx)
        if root not in labels:
            labels[root] = f"pack{len(labels) + 1}"
        out[idx] = labels[root]
    return out


def _round_numbers(picks: list[dict[str, Any]]) -> dict[int, int]:
    """Which booster round each pick belongs to, per seat.

    A round boundary is where a seat's pack size jumps back up: packs shrink by one card
    per pass, so an increase means a fresh booster was opened.
    """
    rounds: dict[int, int] = {}
    state: dict[str, tuple[int, int]] = {}  # seat -> (round, previous pack size)
    for idx, pick in enumerate(picks):
        seat = pick["seat"]
        size = len(pick["pack"])
        if seat in state:
            current_round, previous_size = state[seat]
            if size > previous_size:
                current_round += 1
            state[seat] = (current_round, size)
        else:
            state[seat] = (1, size)
        rounds[idx] = state[seat][0]
    return rounds


def _seat_totals(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seats: dict[str, dict[str, Any]] = {}
    for record in records:
        seat = record["seat"]
        row = seats.setdefault(
            seat,
            {"seat": seat, "model": None, "picks": 0, "fallbacks": 0, "cost_usd": 0.0},
        )
        if record.get("stage") == _FALLBACK_STAGE:
            row["fallbacks"] += 1
            continue
        if "model" in record:
            row["model"] = record["model"]
        if record.get("stage") == _PICK_STAGE:
            row["picks"] += 1
        row["cost_usd"] += _call_cost(record)
    return sorted(seats.values(), key=lambda s: s["seat"])


def _representative_prompts(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """One example of each distinct prompt, rather than one per call.

    Every pick shares a prompt that differs only in the pool and the pack, both of which
    the replay already renders as cards. Attaching the full text to all 84 picks would
    bury the model's answer under a wall of identical instructions and add megabytes to
    the export, so each stage contributes a single example.

    For picks the example is the median pool size: the first pick shows an empty pool
    section, which is the one case that does not illustrate what the model usually sees.
    """
    by_stage: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        if "prompt" not in record:
            continue
        by_stage.setdefault(record["stage"], []).append(record)

    prompts = []
    for stage, calls in by_stage.items():
        if stage == _PICK_STAGE:
            # A pick record from before pack capture has no pool to rank on; ranking only
            # the ones that do keeps the median meaningful instead of treating a missing
            # pool as an empty one.
            rankable = [call for call in calls if "pool" in call]
            ranked = sorted(rankable, key=lambda r: len(r["pool"])) if rankable else calls
            example = ranked[len(ranked) // 2]
        else:
            example = calls[0]
        prompts.append(
            {
                "stage": stage,
                "seat": example["seat"],
                # Written by the same code path that sends the request, so a record with a
                # prompt always has the system message alongside it.
                "system": example["system"],
                "user": example["prompt"],
                "calls": len(calls),
            }
        )
    prompts.sort(key=lambda p: p["stage"])
    return prompts


def build_draft(game_dir: Path) -> dict[str, Any] | None:
    """Build the draft section, or None when the game has no replayable draft record.

    None for constructed games, for anything drafted before the record existed, and for a
    record whose picks all predate pack capture -- callers treat it as "no draft to
    replay" rather than as an empty draft.
    """
    path = game_dir / DRAFT_LOG_NAME
    if not path.exists():
        return None
    records = _load(path)
    if not records:
        return None

    picks = _replayable_picks(records)
    if not picks:
        return None

    pack_labels = _thread_packs(picks)
    rounds = _round_numbers(picks)

    # Card ids this seat has already seen in this pack, for the wheel.
    seen_before: dict[tuple[str, str], set[str]] = {}
    pick_counter: dict[str, int] = {}

    out_picks: list[dict[str, Any]] = []
    for idx, pick in enumerate(picks):
        seat = pick["seat"]
        pack = list(pick["pack"])
        pack_ids = list(pick["pack_ids"])
        picked = pick["picked"]
        label = pack_labels[idx]

        pick_counter[seat] = pick_counter.get(seat, 0) + 1

        key = (seat, label)
        wheeled: list[str] = []
        if key in seen_before:
            previous = seen_before[key]
            wheeled = [name for name, cid in zip(pack, pack_ids, strict=True) if cid in previous]
        seen_before[key] = set(pack_ids)

        out_picks.append(
            {
                "seat": seat,
                "pick_number": pick_counter[seat],
                "round": rounds[idx],
                "pack": label,
                "pack_cards": pack,
                "picked": picked,
                "picked_index": pack.index(picked) if picked in pack else None,
                "wheeled": wheeled,
                "pool_size": len(pick["pool"]),
                "reasoning": pick["reasoning"],
                "elapsed_secs": pick.get("elapsed_secs"),
                "cost_usd": _call_cost(pick),
                # Absent on records from before per-call provider recording.
                "provider": pick.get("provider"),
            }
        )

    deckbuild = [
        {
            "seat": record["seat"],
            "stage": record["stage"],
            # Both are always written by LlmDraftPlayer, so a missing key is a corrupt
            # record and should fail loudly rather than render as a blank panel.
            "content": record["content"],
            "reasoning": record["reasoning"],
            "cost_usd": _call_cost(record),
        }
        for record in records
        if record.get("stage") not in (_PICK_STAGE, _FALLBACK_STAGE)
    ]

    return {
        "seats": _seat_totals(records),
        "picks": out_picks,
        "deckbuild": deckbuild,
        "prompts": _representative_prompts(records),
    }
