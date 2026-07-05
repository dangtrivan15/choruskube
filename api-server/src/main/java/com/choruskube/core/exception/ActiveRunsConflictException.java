package com.choruskube.core.exception;

public class ActiveRunsConflictException extends ConflictException {

    private final long activeRunCount;

    public ActiveRunsConflictException(long count) {
        super(count + " active run(s) in this org would lose GitHub access");
        this.activeRunCount = count;
    }

    public long getActiveRunCount() {
        return activeRunCount;
    }
}
