package mage.player.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorFactory;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardRepository;
import mage.constants.Rarity;
import mage.constants.RangeOfInfluence;
import mage.game.draft.Draft;
import mage.game.tournament.Tournament;
import mage.util.RandomUtil;
import mage.util.TournamentUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI: LLM-backed draft bot. Same draft-only participation rules as
 * {@link ComputerDraftPlayer} (concedes any real game), but picks cards by
 * asking an LLM via OpenRouter instead of the RateCard heuristic.
 * <p>
 * Configure the model with -Dxmage.llmDraft.model=provider/model-id
 * (defaults to deepseek/deepseek-v3.2). For a draft with multiple LlmDraftPlayer seats in
 * the same server JVM, pin an individual seat to its own model with
 * -Dxmage.llmDraft.model.&lt;PlayerName&gt;=provider/model-id, which takes precedence over the
 * shared property above. Requires OPENROUTER_API_KEY in the
 * environment. Falls back to the heuristic {@link ComputerPlayer#pickCard}
 * on any error (missing key, network failure, unparseable response).
 */
public class LlmDraftPlayer extends ComputerDraftPlayer {

    private static final Logger logger = Logger.getLogger(LlmDraftPlayer.class);

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek/deepseek-v3.2";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    // Deckbuilding is one call over a 45-card pool, so it needs far more room than a pick.
    private static final Duration DECKBUILD_TIMEOUT = Duration.ofSeconds(300);
    private static final List<String> BASIC_LAND_NAMES =
            List.of("Plains", "Island", "Swamp", "Mountain", "Forest");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LlmDraftPlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    public LlmDraftPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public LlmDraftPlayer(final LlmDraftPlayer player) {
        super(player);
    }

    @Override
    public LlmDraftPlayer copy() {
        return new LlmDraftPlayer(this);
    }

    @Override
    public void pickCard(List<Card> cards, Deck deck, Draft draft) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("No cards to pick from.");
        }
        if (cards.size() == 1) {
            // forced pick, no need to spend an LLM call on it
            draft.addPick(playerId, cards.get(0).getId(), null);
            return;
        }
        try {
            Card picked = pickCardWithLlm(cards, deck);
            logger.info("LlmDraftPlayer(" + getName() + "): picked " + picked.getName()
                    + " from a pack of " + cards.size());
            draft.addPick(playerId, picked.getId(), null);
        } catch (Exception e) {
            logger.error("LlmDraftPlayer(" + getName() + "): LLM pick failed, falling back to heuristic", e);
            super.pickCard(cards, deck, draft);
        }
    }

    private Card pickCardWithLlm(List<Card> cards, Deck deck) throws IOException, InterruptedException {
        // DraftImpl's booster-sending scheduler runs every player's pickCard() inline on its
        // own single scheduled-executor thread, then self-cancels its own repeating task
        // (boosterSendingEnd() -> Future.cancel(true)) once a round finishes — which interrupts
        // that same worker thread. The interrupted flag survives on the thread into the *next*
        // round's tick, so the first HttpClient.send() call on that thread throws a spurious
        // InterruptedException before any real request is even sent. Clearing the flag here
        // (Thread.interrupted() reads-and-clears) means a stale flag from a prior round's
        // self-cancel can't be mistaken for a genuine interrupt of this pick's own HTTP call.
        Thread.interrupted();
        String apiKey = requireApiKey();
        // Per-seat override lets two LlmDraftPlayer instances in the same server JVM draft
        // with different models (e.g. a two-player draft between model A and model B) —
        // falls back to the shared global property, then the hardcoded default, so existing
        // single-model usage (-Dxmage.llmDraft.model=...) keeps working unchanged.
        String model = resolveModel(getName());

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system",
                "You are an expert Magic: The Gathering booster draft player. "
                        + "You will be shown your picks so far and the current pack. "
                        + "Pick the single best card for a cohesive, powerful 2-color deck, "
                        + "weighing raw power, curve, and synergy with your existing picks. "
                        + "Respond with ONLY the pack number of your pick, nothing else."));
        messages.add(chatMessage("user", buildPrompt(cards, deck)));
        payload.add("messages", messages);
        applyReasoningEffort(payload);

        String content = sendChatCompletion(payload, apiKey, REQUEST_TIMEOUT);
        return parsePick(content, cards);
    }

    /** POST a chat-completion payload to OpenRouter and return the assistant's text. */
    private static String sendChatCompletion(JsonObject payload, String apiKey, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        return responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    private static String resolveModel(String playerName) {
        return System.getProperty(
                "xmage.llmDraft.model." + playerName,
                System.getProperty("xmage.llmDraft.model", DEFAULT_MODEL));
    }

    /**
     * Reasoning effort for this seat, or null to let the provider use its own default.
     * Without this the draft and deckbuild calls ran at whatever effort the model defaults
     * to, so a preset's reasoning_effort silently applied to gameplay but not to drafting.
     */
    private static String resolveEffort(String playerName) {
        String effort = System.getProperty(
                "xmage.llmDraft.effort." + playerName,
                System.getProperty("xmage.llmDraft.effort", ""));
        return effort.isEmpty() ? null : effort;
    }

    /** Add reasoning.effort to a payload when one is configured for this seat. */
    private void applyReasoningEffort(JsonObject payload) {
        String effort = resolveEffort(getName());
        if (effort == null) {
            return;
        }
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        payload.add("reasoning", reasoning);
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENROUTER_API_KEY is not set");
        }
        return apiKey;
    }

    /**
     * Build the post-draft deck by asking the model, instead of using the heuristic
     * deckbuilder in {@link ComputerPlayer#construct}.
     *
     * Until now the model only ever chose which cards to *draft*; which of those 45 cards
     * actually made the 40-card deck, and the entire mana base, were decided by
     * ComputerPlayer's RateCard heuristic. So a model could draft a clean two-colour pool
     * and still be handed a five-colour deck it never asked for.
     *
     * The prompt is deliberately minimal: it states the legal minimum deck size (a rule of
     * the format, which the model cannot otherwise know) and nothing else -- no advice on
     * land counts, curve, or how many colours to play. The point is to see what the model
     * does unaided. Falls back to the heuristic builder on any failure.
     */
    @Override
    public void construct(Tournament tournament, Deck deck) {
        DeckValidator validator = DeckValidatorFactory.instance.createDeckValidator(
                tournament.getOptions().getMatchOptions().getDeckType());
        int deckMinSize = validator != null ? validator.getDeckMinSize() : 0;

        try {
            if (buildDeckWithLlm(deck, deckMinSize)) {
                logDeck(deck);
                tournament.submitDeck(playerId, deck);
                return;
            }
        } catch (Exception e) {
            logger.error("LlmDraftPlayer(" + getName() + "): LLM deckbuild failed, "
                    + "falling back to the heuristic builder", e);
        }
        super.construct(tournament, deck);
    }

    /** Returns true if the model produced a usable deck; false to fall back. */
    private boolean buildDeckWithLlm(Deck deck, int deckMinSize)
            throws IOException, InterruptedException {
        // Same stale-interrupt guard as pickCard: the draft's scheduler thread can carry an
        // interrupted flag into this call and blow up the first HttpClient.send().
        Thread.interrupted();

        List<Card> pool = new ArrayList<>(deck.getSideboard());
        if (pool.isEmpty()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", resolveModel(getName()));
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system",
                "You are building a Magic: The Gathering deck from cards you just drafted. "
                        + "Respond with ONLY a JSON object, no prose and no code fences."));
        messages.add(chatMessage("user", buildDeckPrompt(pool, deckMinSize)));
        payload.add("messages", messages);
        applyReasoningEffort(payload);

        String content = sendChatCompletion(payload, requireApiKey(), DECKBUILD_TIMEOUT);
        logger.info("LlmDraftPlayer(" + getName() + "): deckbuild response: " + content);

        JsonObject choice = parseJsonObject(content);
        if (choice == null) {
            logger.error("LlmDraftPlayer(" + getName() + "): could not parse deckbuild JSON");
            return false;
        }

        List<Card> chosen = resolveChosenSpells(choice, pool);
        Map<String, Integer> lands = parseBasicLands(choice);

        for (Card card : chosen) {
            deck.getCards().add(card);
            deck.getSideboard().remove(card);
        }
        int landTotal = 0;
        for (Map.Entry<String, Integer> e : lands.entrySet()) {
            addBasicLands(deck, e.getKey(), e.getValue());
            landTotal += e.getValue();
        }

        int size = deck.getMaindeckCards().size();
        logger.info("LlmDraftPlayer(" + getName() + "): model chose " + chosen.size()
                + " spells + " + landTotal + " basic lands = " + size + " cards"
                + " (legal minimum " + deckMinSize + "); lands=" + lands);

        if (size < deckMinSize) {
            // Submitting an undersized deck gets it rejected outright, and then there is
            // nothing to review. Top up with the model's own most-requested basic so the
            // deck is legal, and say loudly that we had to.
            String filler = mostRequestedBasic(lands);
            int shortfall = deckMinSize - size;
            logger.warn("LlmDraftPlayer(" + getName() + "): deck was " + shortfall
                    + " cards under the legal minimum; topping up with " + shortfall + " " + filler);
            addBasicLands(deck, filler, shortfall);
        }
        return true;
    }

    private String buildDeckPrompt(List<Card> pool, int deckMinSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("You drafted these ").append(pool.size()).append(" cards:\n");
        for (int i = 0; i < pool.size(); i++) {
            sb.append(i + 1).append(". ").append(cardSummary(pool.get(i))).append('\n');
        }
        sb.append("\nBuild your deck from them. You may also add any number of basic lands ")
                .append("(Plains, Island, Swamp, Mountain, Forest), which are not in the list above ")
                .append("and are available in unlimited quantities.\n");
        sb.append("The deck must contain at least ").append(deckMinSize).append(" cards in total.\n");
        sb.append("\nRespond with ONLY this JSON:\n");
        sb.append("{\"spells\": [<numbers of the cards above to play>], ");
        sb.append("\"basic_lands\": {\"Plains\": 0, \"Island\": 0, \"Swamp\": 0, ");
        sb.append("\"Mountain\": 0, \"Forest\": 0}}");
        return sb.toString();
    }

    /** Pull the first {...} out of the response, tolerating stray prose or code fences. */
    private static JsonObject parseJsonObject(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(content.substring(start, end + 1));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Card> resolveChosenSpells(JsonObject choice, List<Card> pool) {
        List<Card> chosen = new ArrayList<>();
        if (!choice.has("spells") || !choice.get("spells").isJsonArray()) {
            return chosen;
        }
        Set<Integer> used = new java.util.HashSet<>();
        for (JsonElement el : choice.getAsJsonArray("spells")) {
            int index;
            try {
                index = el.getAsInt() - 1;
            } catch (RuntimeException e) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring non-numeric spell entry " + el);
                continue;
            }
            if (index < 0 || index >= pool.size()) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring out-of-range spell index " + (index + 1));
                continue;
            }
            // Each drafted card is a single physical card; a repeated index is not two copies.
            if (!used.add(index)) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring duplicate spell index " + (index + 1));
                continue;
            }
            chosen.add(pool.get(index));
        }
        return chosen;
    }

    private Map<String, Integer> parseBasicLands(JsonObject choice) {
        Map<String, Integer> lands = new LinkedHashMap<>();
        if (!choice.has("basic_lands") || !choice.get("basic_lands").isJsonObject()) {
            return lands;
        }
        JsonObject obj = choice.getAsJsonObject("basic_lands");
        for (String name : BASIC_LAND_NAMES) {
            if (!obj.has(name)) {
                continue;
            }
            int count;
            try {
                count = obj.get(name).getAsInt();
            } catch (RuntimeException e) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring non-numeric land count for " + name);
                continue;
            }
            if (count > 0) {
                lands.put(name, count);
            }
        }
        return lands;
    }

    private static String mostRequestedBasic(Map<String, Integer> lands) {
        String best = "Forest";
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : lands.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void logDeck(Deck deck) {
        StringBuilder sb = new StringBuilder();
        for (Card card : deck.getMaindeckCards()) {
            sb.append(card.getName()).append(", ");
        }
        logger.info("LlmDraftPlayer(" + getName() + ") submitted deck ("
                + deck.getMaindeckCards().size() + " cards): " + sb);
    }

    /**
     * Local copy of ComputerPlayer's private addBasicLands. Note it picks a random printing
     * per land, which is why a deck's basics come out spread across several set numbers.
     */
    private static void addBasicLands(Deck deck, String landName, int number) {
        Set<String> landSets = TournamentUtil.getLandSetCodeForDeckSets(deck.getExpansionSetCodes());

        CardCriteria criteria = new CardCriteria();
        if (!landSets.isEmpty()) {
            criteria.setCodes(landSets.toArray(new String[0]));
        }
        criteria.rarities(Rarity.LAND).name(landName);
        List<CardInfo> cards = CardRepository.instance.findCards(criteria);

        if (cards.isEmpty()) {
            criteria = new CardCriteria();
            criteria.rarities(Rarity.LAND).name(landName);
            criteria.setCodes("M15");
            cards = CardRepository.instance.findCards(criteria);
        }
        if (cards.isEmpty()) {
            logger.error("LlmDraftPlayer: no printing found for basic land " + landName);
            return;
        }

        for (int i = 0; i < number; i++) {
            deck.getCards().add(cards.get(RandomUtil.nextInt(cards.size())).createCard());
        }
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String buildPrompt(List<Card> cards, Deck deck) {
        StringBuilder sb = new StringBuilder();

        List<Card> pool = deck.getCards().stream().toList();
        sb.append("Your pool so far (").append(pool.size()).append(" cards):\n");
        if (pool.isEmpty()) {
            sb.append("(none yet - this is your first pick)\n");
        } else {
            for (Card card : pool) {
                sb.append("- ").append(cardSummary(card)).append('\n');
            }
        }

        sb.append("\nCurrent pack (").append(cards.size()).append(" cards):\n");
        for (int i = 0; i < cards.size(); i++) {
            sb.append(i + 1).append(". ").append(cardSummary(cards.get(i))).append('\n');
        }

        sb.append("\nRespond with ONLY the number (1-").append(cards.size()).append(") of the card to pick.");
        return sb.toString();
    }

    private static String cardSummary(Card card) {
        String cost = card.getManaCost() != null ? card.getManaCost().getText() : "";
        String rarity = card.getRarity() != null ? card.getRarity().toString() : "?";
        String rules = String.join(" ", card.getRules());
        return card.getName() + " " + cost + " [" + rarity + "] " + rules;
    }

    private Card parsePick(String content, List<Card> cards) {
        if (content != null) {
            Matcher matcher = Pattern.compile("\\d+").matcher(content);
            if (matcher.find()) {
                int index = Integer.parseInt(matcher.group()) - 1;
                if (index >= 0 && index < cards.size()) {
                    return cards.get(index);
                }
            }
            // fall back to matching by card name, in case the model ignored the "number only" instruction
            String lowerContent = content.toLowerCase();
            for (Card card : cards) {
                if (lowerContent.contains(card.getName().toLowerCase())) {
                    return card;
                }
            }
        }
        throw new IllegalStateException("Could not parse a valid pick from LLM response: " + content);
    }
}
