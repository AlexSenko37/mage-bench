#!/usr/bin/env python3
"""Run repeated draft-then-play matches between two named model presets.

For each of N games: both presets draft a fresh 40-card deck from a real booster set
(an all-bot XMage tournament, headless — see orchestration/game_processes.py's
start_draft_client/wait_for_draft_completion and TablesPanel.
createConfiguredAiPuppeteerTournament() on the Java side), then play exactly one game
against each other with the resulting decks, through the normal pilot/orchestrator
machinery unchanged. Prints a final win/cost summary.

Usage:
    python -m magebench.cli.draft_match --preset-a dsv4pro-low --preset-b gpt56terra-medium --set TLA --games 10
"""

import argparse
import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from magebench.common.log import get_logger, setup_logging
from magebench.common.port import find_available_port, wait_for_port
from magebench.common.process_manager import ProcessManager, jvm_oom_preexec_fn
from magebench.game.export_game import read_game_winner
from magebench.orchestration.config import Config, load_presets
from magebench.orchestration.game_processes import (
    draft_seat_jvm_args,
    start_draft_client,
    start_server,
    wait_for_draft_completion,
)
from magebench.orchestration.orchestrator import (
    clean_stale_h2_locks,
    compile_project,
    run_orchestrator,
)
from magebench.orchestration.xml_config import modify_server_config

logger = get_logger(__name__)

_ROOT = Path(__file__).resolve().parents[3]
_LOGS_DIR = Path.home() / ".mage-bench" / "logs"
_DRAFT_BOT_MODULE = "Mage.Server.Plugins/Mage.Player.AI.DraftBot"

# The play phase's pilot bridge logs in as a real XMage "user" (unlike the draft phase's
# LlmDraftPlayer seats, which are Player objects the tournament instantiates directly and
# never go through session login at all) — and the server enforces a 3-14 character
# username length. Draft seat names (f"{preset}-A"/"{preset}-B", used for per-seat JVM
# properties and drafted-deck filenames) have no such bound and can easily run past 14
# chars, so the play phase gets its own fixed, always-in-range login names instead of
# reusing them. Confirmed by hitting both ends of this range: a bare "A"/"B" (too short)
# and "gpt56luna-low-B" (16 chars, too long) both got silently rejected at login with no
# server-side log line at all — just the bridge's own "Logging: FAIL" after a fixed timeout.
_PILOT_A_NAME = "PilotA"
_PILOT_B_NAME = "PilotB"


def _compile_draft_bot(project_root: Path) -> bool:
    """Compile+install the LlmDraftPlayer module.

    compile_project() doesn't cover this module (Mage.Server doesn't declare a Maven
    build-time dependency on it — it's only loaded via config.xml's runtime classloading),
    so it would otherwise silently run a stale jar.
    """
    result = subprocess.run(
        ["mvn", "-q", "-DskipTests", "-pl", _DRAFT_BOT_MODULE, "-am", "install"],
        cwd=project_root,
        preexec_fn=jvm_oom_preexec_fn(),
    )
    return result.returncode == 0


def _effort_for_preset(preset_name: str) -> str | None:
    """Reasoning effort configured for a preset, or None to use the provider default."""
    presets = load_presets(None)["presets"]
    pdata = presets.get(preset_name)
    if pdata is None:
        raise ValueError(f"Unknown preset: {preset_name!r}")
    effort = pdata.get("reasoning_effort")
    return str(effort) if effort else None


def _model_for_preset(preset_name: str) -> str:
    presets = load_presets(None)["presets"]
    pdata = presets.get(preset_name)
    if pdata is None:
        raise ValueError(f"Unknown preset: {preset_name!r}. Available: {sorted(presets.keys())}")
    model = pdata.get("model")
    assert model, f"Preset {preset_name!r} has no model configured"
    return str(model)


def _timestamp() -> str:
    return datetime.now(ZoneInfo("America/Los_Angeles")).strftime("%Y%m%d_%H%M%S")


@dataclass(frozen=True)
class DraftResult:
    """One completed draft: the two decks, and where to find its record."""

    deck_a: Path
    deck_b: Path
    seat_a_name: str
    seat_b_name: str
    cost_usd: float
    draft_dir: Path


