package net.toyknight.aeii.robot;

import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import net.toyknight.aeii.GameContext;
import net.toyknight.aeii.entity.*;
import net.toyknight.aeii.manager.GameManager;
import net.toyknight.aeii.manager.Operation;
import net.toyknight.aeii.utils.UnitFactory;
import net.toyknight.aeii.utils.UnitToolkit;

/**
 * @author toyknight 1/12/2016.
 */
public class Robot {

    private final GameManager manager;

    private final ObjectSet<Position> assigned_positions;

    private final ObjectMap<Position, Boolean> tile_threat_status;

    private final ObjectMap<Integer, ObjectSet<Integer>> ability_map;

    private boolean prepared;

    private boolean calculating;

    private int team;

    private Action action;

    public Robot(GameManager manager) {
        this.manager = manager;
        this.assigned_positions = new ObjectSet<Position>();
        this.tile_threat_status = new ObjectMap<Position, Boolean>();
        this.ability_map = new ObjectMap<Integer, ObjectSet<Integer>>();
    }

    public void initialize() {
        prepared = false;
        calculating = false;
        assigned_positions.clear();
        ability_map.clear();
        for (Integer index : getGame().getRule().getAvailableUnits()) {
            for (int ability : UnitFactory.getSample(index).getAbilities()) {
                if (ability_map.containsKey(ability)) {
                    ability_map.get(ability).add(index);
                } else {
                    ability_map.put(ability, new ObjectSet<Integer>());
                    ability_map.get(ability).add(index);
                }
            }
        }
    }

    public GameManager getManager() {
        return manager;
    }

    public GameCore getGame() {
        return getManager().getGame();
    }

    private Action getAction() {
        return action;
    }

    private int getGold() {
        return getGame().getPlayer(team).getGold();
    }

    public boolean isCalculating() {
        return calculating;
    }

    public void calculate() {
        if (!isCalculating()) {
            new Thread(calculate_task, "robot-thread").start();
        }
    }

    private void doCalculate() {
        if (!prepared) {
            prepare();
        }
        switch (getManager().getState()) {
            case GameManager.STATE_SELECT:
                select();
                break;
            case GameManager.STATE_MOVE:
                move();
                break;
            case GameManager.STATE_ACTION:
                act();
                break;
            case GameManager.STATE_REMOVE:
                remove();
                break;
            default:
                finish();
        }
    }

    //prepare for new turn
    private void prepare() {
        team = getGame().getCurrentTeam();
        assigned_positions.clear();
        createTileThreatStatus();
        prepared = true;
    }

    //select a unit for actions, or if there's no unit available do some recruiting then end turn
    private void select() {
        action = null;
        synchronized (GameContext.RENDER_LOCK) {
            ObjectSet<Unit> units = getGame().getMap().getUnits(team);

            Unit refresher = getFirstAvailableUnitWithAbility(units, Ability.REFRESH_AURA);
            if (refresher != null && !refresher.isStandby()) {
                getManager().doSelect(refresher.getX(), refresher.getY());
                return;
            }

            Unit healer = getFirstAvailableUnitWithAbility(units, Ability.HEALER);
            if (healer != null && !healer.isStandby()) {
                getManager().doSelect(healer.getX(), healer.getY());
                return;
            }

            for (Position position : getGame().getMap().getCastlePositions()) {
                Unit unit = getGame().getMap().getUnit(position);
                if (isUnitAvailable(unit)) {
                    getManager().doSelect(unit.getX(), unit.getY());
                    return;
                }
            }

            for (Unit unit : units) {
                if (!unit.isStandby()) {
                    getManager().doSelect(unit.getX(), unit.getY());
                    return;
                }
            }
        }
        if (!recruit()) {
            finish();
        }
    }

