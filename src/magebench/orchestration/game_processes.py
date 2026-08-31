"""Process-launch and readiness helpers for orchestrator runs."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from magebench.common.llm_cost import DEFAULT_LLM_PROVIDER, required_api_key_env
from magebench.common.log import get_logger
from magebench.common.process_manager import ProcessManager
from magebench.orchestration.config import Config, PilotPlayer

logger = get_logger(__name__)

_SPECTATOR_TABLE_READY = "AI Puppeteer: waiting for"
_SPECTATOR_GAME_STARTED = "AI Puppeteer: all players joined"

# In fully headless pilot-vs-pilot games (no human, no --observer), the spectator/observer
# client does not reliably self-terminate once the game ends — it only has a documented
# auto-exit for "no bridge clients ever connected," which doesn't apply once pilots have
# connected and played a full game. Both wait_with_pilot_monitoring (single-game runs) and
# batch_coordination.wait_for_all_games (batch runs) treat "all pilots exited cleanly" as an
# equally authoritative completion signal, after this grace period to let the spectator exit
# on its own first (the normal path for games with a human/recording).
POST_PILOT_GRACE_SECS = 10.0


def wait_for_spectator_table(log_path: Path, proc: subprocess.Popen, timeout: int = 300) -> None:
    """Block until the spectator log indicates the game table is ready."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("Spectator process exited before creating the game table")
        if log_path.exists():
            text = log_path.read_text()
            if _SPECTATOR_TABLE_READY in text:
                return
        time.sleep(2)
    raise TimeoutError(f"Spectator did not create a table within {timeout}s — check {log_path}")


