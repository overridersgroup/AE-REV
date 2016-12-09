package net.toyknight.aeii.system;

import net.toyknight.aeii.GameException;

/**
 * @author toyknight 12/9/2016.
 */
public class AER {

    public static Units units;
    public static Tiles tiles;

    public static Language language;

    private AER() {
    }

    public static void initialize() throws GameException {
        initializeData();
    }

    public static void initializeData() throws GameException {
        units = new Units();
        units.initialize();
        tiles = new Tiles();
        tiles.initialize();
    }

}