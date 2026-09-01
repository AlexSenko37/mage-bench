package mage.player.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.Mana;
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
    // A deckbuild answer that won't parse costs the whole deck (it falls back to the
    // heuristic builder), so it is worth a couple of retries before giving up.
    private static final int DECKBUILD_ATTEMPTS = 3;
    // How far under the legal minimum we will quietly patch a deck. Past this the answer
    // is broken rather than slightly miscounted, and the heuristic builder is a better deck.
    private static final int MAX_TOPUP_CARDS = 3;
    // Below this a colour is a genuine one-of splash the model may reasonably leave
    // unsupported; at or above it, zero sources means those cards are simply dead.
    private static final int MIN_PIPS_NEEDING_A_SOURCE = 3;
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
        JsonObject message = responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message");
        // include_reasoning asks the provider to return the model's reasoning trace. Those
        // tokens are billed either way, so log them rather than discard them -- they are the
        // only view into why a deckbuild came out the way it did.
        if (message.has("reasoning") && message.get("reasoning").isJsonPrimitive()) {
            String reasoning = message.get("reasoning").getAsString();
            if (!reasoning.isEmpty()) {
                logger.info("LlmDraftPlayer reasoning: " + reasoning);
            }
        }
        return message.get("content").getAsString();
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
    /**
     * Build the deck in two calls, spells first and lands second.
     *
     * A mana base is a consequence of the spells, not an input to them, but a single-call
     * answer let the model emit its land counts first and then try to find spells that fit
     * -- draft_20260901_115822 seat A committed to 5 Forests and could only justify 9
     * spells around them. Across the four single-call decks, every one that emitted lands
     * before spells had an incoherent mana base or a truncated deck; the only one that
     * emitted spells first was the only coherent deck. Splitting the call makes that order
     * structural instead of incidental.
     *
     * The land call is also handed the proportional split computed from the spells the
     * model just chose, as a suggestion it may override. Arithmetic is where the model has
     * been weakest (one deck played 11 blue and 5 black pips with no Islands or Swamps),
     * while spell selection is the part actually worth measuring.
     */
    private boolean buildDeckWithLlm(Deck deck, int deckMinSize)
            throws IOException, InterruptedException {
        // Same stale-interrupt guard as pickCard: the draft's scheduler thread can carry an
        // interrupted flag into this call and blow up the first HttpClient.send().
        Thread.interrupted();

        List<Card> pool = new ArrayList<>(deck.getSideboard());
        if (pool.isEmpty()) {
            return false;
        }

        // ---- call 1: which spells to play -----------------------------------------
        // Retried on an implausible count, not just on a parse failure: seat B of
        // draft_20260901_133739 wrote an analysis describing a normal blue-black control
        // deck naming seven cards, then emitted a list of only 10 indices. The plan was
        // fine and the list was truncated, which a parse check cannot catch -- and because
        // the land count is derived from it, that became a 10-spell, 30-land deck.
        List<Card> chosen = null;
        for (int attempt = 1; attempt <= DECKBUILD_ATTEMPTS; attempt++) {
            JsonObject spellAnswer = requestJson("spells", buildSpellPrompt(pool, deckMinSize),
                    spellsResponseFormat());
            if (spellAnswer == null) {
                logger.error("LlmDraftPlayer(" + getName() + "): no parseable spell JSON");
                return false;
            }
            logAnalysis("spells", spellAnswer);

            List<Card> candidate = resolveChosenSpells(spellAnswer, pool);
            if (isPlausibleSpellCount(candidate.size(), deckMinSize)) {
                chosen = candidate;
                break;
            }
            logger.warn("LlmDraftPlayer(" + getName() + "): spell count " + candidate.size()
                    + " is implausible for a " + deckMinSize + "-card deck (want "
                    + minSpells(deckMinSize) + "-" + maxSpells(deckMinSize) + "); retrying ["
                    + attempt + "/" + DECKBUILD_ATTEMPTS + "]");
        }
        if (chosen == null || chosen.isEmpty()) {
            logger.error("LlmDraftPlayer(" + getName() + "): no plausible spell list after "
                    + DECKBUILD_ATTEMPTS + " attempts; falling back to the heuristic builder");
            return false;
        }

        // ---- call 2: the mana base, anchored on a proportional suggestion ----------
        int landsNeeded = Math.max(0, deckMinSize - chosen.size());
        Map<String, Integer> pips = pipCounts(chosen);
        Map<String, Integer> suggestion = proportionalLands(pips, landsNeeded);

        // The land integers come back corrupted often enough that they have to be checked
        // rather than trusted. Under the strict JSON schema this model has emitted
        // "Island": -1 while its own analysis said it needed "many Islands", and
        // "Island":145 for what the analysis called a splash -- the prose is consistently
        // sane and only the numbers are wrong, so this is a decoding artifact, not a
        // deckbuilding mistake. Retry, then settle for the proportional split.
        Map<String, Integer> lands = null;
        String landPrompt = buildLandPrompt(chosen, pips, suggestion, landsNeeded, deckMinSize);
        for (int attempt = 1; attempt <= DECKBUILD_ATTEMPTS; attempt++) {
            JsonObject landAnswer = requestJson("lands", landPrompt, landsResponseFormat());
            if (landAnswer == null) {
                break;
            }
            logAnalysis("lands", landAnswer);
            Map<String, Integer> candidate = parseBasicLands(landAnswer);
            String problem = landProblem(candidate, pips, landsNeeded);
            if (problem == null) {
                lands = candidate;
                break;
            }
            logger.warn("LlmDraftPlayer(" + getName() + "): land counts rejected (" + problem
                    + "): " + candidate + "; retrying [" + attempt + "/" + DECKBUILD_ATTEMPTS + "]");
        }
        if (lands == null) {
            logger.warn("LlmDraftPlayer(" + getName() + "): no usable land counts; "
                    + "using the proportional suggestion " + suggestion);
            lands = suggestion;
        }

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
                + " (target " + deckMinSize + "); pips=" + pips
                + "; suggested=" + suggestion + "; chosen=" + lands);

        int shortfall = deckMinSize - size;
        if (shortfall > MAX_TOPUP_CARDS) {
            // Anything past a rounding slip is a broken answer, not a near-miss. Topping it
            // up produces a legal-looking deck that is nothing like a deck.
            logger.error("LlmDraftPlayer(" + getName() + "): deck was " + shortfall
                    + " cards under the legal minimum (more than the " + MAX_TOPUP_CARDS
                    + " this will patch); discarding it and falling back to the heuristic builder");
            deck.getCards().clear();
            deck.getSideboard().addAll(chosen);
            return false;
        }
        if (shortfall > 0) {
            String filler = mostRequestedBasic(lands);
            logger.warn("LlmDraftPlayer(" + getName() + "): deck was " + shortfall
                    + " cards under the legal minimum; topping up with " + shortfall + " " + filler);
            addBasicLands(deck, filler, shortfall);
        }
        return true;
    }

    /**
     * A 40-card limited deck is conventionally 17 lands and 23 spells. Anything far outside
     * that means the spell list came back wrong -- and since the land count is derived from
     * it, a short list turns straight into an absurd mana base rather than a small deck.
     */
    private static boolean isPlausibleSpellCount(int count, int deckMinSize) {
        return count >= minSpells(deckMinSize) && count <= maxSpells(deckMinSize);
    }

    private static int minSpells(int deckMinSize) {
        return (int) Math.round(deckMinSize * 0.45);   // 18 of 40
    }

    private static int maxSpells(int deckMinSize) {
        return (int) Math.round(deckMinSize * 0.70);   // 28 of 40
    }

    /**
     * Why a land answer is unusable, or null if it is fine.
     *
     * Checks the two things that actually ruin a deck: a total that isn't the number of
     * lands the deck needs, and a colour the deck genuinely needs with no sources at all.
     */
    private static String landProblem(Map<String, Integer> lands, Map<String, Integer> pips,
                                      int landsNeeded) {
        if (lands.isEmpty()) {
            return "no lands at all";
        }
        int total = 0;
        for (int n : lands.values()) {
            total += n;
        }
        if (total != landsNeeded) {
            return "total " + total + " != the " + landsNeeded + " lands the deck needs";
        }
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() >= MIN_PIPS_NEEDING_A_SOURCE && lands.getOrDefault(e.getKey(), 0) == 0) {
                return e.getValue() + " " + e.getKey() + " pips but no " + e.getKey();
            }
        }
        return null;
    }

    /** Log the model's own account of a choice, for reviewing a run afterwards. */
    private void logAnalysis(String stage, JsonObject answer) {
        if (answer.has("analysis") && answer.get("analysis").isJsonPrimitive()) {
            logger.info("LlmDraftPlayer(" + getName() + ") " + stage + " analysis: "
                    + answer.get("analysis").getAsString());
        }
    }

    /** Coloured pip counts across the chosen spells, keyed by basic land name. */
    private static Map<String, Integer> pipCounts(List<Card> chosen) {
        Mana mana = new Mana();
        for (Card card : chosen) {
            if (card.getManaCost() != null) {
                mana.add(card.getManaCost().getMana());
            }
        }
        Map<String, Integer> pips = new LinkedHashMap<>();
        pips.put("Plains", mana.getWhite());
        pips.put("Island", mana.getBlue());
        pips.put("Swamp", mana.getBlack());
        pips.put("Mountain", mana.getRed());
        pips.put("Forest", mana.getGreen());
        return pips;
    }

    /** Split `count` lands across colours in proportion to their pip counts. */
    private static Map<String, Integer> proportionalLands(Map<String, Integer> pips, int count) {
        int total = 0;
        for (int n : pips.values()) {
            total += n;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (total <= 0 || count <= 0) {
            return out;
        }
        int assigned = 0;
        String biggest = null;
        int biggestPips = -1;
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int n = (int) Math.round((double) e.getValue() / total * count);
            if (n > 0) {
                out.put(e.getKey(), n);
                assigned += n;
            }
            if (e.getValue() > biggestPips) {
                biggestPips = e.getValue();
                biggest = e.getKey();
            }
        }
        // Rounding rarely lands exactly on `count`; settle the difference on the main colour.
        if (biggest != null && assigned != count) {
            out.merge(biggest, count - assigned, Integer::sum);
            if (out.get(biggest) <= 0) {
                out.remove(biggest);
            }
        }
        return out;
    }

    private String buildSpellPrompt(List<Card> pool, int deckMinSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("You drafted these ").append(pool.size()).append(" cards:\n");
        for (int i = 0; i < pool.size(); i++) {
            sb.append(i + 1).append(". ").append(cardSummary(pool.get(i))).append('\n');
        }
        sb.append("\nChoose the spells for your deck. You will pick basic lands separately ")
                .append("afterwards, so do not count lands here.\n");
        sb.append("The finished deck will be exactly ").append(deckMinSize)
                .append(" cards including lands. A typical limited deck is 17 lands and 23 spells, ")
                .append("so aim for about 23 spells.\n");
        sb.append("Most limited decks are two colours. A splash for a powerful card is fine, ")
                .append("but each extra colour costs consistency: a splashed card needs enough ")
                .append("sources to cast it on time, and every land devoted to it is a land not ")
                .append("supporting your main colours.\n");
        sb.append("\nIn \"analysis\", briefly say what your deck is trying to do and which ")
                .append("colours you settled on. Then list the card numbers in \"chosen_spells\".");
        return sb.toString();
    }

    private String buildLandPrompt(List<Card> chosen, Map<String, Integer> pips,
                                   Map<String, Integer> suggestion, int landsNeeded, int deckMinSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("These are the ").append(chosen.size()).append(" spells you chose:\n");
        for (Card card : chosen) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        sb.append("\nColoured mana symbols across those spells:\n");
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() > 0) {
                sb.append("- ").append(e.getKey()).append(" (")
                        .append(colourNameFor(e.getKey())).append("): ")
                        .append(e.getValue()).append('\n');
            }
        }
        sb.append("\nTo reach exactly ").append(deckMinSize).append(" cards you need ")
                .append(landsNeeded).append(" basic lands.\n");
        if (!suggestion.isEmpty()) {
            sb.append("Split proportionally to those symbols, that would be: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : suggestion.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getValue()).append(' ').append(e.getKey());
                first = false;
            }
            sb.append(".\n");
            sb.append("Use that split unless you have a reason to differ -- for example a ")
                    .append("colour you only need late, or a card you must cast on curve.\n");
        }
        sb.append("Every colour in your spells needs sources, or those cards are dead. ")
                .append("Use 0 for a basic land type you are not playing, never a negative number.\n");
        sb.append("The counts must add up to ").append(landsNeeded).append(".\n");
        sb.append("\nIn \"analysis\", briefly justify your split. Then give the counts in ")
                .append("\"land_counts\".");
        return sb.toString();
    }

    private static String colourNameFor(String landName) {
        switch (landName) {
            case "Plains": return "white";
            case "Island": return "blue";
            case "Swamp": return "black";
            case "Mountain": return "red";
            case "Forest": return "green";
            default: return landName;
        }
    }

    /**
     * Ask for one stage of the deckbuild, retrying on an unparseable answer.
     *
     * The first attempt constrains the reply with a JSON schema (response_format). If the
     * provider rejects the schema outright, later attempts drop it and rely on the prompt.
     */
    private JsonObject requestJson(String stage, String userPrompt, JsonObject responseFormat)
            throws IOException, InterruptedException {
        String apiKey = requireApiKey();
        boolean useSchema = true;

        for (int attempt = 1; attempt <= DECKBUILD_ATTEMPTS; attempt++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", resolveModel(getName()));
            JsonArray messages = new JsonArray();
            messages.add(chatMessage("system",
                    "You are building a Magic: The Gathering deck from cards you just drafted. "
                            + "Respond with ONLY a JSON object, no prose and no code fences."));
            messages.add(chatMessage("user", userPrompt));
            payload.add("messages", messages);
            applyReasoningEffort(payload);
            // We are already paying for this model's reasoning tokens; capturing the trace
            // costs nothing extra and shows what it actually weighed.
            payload.addProperty("include_reasoning", true);
            if (useSchema) {
                payload.add("response_format", responseFormat);
            }

            String content;
            try {
                content = sendChatCompletion(payload, apiKey, DECKBUILD_TIMEOUT);
            } catch (IOException e) {
                if (useSchema) {
                    logger.warn("LlmDraftPlayer(" + getName() + "): " + stage + " attempt " + attempt
                            + " failed with response_format set, retrying without it: " + e.getMessage());
                    useSchema = false;
                    continue;
                }
                throw e;
            }

            logger.info("LlmDraftPlayer(" + getName() + "): " + stage + " response (attempt "
                    + attempt + "): " + content);
            JsonObject parsed = parseJsonObject(content);
            if (parsed != null) {
                return parsed;
            }
            logger.warn("LlmDraftPlayer(" + getName() + "): " + stage + " attempt " + attempt
                    + " was not parseable JSON");
        }
        return null;
    }

    private static JsonObject stringProp() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject schemaEnvelope(String name, JsonObject props, JsonArray required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", name);
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("json_schema", jsonSchema);
        return format;
    }

    /** Schema for call 1. "analysis" is listed first so it is written before the choice. */
    private static JsonObject spellsResponseFormat() {
        JsonObject itemType = new JsonObject();
        itemType.addProperty("type", "integer");
        JsonObject spells = new JsonObject();
        spells.addProperty("type", "array");
        spells.add("items", itemType);

        JsonObject props = new JsonObject();
        props.add("analysis", stringProp());
        props.add("chosen_spells", spells);
        JsonArray required = new JsonArray();
        required.add("analysis");
        required.add("chosen_spells");
        return schemaEnvelope("chosen_spells", props, required);
    }

    /** Schema for call 2. */
    private static JsonObject landsResponseFormat() {
        JsonObject landProps = new JsonObject();
        JsonArray landRequired = new JsonArray();
        for (String name : BASIC_LAND_NAMES) {
            JsonObject intType = new JsonObject();
            intType.addProperty("type", "integer");
            landProps.add(name, intType);
            landRequired.add(name);
        }
        JsonObject lands = new JsonObject();
        lands.addProperty("type", "object");
        lands.add("properties", landProps);
        lands.add("required", landRequired);
        lands.addProperty("additionalProperties", false);

        JsonObject props = new JsonObject();
        props.add("analysis", stringProp());
        props.add("land_counts", lands);
        JsonArray required = new JsonArray();
        required.add("analysis");
        required.add("land_counts");
        return schemaEnvelope("land_counts", props, required);
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
        if (!choice.has("chosen_spells") || !choice.get("chosen_spells").isJsonArray()) {
            return chosen;
        }
        Set<Integer> used = new java.util.HashSet<>();
        for (JsonElement el : choice.getAsJsonArray("chosen_spells")) {
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
        if (!choice.has("land_counts") || !choice.get("land_counts").isJsonObject()) {
            return lands;
        }
        JsonObject obj = choice.getAsJsonObject("land_counts");
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
            if (count < 0) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring negative land count for "
                        + name + " (" + count + ")");
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
