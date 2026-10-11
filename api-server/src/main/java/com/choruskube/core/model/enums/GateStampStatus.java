package com.choruskube.core.model.enums;

/**
 * {@code consumed}: re-applied; {@code voided}: never will be, see {@code void_reason};
 * {@code finalizing}: the run is carrying the approval out.
 */
public enum GateStampStatus {
    pending,
    consumed,
    voided,
    finalizing
}