DRAFT_LOG_NAME = "draft_picks.jsonl"


def _read_draft_calls(draft_dir: Path) -> list[dict]:
    """Parse the per-call records LlmDraftPlayer wrote during the draft.

    Missing file means the draft ran without -Dxmage.llmDraft.logDir, or fell back to the
    heuristic for every pick before any call was made — both are worth surfacing rather than
    reporting a confident $0.
    """
    path = draft_dir / DRAFT_LOG_NAME
    if not path.exists():
        return []
    calls = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            calls.append(json.loads(line))
        except json.JSONDecodeError:
            logger.warning("Unparseable line in %s, skipping", path)
    return calls


def summarize_draft_cost(draft_dir: Path) -> tuple[float, dict[str, dict]]:
    """Total draft spend in USD, plus a per-seat breakdown.

    Cost comes from OpenRouter's own usage.cost on each response (requested with
    usage.include), not from a local rate table — the rate tables in puppeteer/models.json
    are hand-maintained and have already drifted once.
    """
    per_seat: dict[str, dict] = {}
    total = 0.0
    for call in _read_draft_calls(draft_dir):
        seat = call.get("seat", "?")
        stage = call.get("stage", "?")
        row = per_seat.setdefault(
            seat,
            {"calls": 0, "cost": 0.0, "prompt": 0, "completion": 0, "reasoning": 0,
             "fallbacks": 0, "models": set(), "stages": {}},
        )
        if stage == "pick_fallback":
            row["fallbacks"] += 1
            continue
        row["calls"] += 1
        row["stages"][stage] = row["stages"].get(stage, 0) + 1
        if call.get("model"):
            row["models"].add(call["model"])
        # A pick_fallback record carries no usage: the heuristic made that pick locally,
        # so nothing was billed and there are no tokens to attribute.
        if "usage" in call:
            usage = call["usage"]
            cost = float(usage.get("cost", 0.0))
            row["cost"] += cost
            total += cost
            row["prompt"] += int(usage.get("prompt_tokens", 0))
            row["completion"] += int(usage.get("completion_tokens", 0))
            if "completion_tokens_details" in usage:
                row["reasoning"] += int(usage["completion_tokens_details"].get("reasoning_tokens", 0))
    return total, per_seat


def _report_draft_cost(draft_dir: Path, seat_a_name: str, seat_b_name: str) -> float:
    """Log the draft's own spend and return it. Returns 0.0 with a warning if unrecorded."""
    total, per_seat = summarize_draft_cost(draft_dir)
    if not per_seat:
        logger.warning(
            "No draft LLM calls recorded in %s — the draft cost is unknown, not zero", draft_dir
        )
        return 0.0
    for seat in (seat_a_name, seat_b_name):
        row = per_seat.get(seat)
        if row is None:
            logger.warning("Draft seat %s made no LLM calls at all", seat)
            continue
        models = ", ".join(sorted(row["models"])) or "?"
        logger.info(
            "Draft %s: $%.4f over %d calls (%s) — %d prompt / %d completion tok "
            "(%d reasoning), %d heuristic fallbacks",
            seat, row["cost"], row["calls"], models,
            row["prompt"], row["completion"], row["reasoning"], row["fallbacks"],
        )
        if row["fallbacks"]:
            logger.warning(
                "Draft %s fell back to the RateCard heuristic on %d pick(s) — those picks "
                "were not made by the model", seat, row["fallbacks"],
            )
    logger.info("Draft total: $%.4f", total)
    return total


