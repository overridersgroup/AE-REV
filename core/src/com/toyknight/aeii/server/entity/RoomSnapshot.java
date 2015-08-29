package com.toyknight.aeii.server.entity;

import java.io.Serializable;

/**
 * Created by toyknight on 8/27/2015.
 */
public class RoomSnapshot implements Serializable {

    private static final long serialVersionUID = 8272015L;

    private final long room_number;
    private final String room_name;
    private final String map_name;
    private final int capacity;
    private final int remaining;

    public RoomSnapshot(long room_number, String room_name, String map_name, int capacity, int remaining) {
        this.room_number = room_number;
        this.room_name = room_name;
        this.map_name = map_name;
        this.capacity = capacity;
        this.remaining = remaining;
    }

    public long getRoomNumber() {
        return room_number;
    }

    public String getRoomName() {
        return room_name;
    }

    public String getMapName() {
        return map_name;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getRemaining() {
        return remaining;
    }

    @Override
    public String toString() {
        return getRoomName() + "    |    Map: " + getMapName() + "    |    (" + (getCapacity() - getRemaining()) + "/" + getCapacity() + ")";
    }

}
