"""Card metadata helpers for game export construction."""

import re

from magebench.game import scryfall

DECKLIST_RE = re.compile(r"(?:SB:\s*)?(\d+)\s+\[([^:]+):([^\]]+)\]\s+(.+)")

_CARD_DATA_FIELDS = (
    "mana_cost",
    "type_line",
    "oracle_text",
    "power",
    "toughness",
    "loyalty",
    "defense",
)


def _trim_card(card: dict) -> dict:
    """Extract only the fields the renderer needs from a Scryfall card object."""
    trimmed: dict = {}
    for field in _CARD_DATA_FIELDS:
        val = card.get(field)
        if val is not None:
            trimmed[field] = val
    return trimmed


def _collect_card_names(snapshots: list[dict]) -> tuple[set[str], set[str]]:
    """Scan all snapshot zones for card names.

    Returns (real_card_names, token_names).
    """
    real_cards: set[str] = set()
    tokens: set[str] = set()
    zones = ("battlefield", "graveyard", "exile", "hand", "commanders")

    for snap in snapshots:
        stack = snap.get("stack")
        if stack is not None:
            for item in stack:
                if isinstance(item, dict):
                    name = item.get("name")
                    if name and "ability" not in name.lower():
                        if " Token" in name or " token" in name:
                            tokens.add(name)
                        else:
                            real_cards.add(name)
                elif isinstance(item, str) and "ability" not in item.lower():
                    if " Token" in item or " token" in item:
                        tokens.add(item)
                    else:
                        real_cards.add(item)

        for player in snap["players"]:
            for zone_name in zones:
                zone_cards = player.get(zone_name)
                if zone_cards is not None:
                    for card in zone_cards:
                        if isinstance(card, dict):
                            name = card.get("name")
                        elif isinstance(card, str):
                            name = card
                        else:
                            continue
                        if not name:
                            continue
                        if " Token" in name or " token" in name:
                            tokens.add(name)
                        else:
                            real_cards.add(name)

    return real_cards, tokens


def decklist_card_names(players_meta: list[dict]) -> set[str]:
    """Card names parsed from every player's raw decklist lines.

    Covers the full 40+ card pool a deck explorer needs, not just the subset that
    happened to appear in play (which is all _collect_card_names can see).
    """
    names: set[str] = set()
    for player in players_meta:
        decklist = player.get("decklist")
        if decklist is None:
            continue
        for entry in decklist:
            m = DECKLIST_RE.match(entry)
            if m:
                names.add(m.group(4).strip())
    return names


def build_card_data(
    card_images: dict[str, str],
    snapshots: list[dict],
    players_meta: list[dict] | None = None,
) -> tuple[dict[str, str], dict[str, dict]]:
    """Build cardData metadata and add token images to cardImages.

    Returns (updated_card_images, card_data).
    """
    real_cards, tokens = _collect_card_names(snapshots)
    if players_meta:
        real_cards |= decklist_card_names(players_meta)

    updated_images = dict(card_images)
    for token_name in sorted(tokens):
        if token_name in updated_images:
            continue
        url = scryfall.search_token(token_name)
        if url:
            updated_images[token_name] = url

    card_data: dict[str, dict] = {}
    names_to_fetch = sorted(real_cards)

    for i in range(0, len(names_to_fetch), 75):
        batch = names_to_fetch[i : i + 75]
        found, _not_found = scryfall.collection(batch)
        for card in found:
            card_data[card["name"]] = _trim_card(card)

    for name in names_to_fetch:
        if name in card_data:
            continue
        lookup = scryfall.named(name)
        if lookup:
            trimmed = _trim_card(lookup)
            card_data[lookup["name"]] = trimmed
            # Also alias under the name actually requested: a collection lookup by
            # name can return a card under a slightly different canonical name (split
            # cards, DFC front faces, old print variants) than what the decklist line
            # or a snapshot recorded — without this, the deck explorer silently drops
            # the card because it never finds it under the name it's looking up.
            card_data[name] = trimmed

    return updated_images, card_data
