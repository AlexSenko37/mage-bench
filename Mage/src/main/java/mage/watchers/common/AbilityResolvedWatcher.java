package mage.watchers.common;

import mage.abilities.Ability;
import mage.constants.WatcherScope;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.watchers.Watcher;

import java.util.HashMap;
import java.util.Map;

/**
 * @author TheElk801
 */
public class AbilityResolvedWatcher extends Watcher {

    private final Map<String, Integer> resolutionMap = new HashMap<>();

    public AbilityResolvedWatcher() {
        super(WatcherScope.GAME);
    }

    @Override
    public void watch(GameEvent event, Game game) {
        if (event.getType() == GameEvent.EventType.RESOLVING_ABILITY) {
            resolutionMap.merge(event.getTargetId().toString() + game.getState().getZoneChangeCounter(event.getSourceId()), 1, Integer::sum);
        }
    }

    @Override
    public void reset() {
        super.reset();
        resolutionMap.clear();
    }

    public static int getResolutionCount(Game game, Ability source) {
        AbilityResolvedWatcher watcher = game.getState().getWatcher(AbilityResolvedWatcher.class);
        // A card whose ability uses this count (e.g. via IfAbilityHasResolvedXTimesEffect) must
        // register an AbilityResolvedWatcher alongside it (addAbility(ability, new
        // AbilityResolvedWatcher())) - if none is registered for this game, treat it as "never
        // resolved" instead of crashing the game with a NullPointerException. Found via a card
        // that was missing that registration (SouthPoleVoyager).
        if (watcher == null) {
            return 0;
        }
        return watcher
                .resolutionMap
                .getOrDefault(source.getOriginalId().toString() + game.getState().getZoneChangeCounter(source.getSourceId()), 0);
    }
}
