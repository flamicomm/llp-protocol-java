package com.flamingo.llp.monitor.model;

import com.flamingo.comm.llp.core.LLPFrame;

public class FrameEvent {

    private final Direction direction;
    private final LLPFrame frame;
    private final long timestamp;

    public FrameEvent(Direction direction, LLPFrame frame) {
        this.direction = direction;
        this.frame = frame;
        this.timestamp = System.currentTimeMillis();
    }

    public Direction getDirection() { return direction; }
    public LLPFrame getFrame() { return frame; }
    public long getTimestamp() { return timestamp; }
}