def wait_for_game_start(log_path: Path, proc: subprocess.Popen, timeout: int = 600) -> None:
    """Block until the spectator log indicates the game started."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return
        if log_path.exists():
            text = log_path.read_text()
            if _SPECTATOR_GAME_STARTED in text:
                return
        time.sleep(2)
    raise TimeoutError(f"Game did not start within {timeout}s — check {log_path}")


def _find_drafted_deck(decks_dir: Path, seat_name: str, since: float) -> Path | None:
    """Find the most recently written deck for a seat, per TournamentImpl's naming
    (<PlayerName>-<GuildArchetype>-<shortId>.dck), ignoring anything older than `since` so a
    stale deck from a previous run with the same seat name can't be mistaken for a fresh one.
    """
    if not decks_dir.is_dir():
        return None
    candidates = [p for p in decks_dir.glob(f"{seat_name}-*.dck") if p.stat().st_mtime >= since]
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def wait_for_draft_completion(
    project_root: Path,
    seat_a_name: str,
    seat_b_name: str,
    since: float,
    proc: subprocess.Popen,
    timeout: int = 300,
) -> tuple[Path, Path]:
    """Block until both seats' drafted decks appear under Mage.Server/data/decks/drafted/.

    Note the Mage.Server prefix: start_server() launches the XMage server with
    cwd=project_root/"Mage.Server", and TournamentImpl.saveSubmittedDeckToDisk() resolves
    its "data/decks/drafted/..." path relative to that process's cwd — not the repo root.

    Never waits unboundedly — this project has twice already shipped a bare `while True`
    poll loop that could hang forever (see POST_PILOT_GRACE_SECS above and
    batch_coordination.wait_for_all_games); this one raises past `timeout` instead.
    """
    decks_dir = project_root / "Mage.Server" / "data" / "decks" / "drafted"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"Draft process exited (code {proc.poll()}) before both decks appeared")
        deck_a = _find_drafted_deck(decks_dir, seat_a_name, since)
        deck_b = _find_drafted_deck(decks_dir, seat_b_name, since)
        if deck_a and deck_b:
            return deck_a, deck_b
        time.sleep(2)
    raise TimeoutError(f"Draft did not complete within {timeout}s — check the draft client log")


def bring_to_foreground_macos() -> None:
    """Bring the Java app to foreground on macOS using AppleScript."""
    if sys.platform != "darwin":
        return

    time.sleep(2)
    subprocess.run(
        [
            "osascript",
            "-e",
            'tell application "System Events" to set frontmost of first process whose name contains "java" to true',
        ],
        capture_output=True,
    )


def wait_with_pilot_monitoring(
    spectator_proc: subprocess.Popen,
    pilot_procs: list[tuple[str, subprocess.Popen]],
    pm: ProcessManager,
    poll_interval: float = 2.0,
) -> int:
    """Wait for the spectator to exit, aborting if any pilot dies with an error.

    If all pilots exit cleanly but the spectator never self-terminates (see
    POST_PILOT_GRACE_SECS above), force it closed instead of waiting forever.
    """
    pilots_clean_since: float | None = None
    while True:
        spectator_rc = spectator_proc.poll()
        if spectator_rc is not None:
            if spectator_rc != 0:
                logger.error("Spectator exited with code %s — aborting game.", spectator_rc)
                pm.cleanup()
            return spectator_rc

        all_clean = bool(pilot_procs)
        for name, proc in pilot_procs:
            rc = proc.poll()
            if rc is not None and rc != 0:
                logger.error("Pilot '%s' exited with code %s — aborting game.", name, rc)
                pm.cleanup()
                return -1
            if rc != 0:
                all_clean = False

        if all_clean:
            now = time.monotonic()
            if pilots_clean_since is None:
                pilots_clean_since = now
            elif now - pilots_clean_since >= POST_PILOT_GRACE_SECS:
                logger.warning(
                    "All pilots exited cleanly but spectator did not self-terminate "
                    "within %.0fs — forcing shutdown.",
                    POST_PILOT_GRACE_SECS,
                )
                spectator_proc.terminate()
                return 0
        else:
            pilots_clean_since = None

        time.sleep(poll_interval)


def start_server(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    config_path: Path,
    log_path: Path,
) -> subprocess.Popen:
    """Start the XMage server."""
    jvm_args = " ".join(
        [
            config.jvm_bridge_opts,
            "-Xmx1024m",
            f"-Dxmage.config.path={config_path}",
        ]
    )

    env = {
        "XMAGE_AI_PUPPETEER": "1",
        "XMAGE_AI_PUPPETEER_USER": config.user,
        "XMAGE_AI_PUPPETEER_PASSWORD": config.password,
        "XMAGE_AI_PUPPETEER_SERVER": config.server,
        "XMAGE_AI_PUPPETEER_PORT": str(config.port),
        "XMAGE_AI_PUPPETEER_DISABLE_WHATS_NEW": "1",
        "MAVEN_OPTS": jvm_args,
    }

    return pm.start_jvm_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Server",
        env=env,
        log_file=log_path,
    )


def start_gui_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start the GUI spectator client."""
    config_json = config.get_players_config_json()

    jvm_args_list = [
        config.jvm_opens,
        config.jvm_rendering,
        "-Xmx1536m",
        "-Dxmage.aiPuppeteer.autoConnect=true",
        "-Dxmage.aiPuppeteer.autoStart=true",
        "-Dxmage.aiPuppeteer.disableWhatsNew=true",
        f"-Dxmage.aiPuppeteer.server={config.server}",
        f"-Dxmage.aiPuppeteer.port={config.port}",
        f"-Dxmage.aiPuppeteer.user={config.user}",
        f"-Dxmage.aiPuppeteer.password={config.password}",
    ]
    if game_dir:
        # Without this, TablesPanel.createConfiguredAiPuppeteerGame() never sets
        # MatchOptions.gameLogDir, so ServerGameEventLogCollector silently no-ops and
        # server_game_events.jsonl (required by export_game.py) is never written.
        jvm_args_list.append(f"-Dxmage.observer.gameDir={game_dir}")

    jvm_args = " ".join(jvm_args_list)

    env = {
        "XMAGE_AI_PUPPETEER": "1",
        "XMAGE_AI_PUPPETEER_USER": config.user,
        "XMAGE_AI_PUPPETEER_PASSWORD": config.password,
        "XMAGE_AI_PUPPETEER_SERVER": config.server,
        "XMAGE_AI_PUPPETEER_PORT": str(config.port),
        "XMAGE_AI_PUPPETEER_DISABLE_WHATS_NEW": "1",
        "XMAGE_AI_PUPPETEER_PLAYERS_CONFIG": config_json,
        "MAVEN_OPTS": jvm_args,
    }
    if config.match_time_limit:
        env["XMAGE_AI_PUPPETEER_MATCH_TIME_LIMIT"] = config.match_time_limit
    if config.match_buffer_time:
        env["XMAGE_AI_PUPPETEER_MATCH_BUFFER_TIME"] = config.match_buffer_time
    if config.custom_start_life:
        env["XMAGE_AI_PUPPETEER_CUSTOM_START_LIFE"] = str(config.custom_start_life)
    if config.skip_init_shuffling:
        env["XMAGE_AI_PUPPETEER_SKIP_INIT_SHUFFLING"] = "true"

    return pm.start_jvm_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client",
        env=env,
        log_file=log_path,
    )