    //recruit a new unit, returns false if no unit can be recruited
    private boolean recruit() {
        synchronized (GameContext.RENDER_LOCK) {
            Position recruit_position = getPreferredRecruitPosition();
            if (recruit_position == null) {
                return false;
            } else {
                if (!getGame().isCommanderAlive(team) && getGame().getCommander(team).getPrice() <= getGold()) {
                    getManager().doBuyUnit(UnitFactory.getCommanderIndex(), recruit_position.x, recruit_position.y);
                    return true;
                } else {
                    ObjectSet<Unit> enemy_units = getGame().getEnemyUnits(team);
                    int enemy_average_physical_defence = getAveragePhysicalDefence(enemy_units);
                    int enemy_average_magic_defence = getAverageMagicDefence(enemy_units);
                    int enemy_average_mobility = getAverageMobility(enemy_units) + 1;

                    int preferred_attack_type = enemy_average_physical_defence <= enemy_average_magic_defence ?
                            Unit.ATTACK_PHYSICAL : Unit.ATTACK_MAGIC;
                    int preferred_ability = getPreferredAbility();
                    int unit_index = getPreferredRecruitment(
                            recruit_position, preferred_attack_type, preferred_ability, enemy_average_mobility);
                    if (unit_index >= 0 &&
                            getManager().canBuy(unit_index, team, recruit_position.x, recruit_position.y)) {
                        getManager().doBuyUnit(unit_index, recruit_position.x, recruit_position.y);
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        }
    }

    private void move() {
        if (getAction() == null) {
            calculateAction();
        } else {
            if (!getAction().isMoved()) {
                Position action_position = action.getPosition();
                getManager().doMove(action_position.x, action_position.y);
                getAction().setMoved(true);
            }
        }
    }

    private void act() {
        synchronized (GameContext.RENDER_LOCK) {
            if (!getAction().isActed()) {
                Position target = getAction().getTarget();
                switch (getAction().getType()) {
                    case Operation.OCCUPY:
                        getManager().doOccupy();
                        break;
                    case Operation.REPAIR:
                        getManager().doRepair();
                        break;
                    case Operation.ATTACK:
                        getManager().doAttack(target.x, target.y);
                        break;
                    case Operation.HEAL:
                        getManager().doHeal(target.x, target.y);
                        break;
                    case Operation.SUMMON:
                        getManager().doSummon(target.x, target.y);
                        break;
                    case Operation.STANDBY:
                    default:
                        getManager().doStandbySelectedUnit();
                }
                getAction().setActed(true);
            }
        }
    }

    private void remove() {
        synchronized (GameContext.RENDER_LOCK) {
            Unit selected_unit = getManager().getSelectedUnit();
            ObjectSet<Position> movable_positions =
                    getManager().getPositionGenerator().createMovablePositions(selected_unit);
            Position target = getPreferredStandbyPosition(getManager().getSelectedUnit(), movable_positions);
            getManager().doMove(target.x, target.y);
        }
    }

    private void finish() {
        getManager().doEndTurn();
        prepared = false;
    }

    private void createTileThreatStatus() {
        tile_threat_status.clear();
        ObjectSet<Unit> enemy_units;
        synchronized (GameContext.RENDER_LOCK) {
            enemy_units = getGame().getEnemyUnits(team);
        }
        for (Unit enemy : enemy_units) {
            if (enemy.hasAbility(Ability.COMMANDER) || enemy.hasAbility(Ability.CONQUEROR)) {
                synchronized (GameContext.RENDER_LOCK) {
                    ObjectSet<Position> movable_positions =
                            getManager().getPositionGenerator().createMovablePositions(enemy, true);
                    for (Position position : movable_positions) {
                        Tile tile = getGame().getMap().getTile(position);
                        if (isMyCastle(tile) && enemy.hasAbility(Ability.COMMANDER)) {
                            tile_threat_status.put(position, true);
                        }
                        if (isMyVillage(tile) && enemy.hasAbility(Ability.CONQUEROR)) {
                            tile_threat_status.put(position, true);
                        }
                    }
                }
            }
        }
    }

    private void calculateAction() {
        Unit selected_unit = getManager().getSelectedUnit();
        synchronized (GameContext.RENDER_LOCK) {
            if (selected_unit.isStatic()) {
                Position standby_position = getGame().getMap().getPosition(selected_unit);
                if (getManager().hasEnemyWithinRange(selected_unit)) {
                    Position attack_target = null;
                    for (Position position :
                            getManager().getPositionGenerator().createAttackablePositions(selected_unit, false)) {
                        if (getGame().isEnemy(selected_unit, getGame().getMap().getUnit(position))) {
                            attack_target = position;
                            break;
                        }
                    }
                    if (attack_target == null) {
                        submitAction(new Action(standby_position, standby_position, Operation.STANDBY));
                    } else {
                        submitAction(new Action(standby_position, attack_target, Operation.ATTACK));
                    }
                } else {
                    submitAction(new Action(standby_position, standby_position, Operation.STANDBY));
                }
                return;
            }
        }

        Position current_position = getGame().getMap().getPosition(selected_unit);
        ObjectSet<Position> movable_positions;
        synchronized (GameContext.RENDER_LOCK) {
            movable_positions = getManager().getPositionGenerator().createMovablePositions(selected_unit);
        }

        ObjectSet<Action> actions = new ObjectSet<Action>();
        Unit temp_selected_unit = UnitFactory.cloneUnit(selected_unit);
        for (Position position : movable_positions) {
            if (!selected_unit.hasAbility(Ability.HEAVY_MACHINE) ||
                    (selected_unit.hasAbility(Ability.HEAVY_MACHINE) && position.equals(current_position))) {
                ObjectSet<Position> target_positions = getManager().getPositionGenerator().createPositionsWithinRange(
                        position.x, position.y, selected_unit.getMinAttackRange(), selected_unit.getMaxAttackRange());
                for (Position target_position : target_positions) {
                    Unit target = getGame().getMap().getUnit(target_position);
                    if (isEnemy(target)) {
                        actions.add(new Action(position, target_position, Operation.ATTACK));
                    } else {
                        Tile tile = getGame().getMap().getTile(target_position);
                        if (target == null && selected_unit.hasAbility(Ability.DESTROYER) && isEnemyVillage(tile)) {
                            actions.add(new Action(position, target_position, Operation.ATTACK));
                        }
                    }
                    if (isAlly(target)) {
                        temp_selected_unit.setX(position.x);
                        temp_selected_unit.setY(position.y);
                        if (selected_unit.hasAbility(Ability.HEALER) && getGame().canHeal(temp_selected_unit, target)) {
                            if (UnitToolkit.isTheSameUnit(selected_unit, target)) {
                                actions.add(new Action(position, position, Operation.HEAL));
                            } else {
                                actions.add(new Action(position, target_position, Operation.HEAL));
                            }
                        }
                    }
                    synchronized (GameContext.RENDER_LOCK) {
                        if (getGame().getMap().isTomb(target_position)
                                && (selected_unit.hasAbility(Ability.NECROMANCER))) {
                            actions.add(new Action(position, target_position, Operation.SUMMON));
                        }
                    }
                }
                if (canOccupy(position, selected_unit)) {
                    actions.add(new Action(position, position, Operation.OCCUPY));
                }
                if (canRepair(position, selected_unit)) {
                    actions.add(new Action(position, position, Operation.REPAIR));
                }
            }
        }

        if (actions.size > 0) {
            Action preferred_action;
            if ((preferred_action = getPreferredAction(actions)) == null) {
                synchronized (GameContext.RENDER_LOCK) {
                    Position standby_position = getPreferredStandbyPosition(selected_unit, movable_positions);
                    submitAction(new Action(standby_position, standby_position, Operation.STANDBY));
                }
            } else {
                submitAction(preferred_action);
            }
        } else {
            synchronized (GameContext.RENDER_LOCK) {
                if (isThreatened(current_position) && movable_positions.contains(current_position)) {
                    submitAction(new Action(current_position, current_position, Operation.STANDBY));
                    return;
                }
                if (selected_unit.hasAbility(Ability.CONQUEROR)) {
                    Position nearest_village_position = getNearestCapturableVillagePosition(selected_unit);
                    if (nearest_village_position != null) {
                        Position next_position = getManager().getPositionGenerator().getNextPositionToTarget(
                                selected_unit, nearest_village_position);
                        assigned_positions.add(nearest_village_position);
                        submitAction(new Action(next_position, next_position, Operation.STANDBY));
                        return;
                    }
                }
                Unit nearest_enemy_commander;
                if ((nearest_enemy_commander = getNearestEnemyCommander(selected_unit)) == null) {
                    if (selected_unit.hasAbility(Ability.COMMANDER)) {
                        Position nearest_castle_position = getNearestCapturableCastlePosition(selected_unit);
                        if (nearest_castle_position == null) {
                            Position standby_position = getPreferredStandbyPosition(selected_unit, movable_positions);
                            submitAction(new Action(standby_position, standby_position, Operation.STANDBY));
                        } else {
                            Position next_position = getManager().getPositionGenerator().getNextPositionToTarget(
                                    selected_unit, nearest_castle_position);
                            submitAction(new Action(next_position, next_position, Operation.STANDBY));
                        }
                    } else {
                        Unit nearest_enemy;
                        if ((nearest_enemy = getNearestEnemy(selected_unit)) == null) {
                            Position standby_position = getPreferredStandbyPosition(selected_unit, movable_positions);
                            submitAction(new Action(standby_position, standby_position, Operation.STANDBY));
                        } else {
                            Position next_position = getManager().getPositionGenerator().getNextPositionToTarget(
                                    selected_unit, getGame().getMap().getPosition(nearest_enemy));
                            submitAction(new Action(next_position, next_position, Operation.STANDBY));
                        }
                    }
                } else {
                    Position next_position = getManager().getPositionGenerator().getNextPositionToTarget(
                            selected_unit, getGame().getMap().getPosition(nearest_enemy_commander));
                    submitAction(new Action(next_position, next_position, Operation.STANDBY));
                }
            }
        }
    }

    private void submitAction(Action action) {
        this.action = action;
        if (action.getType() == Operation.REPAIR) {
            assigned_positions.add(action.getPosition());
        }
        System.out.println("go to " + "[" + action.getPosition().x + ", " + action.getPosition().y + "]");
        switch (action.getType()) {
            case Operation.ATTACK:
                System.out.print("attack");
                break;
            case Operation.HEAL:
                System.out.print("heal");
                break;
            case Operation.SUMMON:
                System.out.print("summon");
                break;
            case Operation.OCCUPY:
                System.out.print("occupy");
                break;
            case Operation.REPAIR:
                System.out.print("repair");
                break;
            case Operation.STANDBY:
                System.out.print("standby");
                break;
        }
        System.out.print(" ");
        System.out.println(action.getTarget() == null ? "null" : String.format("[%d, %d]", action.getTarget().x, action.getTarget().y));
    }

    private boolean canRepair(Position position, Unit unit) {
        Unit target = getGame().getMap().getUnit(position);
        return getGame().canRepair(unit, position.x, position.y)
                && (target == null || UnitToolkit.isTheSameUnit(unit, target));
    }

    private boolean canOccupy(Position position, Unit unit) {
        if (!getGame().isAlly(team, getGame().getMap().getTile(position).getTeam())) {
            Unit target = getGame().getMap().getUnit(position);
            return getGame().canOccupy(unit, position.x, position.y)
                    && (target == null || UnitToolkit.isTheSameUnit(unit, target));
        } else {
            return false;
        }
    }

    private int getActionScore(Action action) {
        synchronized (GameContext.RENDER_LOCK) {
            Unit selected_unit = UnitFactory.cloneUnit(getManager().getSelectedUnit());
            selected_unit.setX(action.getPosition().x);
            selected_unit.setY(action.getPosition().y);

            int score = 0;
            switch (action.getType()) {
                case Operation.OCCUPY:
                    Tile tile = getGame().getMap().getTile(action.getPosition());
                    if (tile != null && tile.isCastle()) {
                        score += 20000;
                    }
                    if (tile != null && tile.isVillage()) {
                        score += 10000;
                    }
                    break;
                case Operation.REPAIR:
                    score += 5000;
                    break;
                case Operation.SUMMON:
                    score += 1000;
                    break;
                case Operation.HEAL:
                    Unit target = getGame().getMap().getUnit(action.getTarget());
                    if (isAlly(target)) {
                        score += 10 * (target.getAttack() * target.getCurrentHp() / target.getMaxHp() + getMobility(target) * 5);
                    } else {
                        score += 0;
                    }
                    break;
                case Operation.ATTACK:
                    target = UnitFactory.cloneUnit(getGame().getMap().getUnit(action.getTarget()));
                    if (isEnemy(target)) {
                        score += target.getPrice() / 20 + getAttackScore(selected_unit, target);
                    } else {
                        tile = getGame().getMap().getTile(action.getTarget());
                        if (target == null && isEnemyVillage(tile)) {
                            score += 5000;
                        } else {
                            score += 0;
                        }
                    }
                    break;
                default:
                    score += 0;
            }
            if (!selected_unit.hasAbility(Ability.CHARGER)) {
                score += getStandbyScore(selected_unit, action.getPosition());
            }

            Position current_position = getGame().getMap().getPosition(getManager().getSelectedUnit());
            if (isThreatened(current_position) && !action.getPosition().equals(current_position)) {
                Tile tile = getGame().getMap().getTile(current_position);
                if (isMyCastle(tile)) {
                    score -= 20000;
                }
                if (isMyVillage(tile)) {
                    score -= 10000;
                }
            }
            return score;
        }
    }

    private int getAttackScore(Unit attacker, Unit defender) {
        int score = 0;
        int attack_damage = getManager().getUnitToolkit().getDamage(attacker, defender, false);
        defender.changeCurrentHp(-attack_damage);
        if (defender.isCommander()) {
            score += defender.getCurrentHp() <= 0 ? defender.getPrice() * 20 : attack_damage * defender.getPrice() / 10;
        } else {
            score += defender.getCurrentHp() <= 0 ? defender.getPrice() * 10 : attack_damage * defender.getPrice() / 20;
        }
        if (defender.getStatus() == null) {
            UnitToolkit.attachAttackStatus(attacker, defender);
            if (Status.isDebuff(defender.getStatus())) {
                switch (defender.getStatus().getType()) {
                    case Status.POISONED:
                        score += defender.getPrice() / 4;
                        break;
                    case Status.BLINDED:
                        score += defender.getPrice() / 2;
                        break;
                }
            }
        }
        if (getGame().canCounter(attacker, defender)) {
            int counter_damage = getManager().getUnitToolkit().getDamage(defender, attacker, false);
            attacker.changeCurrentHp(-counter_damage);
            if (attacker.isCommander()) {
                score -= attacker.getCurrentHp() <= 0 ?
                        attacker.getPrice() * 20 : counter_damage * attacker.getPrice() / 10;
            } else {
                score -= attacker.getCurrentHp() <= 0 ?
                        attacker.getPrice() * 10 : counter_damage * attacker.getPrice() / 20;
            }
            if (attacker.getStatus() == null) {
                UnitToolkit.attachAttackStatus(defender, attacker);
                if (Status.isDebuff(attacker.getStatus())) {
                    switch (attacker.getStatus().getType()) {
                        case Status.POISONED:
                            score -= attacker.getPrice() / 4;
                            break;
                        case Status.BLINDED:
                            score -= attacker.getPrice() / 2;
                            break;
                    }
                }
            }
        }
        return score;
    }

    private int getAverageAllyDistance(Position position) {
        int ally_count = 0;
        int total_distance = 0;
        for (Unit unit : getGame().getMap().getUnits()) {
            if (getGame().isAlly(team, unit.getTeam())) {
                ally_count++;
                total_distance += getDistance(getGame().getMap().getPosition(unit), position);
            }
        }
        return ally_count == 0 ? 999 : total_distance / ally_count;
    }

    private int getAverageEnemyDistance(Position position) {
        int enemy_count = 0;
        int total_distance = 0;
        for (Unit unit : getGame().getMap().getUnits()) {
            if (getGame().isEnemy(team, unit.getTeam())) {
                enemy_count++;
                total_distance += getDistance(getGame().getMap().getPosition(unit), position);
            }
        }
        return enemy_count == 0 ? 999 : total_distance / enemy_count;
    }

    private int getAverageMagicDefence(ObjectSet<Unit> units) {
        int unit_number = 0;
        int unit_total_magic_defence = 0;
        for (Unit unit : units) {
            unit_number++;
            unit_total_magic_defence += unit.getMagicDefence();
        }
        return unit_number > 0 ? unit_total_magic_defence / unit_number : 0;
    }

    private int getAverageMobility(ObjectSet<Unit> units) {
        int unit_number = 0;
        int unit_total_mobility = 0;
        for (Unit unit : units) {
            unit_number++;
            unit_total_mobility += getMobility(unit);
        }
        return unit_number > 0 ? unit_total_mobility / unit_number : 0;
    }

    private int getAveragePhysicalDefence(ObjectSet<Unit> units) {
        int unit_number = 0;
        int unit_total_physical_defence = 0;
        for (Unit unit : units) {
            unit_number++;
            unit_total_physical_defence += unit.getPhysicalDefence();
        }
        return unit_number > 0 ? unit_total_physical_defence / unit_number : 0;
    }

    private int getDistance(Position p1, Position p2) {
        return Math.abs(p1.x - p2.x) + Math.abs(p1.y - p2.y);
    }

    private Unit getFirstAvailableUnitWithAbility(ObjectSet<Unit> units, int ability) {
        for (Unit unit : units) {
            if (!unit.isStandby() && unit.hasAbility(ability)) {
                return unit;
            }
        }
        return null;
    }

    private int getMobility(Unit unit) {
        int bonus = 0;
        if (unit.hasAbility(Ability.CRAWLER)) {
            bonus = 1;
        }
        if (unit.hasAbility(Ability.AIR_FORCE)) {
            bonus = 2;
        }
        int base_mobility = unit.hasAbility(Ability.HEAVY_MACHINE) ?
                unit.getMaxAttackRange() + bonus : unit.getMovementPoint() - 2 + unit.getMaxAttackRange() + bonus;
        return base_mobility + bonus;
    }

    private Position getNearestCapturableCastlePosition(Unit unit) {
        Position castle_position = null;
        int min_distance = Integer.MAX_VALUE;
        for (Position position : getGame().getMap().getCastlePositions()) {
            Tile tile = getGame().getMap().getTile(position);
            if (!getGame().isAlly(team, tile.getTeam())) {
                int distance = getDistance(getGame().getMap().getPosition(unit), position);
                if (distance < min_distance) {
                    castle_position = position;
                    min_distance = distance;
                }
            }
        }
        return castle_position;
    }

    private Position getNearestCapturableVillagePosition(Unit unit) {
        Position village_position = null;
        int min_distance = Integer.MAX_VALUE;
        for (Position position : getGame().getMap().getVillagePositions()) {
            Tile tile = getGame().getMap().getTile(position);
            if (!assigned_positions.contains(position) && !getGame().isAlly(team, tile.getTeam())) {
                int distance = getDistance(getGame().getMap().getPosition(unit), position);
                if (distance < min_distance) {
                    village_position = position;
                    min_distance = distance;
                }
            }
        }
        return village_position;
    }

    private Unit getNearestEnemy(Unit selected_unit) {
        Unit enemy = null;
        int min_distance = Integer.MAX_VALUE;
        for (Unit unit : getGame().getMap().getUnits()) {
            int distance =
                    getDistance(getGame().getMap().getPosition(unit), getGame().getMap().getPosition(selected_unit));
            if (isEnemy(unit) && distance < min_distance) {
                enemy = unit;
                min_distance = distance;
            }
        }
        return enemy;
    }

    private Unit getNearestEnemyCommander(Unit selected_unit) {
        Unit enemy_commander = null;
        int min_distance = Integer.MAX_VALUE;
        for (Unit unit : getGame().getMap().getUnits()) {
            int distance =
                    getDistance(getGame().getMap().getPosition(unit), getGame().getMap().getPosition(selected_unit));
            if (isEnemyCommander(unit) && distance < min_distance) {
                enemy_commander = unit;
                min_distance = distance;
            }
        }
        return enemy_commander;
    }

    private int getPreferredAbility() {
        ObjectSet<Unit> enemy_units = getGame().getEnemyUnits(team);
        ObjectSet<Unit> ally_units = getGame().getAllyUnits(team);
        if (getUnitCountWithAbility(ally_units, Ability.CONQUEROR) < 4) {
            return Ability.CONQUEROR;
        }
        if (getGame().getMap().getTombs().size > 1
                && getUnitCountWithAbility(ally_units, Ability.NECROMANCER) < 1
                && ability_map.containsKey(Ability.NECROMANCER)) {
            return Ability.NECROMANCER;
        }
        if (getUnitCountWithAbility(ally_units, Ability.HEALER) < 1 && ability_map.containsKey(Ability.HEALER)) {
            return Ability.HEALER;
        }
        if (getUnhealthyUnitCount(ally_units) >= 5
                && getUnitCountWithAbility(ally_units, Ability.REFRESH_AURA) < 1
                && ability_map.containsKey(Ability.REFRESH_AURA)) {
            return Ability.REFRESH_AURA;
        }
        if (getUnitCountWithAbility(enemy_units, Ability.AIR_FORCE) > 0
                && getUnitCountWithAbility(ally_units, Ability.MARKSMAN) < 2
                && ability_map.containsKey(Ability.MARKSMAN)) {
            return Ability.MARKSMAN;
        }
        return -1;
    }

    private Action getPreferredAction(ObjectSet<Action> actions) {
        Action preferred_action = null;
        int max_action_score = 0;
        for (Action action : actions) {
            int score = getActionScore(action);
            if (score > max_action_score) {
                preferred_action = action;
                max_action_score = score;
            }
        }
        return preferred_action;
    }

    private int getPreferredRecruitment(
            Position recruit_position, int preferred_attack_type, int preferred_ability, int preferred_mobility) {
        if (getGame().getMap().getUnit(recruit_position) == null
                && isThreatened(recruit_position) && getGold() < getSecondExpensiveUnitPrice()) {
            return getGame().getRule().getAvailableUnits().first();
        }
        if (preferred_ability >= 0 && ability_map.containsKey(preferred_ability)) {
            int preferred_index = -1;
            boolean mobility_reached = false;
            for (int index : ability_map.get(preferred_ability)) {
                if (!UnitFactory.isCommander(index) || !getGame().isCommanderAlive(team)) {
                    Unit sample =
                            UnitFactory.isCommander(index) ? getGame().getCommander(team) : UnitFactory.getSample(index);
                    if (preferred_index < 0) {
                        preferred_index = index;
                        mobility_reached = getMobility(sample) >= preferred_mobility;
                    } else {
                        if (!mobility_reached && getMobility(sample) >= preferred_mobility) {
                            preferred_index = index;
                            mobility_reached = true;
                        }
                    }
                }
            }
            return preferred_index;
        } else {
            int preferred_index = -1;
            int max_price = Integer.MIN_VALUE;
            for (Integer index : getGame().getRule().getAvailableUnits()) {
                if (!UnitFactory.isCommander(index) || !getGame().isCommanderAlive(team)) {
                    Unit sample =
                            UnitFactory.isCommander(index) ? getGame().getCommander(team) : UnitFactory.getSample(index);
                    if (sample.getAttackType() == preferred_attack_type && sample.getPrice() <= getGold()
                            && getMobility(sample) >= preferred_mobility && sample.getPrice() > max_price) {
                        max_price = sample.getPrice();
                        preferred_index = index;
                    }
                }
            }
            return preferred_index;
        }
    }

    private Position getPreferredRecruitPosition() {
        Position preferred_position = null;
        for (Position castle_position : getGame().getMap().getCastlePositions(team)) {
            if (isCastleAvailable(castle_position)) {
                if (isThreatened(castle_position)) {
                    return castle_position;
                } else {
                    if (compareRecruitPosition(castle_position, preferred_position) > 0) {
                        preferred_position = castle_position;
                    }
                }
            }
        }
        return preferred_position;
    }

    private Position getPreferredStandbyPosition(Unit unit, ObjectSet<Position> movable_positions) {
        Position standby_position = movable_positions.first();
        int max_standby_score = Integer.MIN_VALUE;
        for (Position position : movable_positions) {
            int score = getStandbyScore(unit, position);
            if (score > max_standby_score) {
                standby_position = position;
                max_standby_score = score;
            }
        }
        return standby_position;
    }

    private int getSecondExpensiveUnitPrice() {
        if (getGame().getRule().getAvailableUnits().size >= 2) {
            return getGame().getUnitPrice(getGame().getRule().getAvailableUnits().get(1), team);
        } else {
            return getGame().getUnitPrice(getGame().getRule().getAvailableUnits().get(0), team);
        }
    }

    private int getCheapestUnitPrice() {
        return getGame().getUnitPrice(getGame().getRule().getAvailableUnits().get(0), team);
    }

    private int getStandbyScore(Unit unit, Position standby_position) {
        int score = 0;
        score += getAverageEnemyDistance(standby_position) * 20;
        score -= getAverageAllyDistance(standby_position) * 10;
        score += getManager().getUnitToolkit().getTileDefenceBonus(
                unit, getGame().getMap().getTileIndex(standby_position)) * 5;
        Tile tile = getGame().getMap().getTile(standby_position);
        score += getManager().getUnitToolkit().getTerrainHeal(unit, tile) * 10;
        if (getGame().getMap().isTomb(standby_position)) {
            if (!unit.hasAbility(Ability.UNDEAD) && !unit.hasAbility(Ability.NECROMANCER) && unit.getStatus() == null) {
                score -= unit.getPrice() / 4;
            }
            if (unit.hasAbility(Ability.UNDEAD)) {
                score += 200;
            }
        }
        if (isEnemyCastle(tile)) {
            score -= 50 * unit.getPrice() / 20;
        }
        if (isMyCastle(tile) && !isMyCommander(unit)) {
            score -= 5000;
        }
        if (isThreatened(standby_position)) {
            if (tile.isCastle() && getGold() < getCheapestUnitPrice()) {
                score += 20000;
            }
            if (tile.isVillage()) {
                score += 10000;
            }
        }
        if (unit.hasAbility(Ability.SLOWING_AURA)
                || unit.hasAbility(Ability.ATTACK_AURA) || unit.hasAbility(Ability.REFRESH_AURA)) {
            ObjectSet<Position> aura_positions = getManager().getPositionGenerator().createPositionsWithinRange(
                    standby_position.x, standby_position.y, 0, 2);
            for (Position position : aura_positions) {
                Unit target = getGame().getMap().getUnit(position);
                if (unit.hasAbility(Ability.SLOWING_AURA) && isEnemy(target) && target.getStatus() == null) {
                    score += target.getPrice() / 4;
                }
                if (unit.hasAbility(Ability.ATTACK_AURA) && isAlly(target) && target.getStatus() == null) {
                    score += target.getPrice() / 4;
                }
                if (unit.hasAbility(Ability.REFRESH_AURA) && isAlly(target)) {
                    if (target.getCurrentHp() < target.getMaxHp()) {
                        score += target.getPrice() / 4;
                    }
                    if (Status.isDebuff(target.getStatus())) {
                        score += target.getPrice() / 5;
                    }
                }
            }
        }
        return score;
    }

    private int getUnhealthyUnitCount(ObjectSet<Unit> units) {
        int count = 0;
        for (Unit unit : units) {
            if (Status.isDebuff(unit.getStatus()) || unit.getCurrentHp() < unit.getMaxHp()) {
                count++;
            }
        }
        return count;
    }


    private int getUnitCountWithAbility(ObjectSet<Unit> units, int ability) {
        int count = 0;
        for (Unit unit : units) {
            if (unit.hasAbility(ability)) {
                count++;
            }
        }
        return count;
    }

    private boolean isAlly(Unit unit) {
        return unit != null && unit.getTeam() == team;
    }

    private boolean isCastleAvailable(Position position) {
        Tile tile = getGame().getMap().getTile(position);
        Unit unit = getGame().getMap().getUnit(position);
        return isMyCastle(tile) && (unit == null || isMyCommander(unit));
    }

    private boolean isEnemy(Unit unit) {
        return unit != null && getGame().isEnemy(team, unit.getTeam());
    }

    private boolean isEnemyCastle(Tile tile) {
        return tile != null && tile.isCastle() && getGame().isEnemy(team, tile.getTeam());
    }

    private boolean isEnemyCommander(Unit unit) {
        return isEnemy(unit) && unit.isCommander();
    }

    private boolean isEnemyVillage(Tile tile) {
        return tile != null && tile.isVillage() && getGame().isEnemy(team, tile.getTeam());
    }

    private boolean isMyCastle(Tile tile) {
        return tile != null && tile.isCastle() && tile.getTeam() == team;
    }

    private boolean isMyCommander(Unit unit) {
        return unit != null && unit.isCommander() && unit.getTeam() == team;
    }

    private boolean isMyVillage(Tile tile) {
        return tile != null && tile.isVillage() && tile.getTeam() == team;
    }

    private boolean isThreatened(Position position) {
        return tile_threat_status.get(position, false);
    }

    private boolean isUnitAvailable(Unit unit) {
        return unit != null && unit.getTeam() == team && !unit.isStandby();
    }

    private int compareRecruitPosition(Position position_a, Position position_b) {
        if (position_a == null) {
            return -1;
        }
        if (position_b == null) {
            return 1;
        }
        return getAverageEnemyDistance(position_a) > getAverageEnemyDistance(position_b) ? 1 : -1;
    }

    private final Runnable calculate_task = new Runnable() {
        @Override
        public void run() {
            try {
                calculating = true;
                Thread.sleep(150);
                doCalculate();
                calculating = false;
            } catch (InterruptedException ignored) {
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    };

}
