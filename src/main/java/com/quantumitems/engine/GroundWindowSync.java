package com.quantumitems.engine;

/**
 * Implemented onto ItemEntity by mixin: forces the SynchedEntityData sync of
 * the carried stack so clients see pool changes in windows lying on the ground.
 */
public interface GroundWindowSync {
    void quantumitems$forceItemSync();
}