def start_draft_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    seat_a_name: str,
    seat_a_model: str,
    seat_b_name: str,
    seat_b_model: str,
    set_code: str,
    log_path: Path,
    packs_per_player: int = 3,
) -> subprocess.Popen:
    """Start an all-bot LLM-drafted-booster-draft tournament between two named models.

    Drives TablesPanel.createConfiguredAiPuppeteerTournament() (Mage.Client module — same
    process type as start_gui_client, since that's where the tournament auto-start code
    lives). Both seats are LlmDraftPlayer bots; the per-seat -Dxmage.llmDraft.model.<name>
    properties let them draft with different models even though they run in the same server
    JVM. The post-draft match is expected to auto-concede immediately — only the two
    decklists TournamentImpl saves to data/decks/drafted/ matter.
    """
    players_config = json.dumps(
        {
            "players": [
                {"type": "bot", "ai": "COMPUTER_LLM_DRAFT_BOT", "name": seat_a_name},
                {"type": "bot", "ai": "COMPUTER_LLM_DRAFT_BOT", "name": seat_b_name},
            ],
            "draftSetCode": set_code,
            "draftPacksPerPlayer": packs_per_player,
        }
    )

    jvm_args = " ".join(
        [
            config.jvm_opens,
            config.jvm_rendering,
            "-Xmx1536m",
            "-Dxmage.aiPuppeteer.autoConnect=true",
            "-Dxmage.aiPuppeteer.autoStartTournament=true",
            "-Dxmage.aiPuppeteer.disableWhatsNew=true",
            f"-Dxmage.aiPuppeteer.server={config.server}",
            f"-Dxmage.aiPuppeteer.port={config.port}",
            f"-Dxmage.aiPuppeteer.user={config.user}",
            f"-Dxmage.aiPuppeteer.password={config.password}",
            f"-Dxmage.llmDraft.model.{seat_a_name}={seat_a_model}",
            f"-Dxmage.llmDraft.model.{seat_b_name}={seat_b_model}",
        ]
    )

    env = {
        "XMAGE_AI_PUPPETEER": "1",
        "XMAGE_AI_PUPPETEER_USER": config.user,
        "XMAGE_AI_PUPPETEER_PASSWORD": config.password,
        "XMAGE_AI_PUPPETEER_SERVER": config.server,
        "XMAGE_AI_PUPPETEER_PORT": str(config.port),
        "XMAGE_AI_PUPPETEER_DISABLE_WHATS_NEW": "1",
        "XMAGE_AI_PUPPETEER_PLAYERS_CONFIG": players_config,
        "MAVEN_OPTS": jvm_args,
    }

    return pm.start_jvm_process(
        args=["mvn", "-q", "exec:java"],
        cwd=project_root / "Mage.Client",
        env=env,
        log_file=log_path,
    )