def run_draft(
    preset_a: str,
    preset_b: str,
    set_code: str,
    packs_per_player: int,
    project_root: Path,
    filler_bots: int = 6,
    draft_timeout: int = 3600,
) -> DraftResult:
    """Run one headless all-bot draft tournament."""
    draft_dir = _LOGS_DIR / f"draft_{_timestamp()}"
    draft_dir.mkdir(parents=True, exist_ok=True)

    config = Config()
    pm = ProcessManager()
    try:
        port_reservation = find_available_port(config.start_port)
        config.port = port_reservation.port
        server_config_path = draft_dir / "server_config.xml"
        modify_server_config(
            source=project_root / "Mage.Server" / "config" / "config.xml",
            destination=server_config_path,
            port=config.port,
        )
        seat_a_name = f"{preset_a}-A"
        seat_b_name = f"{preset_b}-B"
        model_a = _model_for_preset(preset_a)
        model_b = _model_for_preset(preset_b)

        server_log = draft_dir / "server.log"
        logger.info("Starting draft server on port %d...", config.port)
        start_server(
            pm,
            project_root,
            config,
            server_config_path,
            server_log,
            extra_jvm_args=draft_seat_jvm_args(
                seat_a_name=seat_a_name,
                seat_a_model=model_a,
                seat_b_name=seat_b_name,
                seat_b_model=model_b,
                log_dir=draft_dir,
                seat_a_effort=_effort_for_preset(preset_a),
                seat_b_effort=_effort_for_preset(preset_b),
            ),
        )
        if not wait_for_port(config.server, config.port, config.server_wait):
            raise RuntimeError(f"Draft server failed to start within {config.server_wait}s — check {server_log}")
        port_reservation.release()

        client_log = draft_dir / "client.log"
        since = time.time()
        logger.info(
            "Starting draft: %s (%s) vs %s (%s) from set %s (%d packs each)...",
            seat_a_name,
            model_a,
            seat_b_name,
            model_b,
            set_code,
            packs_per_player,
        )
        proc = start_draft_client(
            pm,
            project_root,
            config,
            seat_a_name=seat_a_name,
            seat_b_name=seat_b_name,
            set_code=set_code,
            log_path=client_log,
            packs_per_player=packs_per_player,
            filler_bots=filler_bots,
        )
        deck_a, deck_b = wait_for_draft_completion(
            project_root, seat_a_name, seat_b_name, since, proc, timeout=draft_timeout
        )
        logger.info("Draft complete: %s, %s", deck_a.name, deck_b.name)
        draft_cost = _report_draft_cost(draft_dir, seat_a_name, seat_b_name)
        return DraftResult(
            deck_a=deck_a,
            deck_b=deck_b,
            seat_a_name=seat_a_name,
            seat_b_name=seat_b_name,
            cost_usd=draft_cost,
            draft_dir=draft_dir,
        )
    finally:
        pm.cleanup()


def build_game_config(
    preset_a: str,
    preset_b: str,
    deck_a: Path,
    deck_b: Path,
    project_root: Path,
    index: int,
) -> Path:
    """Build a game config JSON for one draft-match game. Mirrors the shape every other
    configs/*.json file in this repo uses — see cli/tournament_game.py's
    build_game_config() for the reference this was modeled on.

    Pilot names are the fixed _PILOT_A_NAME/_PILOT_B_NAME, not the (potentially too-long)
    draft seat names — see the module docstring comment above those constants for why.
    """
    config = {
        "skipPostGamePrompts": True,
        "gameType": "Two Player Duel",
        "deckType": "Limited",
        "players": [
            {"type": "pilot", "name": _PILOT_A_NAME, "preset": preset_a, "deck": str(deck_a)},
            {"type": "pilot", "name": _PILOT_B_NAME, "preset": preset_b, "deck": str(deck_b)},
        ],
    }
    config_dir = project_root / "tmp" / "draft-match-configs"
    config_dir.mkdir(parents=True, exist_ok=True)
    config_path = config_dir / f"game{index}.json"
    config_path.write_text(json.dumps(config, indent=2) + "\n")
    return config_path


