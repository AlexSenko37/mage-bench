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
    // Propose, see what the list actually adds up to, then accept or revise.
    private static final int SPELL_ROUNDS = 3;
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

        // ---- call 1: which spells to play, then let the model check its own list ----
        // Selection is by card NAME, not by index into the pool. The model reasons about
        // cards by name but was having to emit numbers, and the bookkeeping was where it
        // came apart: one seat stated "green and white, blue excluded as too demanding" and
        // then emitted indices for two blue cards. Names also make a bad answer detectable
        // -- anything not in the pool is rejected rather than silently resolving to
        // whatever card happened to sit at that number.
        List<Card> chosen = null;
        String feedback = null;
        for (int round = 1; round <= SPELL_ROUNDS; round++) {
            JsonObject answer = requestJson("spells",
                    buildSpellPrompt(pool, deckMinSize, feedback), spellsResponseFormat());
            if (answer == null) {
                logger.error("LlmDraftPlayer(" + getName() + "): no parseable spell JSON");
                return false;
            }
            logAnalysis("spells", answer);

            List<Card> candidate = resolveChosenSpells(answer, pool);
            boolean accepted = round > 1 && isAccept(answer);

            if (candidate.isEmpty()) {
                feedback = "Your last answer named no cards from your pool. Choose from the "
                        + "list above, using each card's exact name.";
                continue;
            }
            if (accepted && isPlausibleSpellCount(candidate.size(), deckMinSize)) {
                chosen = candidate;
                break;
            }
            if (round == SPELL_ROUNDS) {
                // Out of review rounds: take the list if it is usable at all.
                if (isPlausibleSpellCount(candidate.size(), deckMinSize)) {
                    chosen = candidate;
                }
                break;
            }
            // Hand back what the proposal actually amounts to and let the model
            // reconcile it against its own stated plan.
            feedback = reviewFeedback(candidate, deckMinSize);
        }
        if (chosen == null || chosen.isEmpty()) {
            logger.error("LlmDraftPlayer(" + getName() + "): no usable spell list after "
                    + SPELL_ROUNDS + " rounds; falling back to the heuristic builder");
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

    private static boolean isAccept(JsonObject answer) {
        return answer.has("decision")
                && answer.get("decision").isJsonPrimitive()
                && "accept".equalsIgnoreCase(answer.get("decision").getAsString());
    }

    /**
     * What the proposed list actually adds up to, handed back for the model to check its
     * own plan against. Its prose has been consistently sound while the list it emitted
     * did not match -- so rather than police that from the outside, show it the numbers
     * and let it reconcile them.
     */
    private String reviewFeedback(List<Card> chosen, int deckMinSize) {
        Map<String, Integer> pips = pipCounts(chosen);
        int creatures = 0;
        Map<Integer, Integer> curve = new java.util.TreeMap<>();
        for (Card card : chosen) {
            if (card.isCreature()) {
                creatures++;
            }
            curve.merge(card.getManaValue(), 1, Integer::sum);
        }
        int lands = deckMinSize - chosen.size();

        StringBuilder sb = new StringBuilder();
        sb.append("You proposed these ").append(chosen.size()).append(" spells:\n");
        for (Card card : chosen) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        sb.append("\nWhat that adds up to:\n");
        sb.append("- ").append(chosen.size()).append(" spells, so ").append(lands)
                .append(" basic lands to reach ").append(deckMinSize).append(" cards")
                .append(lands == 17 ? "" : " (17 is typical)").append('\n');
        sb.append("- ").append(creatures).append(" creatures, ")
                .append(chosen.size() - creatures).append(" non-creature spells\n");
        sb.append("- coloured mana symbols: ");
        boolean any = false;
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() > 0) {
                if (any) {
                    sb.append(", ");
                }
                sb.append(e.getValue()).append(' ').append(colourNameFor(e.getKey()));
                any = true;
            }
        }
        sb.append(any ? "\n" : "none\n");
        int colours = 0;
        for (int n : pips.values()) {
            if (n > 0) {
                colours++;
            }
        }
        sb.append("- that is ").append(colours).append(" colour")
                .append(colours == 1 ? "" : "s").append('\n');
        sb.append("- mana curve: ");
        boolean firstCurve = true;
        for (Map.Entry<Integer, Integer> e : curve.entrySet()) {
            if (!firstCurve) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("cmc x").append(e.getValue());
            firstCurve = false;
        }
        sb.append("\n\nDoes this match the deck you described? If it does, set \"decision\" ")
                .append("to \"accept\" and repeat the same card names. If something is off -- ")
                .append("a colour you did not mean to be in, too few or too many spells, a curve ")
                .append("too heavy at the top -- set \"decision\" to \"revise\" and give the ")
                .append("corrected list.");
        return sb.toString();
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

    private String buildSpellPrompt(List<Card> pool, int deckMinSize, String feedback) {
        StringBuilder sb = new StringBuilder();
        if (feedback != null) {
            sb.append(feedback).append("\n\n");
            sb.append("Your full pool again, for reference:\n");
        } else {
            sb.append("You drafted these ").append(pool.size()).append(" cards:\n");
        }
        for (Card card : pool) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        if (feedback != null) {
            return sb.toString();
        }
        sb.append("\nChoose the spells for your deck. You will pick basic lands separately ")
                .append("afterwards, so do not count lands here.\n");
        sb.append("The finished deck will be exactly ").append(deckMinSize)
                .append(" cards including lands. A typical limited deck is 17 lands and 23 spells, ")
                .append("so aim for about 23 spells.\n");
        // Stronger than the earlier wording, which did not move spell selection at all --
        // every deck still came out five colours. Splashing is normal in this set, so the
        // splash is still allowed; what is spelled out is the price, and that a third
        // colour has to earn its place rather than being where the leftovers go.
        sb.append("Build a two-colour deck. Pick the two colours where your best cards are ")
                .append("and play essentially all of your playables in them.\n");
        sb.append("A splash of a third colour is allowed, but only for a card that is worth ")
                .append("bending the deck around -- a bomb or premium removal, not merely a good ")
                .append("card. A splash costs you 2-3 lands that then do not cast your main ")
                .append("colours, which makes every other card in the deck less reliable. ")
                .append("Splashing a fourth colour is almost never right, and a five-colour ")
                .append("deck loses more games to bad mana than it wins on card quality.\n");
        sb.append("Cards outside your colours stay in the sideboard even when they are strong. ")
                .append("A powerful card you cannot cast on time is worse than a modest one you can.\n");
        sb.append("\nIn \"analysis\", name your two main colours and say why. If you are ")
                .append("splashing, name the card and say what makes it worth the mana cost. ")
                .append("Then give the exact card names in \"chosen_spells\" -- names only, ")
                .append("copied from the list above. You can only play cards you drafted.");
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
        JsonObject spells = new JsonObject();
        spells.addProperty("type", "array");
        spells.add("items", stringProp());

        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        JsonArray allowed = new JsonArray();
        allowed.add("accept");
        allowed.add("revise");
        decision.add("enum", allowed);

        JsonObject props = new JsonObject();
        props.add("analysis", stringProp());
        props.add("decision", decision);
        props.add("chosen_spells", spells);
        JsonArray required = new JsonArray();
        required.add("analysis");
        required.add("decision");
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

    /**
     * Resolve the model's chosen card names against its own pool.
     *
     * Matching is by name, case- and whitespace-insensitive. A name the pool does not
     * contain is dropped and logged: the model can only play what it drafted, so this is
     * also what stops a hallucinated card from entering the deck. Duplicates are honoured
     * only up to the number of physical copies actually drafted.
     */
    private List<Card> resolveChosenSpells(JsonObject choice, List<Card> pool) {
        List<Card> chosen = new ArrayList<>();
        if (!choice.has("chosen_spells") || !choice.get("chosen_spells").isJsonArray()) {
            return chosen;
        }

        Map<String, List<Card>> available = new LinkedHashMap<>();
        for (Card card : pool) {
            available.computeIfAbsent(normaliseCardName(card.getName()), k -> new ArrayList<>()).add(card);
        }

        for (JsonElement el : choice.getAsJsonArray("chosen_spells")) {
            String name;
            try {
                name = el.getAsString();
            } catch (RuntimeException e) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring non-string card entry " + el);
                continue;
            }
            List<Card> copies = available.get(normaliseCardName(name));
            if (copies == null || copies.isEmpty()) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring \"" + name
                        + "\" -- not in the drafted pool"
                        + (copies != null ? " (all copies already used)" : ""));
                continue;
            }
            chosen.add(copies.remove(0));
        }
        return chosen;
    }

    private static String normaliseCardName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
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