def start_sleepwalker_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    deck_path: str | None,
    log_path: Path,
) -> subprocess.Popen:
    """Start a sleepwalker client."""
    env = {"PYTHONUNBUFFERED": "1"}
    args = [
        sys.executable,
        "-m",
        "magebench.pilot.sleepwalker",
        "--server",
        config.server,
        "--port",
        str(config.port),
        "--username",
        name,
        "--project-root",
        str(project_root),
    ]
    if deck_path:
        args.extend(["--deck", str(project_root / deck_path)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_replay_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    name: str,
    deck_path: str | None,
    script_path: str | None,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start a replay client."""
    env = {"PYTHONUNBUFFERED": "1"}
    args = [
        sys.executable,
        "-m",
        "magebench.pilot.replay",
        "--server",
        config.server,
        "--port",
        str(config.port),
        "--username",
        name,
        "--project-root",
        str(project_root),
    ]
    if deck_path:
        args.extend(["--deck", str(project_root / deck_path)])
    if script_path:
        args.extend(["--script", str(project_root / script_path)])
    if game_dir:
        args.extend(["--game-dir", str(game_dir)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_pilot_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    player: PilotPlayer,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start an LLM-powered pilot client via MCP."""
    env = {"PYTHONUNBUFFERED": "1"}

    key_env = required_api_key_env(player.provider)
    api_key = os.environ.get(key_env)
    if api_key:
        env[key_env] = api_key

    args = [
        sys.executable,
        "-m",
        "magebench.pilot.pilot",
        "--server",
        config.server,
        "--port",
        str(config.port),
        "--username",
        player.name,
        "--project-root",
        str(project_root),
    ]

    if player.deck:
        args.extend(["--deck", str(project_root / player.deck)])
    if player.model:
        args.extend(["--model", player.model])
    if player.provider != DEFAULT_LLM_PROVIDER:
        args.extend(["--provider", player.provider])

    assert player.system_prompt, f"Pilot player {player.name} has no system_prompt (check preset)"
    effective_prompt = player.system_prompt
    if player.prompt_suffix:
        effective_prompt += (
            "\n\n## Chat Personality\n"
            "You have a chat personality described below. Use it to flavor your "
            "narration and trash-talk — be expressive, have fun with it, and "
            "react to your opponent's chat messages in character. But your actual "
            "gameplay decisions (card choices, attacks, blocks, targets, sequencing) "
            "must always be based on optimal Magic strategy. Never let the persona "
            "influence which play you choose.\n\n" + player.prompt_suffix
        )
    args.extend(["--system-prompt", effective_prompt])
    if player.max_interactions_per_turn is not None:
        args.extend(["--max-interactions-per-turn", str(player.max_interactions_per_turn)])
    if player.reasoning_effort:
        args.extend(["--reasoning-effort", player.reasoning_effort])
    if player.tools is not None:
        args.extend(["--tools", ",".join(player.tools)])
    if player.ignore_providers:
        args.extend(["--ignore-providers", ",".join(player.ignore_providers)])
    if player.provider_order:
        args.extend(["--provider-order", ",".join(player.provider_order)])
    if player.cache_control:
        args.extend(["--cache-control", json.dumps(player.cache_control)])
    if game_dir:
        args.extend(["--game-dir", str(game_dir)])

    return pm.start_process(
        args=args,
        cwd=project_root,
        env=env,
        log_file=log_path,
    )


def start_observer_client(
    pm: ProcessManager,
    project_root: Path,
    config: Config,
    log_path: Path,
    game_dir: Path | None = None,
) -> subprocess.Popen:
    """Start the observer spectator client."""
    config_json = config.get_players_config_json()

    jvm_args_list = [
        config.jvm_opens,
        config.jvm_rendering,
        "-Xmx1536m",
        "-Dxmage.aiPuppeteer.autoConnect=true",
        "-Dxmage.aiPuppeteer.autoStart=true",
        "-Dxmage.aiPuppeteer.disableWhatsNew=true",
        f"-Dxmage.aiPuppeteer.server={config.server}",
        f"-Dxmage.aiPuppeteer.port={config.port}",
        f"-Dxmage.aiPuppeteer.user={config.user}",
        f"-Dxmage.aiPuppeteer.password={config.password}",
    ]
    if game_dir:
        jvm_args_list.append(f"-Dxmage.observer.gameDir={game_dir}")
    if config.record:
        resolved_game_dir = game_dir or (project_root / config.log_dir / f"game_{config.timestamp}").resolve()
        record_path = config.record_output or (resolved_game_dir / "recording.mov")
        jvm_args_list.append(f"-Dxmage.observer.record={record_path}")

    jvm_args = " ".join(jvm_args_list)
    env = {
        "XMAGE_AI_PUPPETEER": "1",
        "XMAGE_AI_PUPPETEER_USER": config.user,
        "XMAGE_AI_PUPPETEER_PASSWORD": config.password,
        "XMAGE_AI_PUPPETEER_SERVER": config.server,
        "XMAGE_AI_PUPPETEER_PORT": str(config.port),
        "XMAGE_AI_PUPPETEER_DISABLE_WHATS_NEW": "1",
        "XMAGE_AI_PUPPETEER_PLAYERS_CONFIG": config_json,
        "MAVEN_OPTS": jvm_args,
    }
    if config.match_time_limit:
        env["XMAGE_AI_PUPPETEER_MATCH_TIME_LIMIT"] = config.match_time_limit
    if config.match_buffer_time:
        env["XMAGE_AI_PUPPETEER_MATCH_BUFFER_TIME"] = config.match_buffer_time
    if config.custom_start_life:
        env["XMAGE_AI_PUPPETEER_CUSTOM_START_LIFE"] = str(config.custom_start_life)
    if config.skip_init_shuffling:
        env["XMAGE_AI_PUPPETEER_SKIP_INIT_SHUFFLING"] = "true"

    args = ["mvn", "-q", "exec:java"]
    if sys.platform == "linux" and "DISPLAY" not in os.environ and "WAYLAND_DISPLAY" not in os.environ:
        xvfb = shutil.which("xvfb-run")
        assert xvfb is not None, (
            "Headless environment detected (no DISPLAY set) but xvfb-run is not installed. "
            "Install xvfb for your distribution (e.g. apt-get install xvfb or dnf install xorg-x11-server-Xvfb)."
        )
        args = [xvfb, "--auto-servernum", "--server-args=-screen 0 1920x1080x24", *args]
        logger.info("Headless environment detected — wrapping observer with xvfb-run")

    return pm.start_jvm_process(
        args=args,
        cwd=project_root / "Mage.Client.Observer",
        env=env,
        log_file=log_path,
    )