def main() -> int:
    setup_logging()
    parser = argparse.ArgumentParser(description="Run repeated draft-then-play matches between two model presets")
    parser.add_argument("--preset-a", required=True, help="Preset name from puppeteer/presets.json")
    parser.add_argument("--preset-b", required=True, help="Preset name from puppeteer/presets.json")
    parser.add_argument("--set", required=True, dest="set_code", help="Set code to draft from, e.g. TLA")
    parser.add_argument("--games", type=int, default=1)
    parser.add_argument("--packs-per-player", type=int, default=3)
    parser.add_argument(
        "--draft-timeout",
        type=int,
        default=3600,
        help="Seconds to wait for both drafted decks before abandoning the game (default 3600). "
        "A reasoning model at max effort can spend 20+ minutes on a full pod draft.",
    )
    parser.add_argument(
        "--filler-bots",
        type=int,
        default=6,
        help=(
            "Heuristic draft bots seated alongside the two LLMs. Default 6 makes an "
            "8-seat pod, the standard draft size; Booster Draft Elimination needs 4+."
        ),
    )
    args = parser.parse_args()

    logger.info("Compiling project...")
    if not compile_project(_ROOT, observer=True) or not _compile_draft_bot(_ROOT):
        logger.error("Compilation failed")
        return 1

    wins = {args.preset_a: 0, args.preset_b: 0}
    total_cost = 0.0
    total_draft_cost = 0.0
    games_completed = 0

    for i in range(1, args.games + 1):
        print(f"\n{'=' * 60}\nGame {i}/{args.games}: {args.preset_a} vs {args.preset_b}\n{'=' * 60}")
        try:
            # Draft seat names (3rd/4th values) only matter to the draft phase itself
            # (per-seat JVM properties, drafted-deck filenames) - the play phase logs
            # in under its own fixed _PILOT_A_NAME/_PILOT_B_NAME, so they're discarded here.
            draft = run_draft(
                args.preset_a,
                args.preset_b,
                args.set_code,
                args.packs_per_player,
                _ROOT,
                filler_bots=args.filler_bots,
                draft_timeout=args.draft_timeout,
            )
            deck_a, deck_b, draft_cost = draft.deck_a, draft.deck_b, draft.cost_usd
        except (RuntimeError, TimeoutError) as exc:
            logger.error("Game %d: draft failed: %s", i, exc)
            continue

        clean_stale_h2_locks(_ROOT)
        config_path = build_game_config(args.preset_a, args.preset_b, deck_a, deck_b, _ROOT, i)
        result = run_orchestrator(
            Config(config_file=config_path, observer=True, record=False, skip_compile=True),
            project_root=_ROOT,
        )
        if result.exit_code != 0 or not result.sessions:
            logger.error("Game %d: play phase failed (exit code %d)", i, result.exit_code)
            continue

        session = result.sessions[0]
        # The draft ran in its own log directory before the game directory existed, so its
        # record is copied in here. export_game.py only ever looks inside the game dir, and
        # a draft replay is meaningless detached from the game its decks were built for.
        draft_log = draft.draft_dir / DRAFT_LOG_NAME
        if draft_log.exists():
            shutil.copy2(draft_log, session.game_dir / DRAFT_LOG_NAME)
        else:
            logger.warning("No %s to attach to %s", DRAFT_LOG_NAME, session.game_dir)
        winner_name = read_game_winner(session.game_dir)
        play_cost = sum(result.pilot_costs.values())
        # The draft is a real LLM expense (roughly 40 picks plus the deckbuild round trips per
        # seat) and used to be omitted from every figure this tool reported.
        cost = play_cost + draft_cost
        total_cost += cost
        total_draft_cost += draft_cost
        games_completed += 1
        if winner_name == _PILOT_A_NAME:
            wins[args.preset_a] += 1
        elif winner_name == _PILOT_B_NAME:
            wins[args.preset_b] += 1
        else:
            logger.warning("Game %d: no clear winner recorded (%r)", i, winner_name)
        print(
            f"Game {i} winner: {winner_name}  cost: ${cost:.4f} "
            f"(draft ${draft_cost:.4f} + play ${play_cost:.4f})"
        )

    print(f"\n{'=' * 60}\nFINAL RESULTS ({games_completed}/{args.games} games completed)\n{'=' * 60}")
    print(f"  {args.preset_a}: {wins[args.preset_a]} wins")
    print(f"  {args.preset_b}: {wins[args.preset_b]} wins")
    print(f"  Total cost: ${total_cost:.4f}"
          f"  (draft ${total_draft_cost:.4f}, play ${total_cost - total_draft_cost:.4f})")
    print(f"{'=' * 60}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
