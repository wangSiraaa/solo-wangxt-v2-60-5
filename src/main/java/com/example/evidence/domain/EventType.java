package com.example.evidence.domain;

/** Business events recorded on a plan's evidence chain. */
public enum EventType {
    /** Snapshot of a pre-existing historical plan (migration anchor / genesis). */
    ANCHOR,
    /** Plan created inside this system (genesis event). */
    CREATED,
    /** 开工: DRAFT -> ISSUED */
    STARTED,
    /** 确认: ISSUED -> CONFIRMED */
    CONFIRMED,
    /** 模拟回执: CONFIRMED -> RECEIPTED */
    RECEIPTED,
    /** 拒绝结果: CONFIRMED -> REJECTED */
    REJECTED,
    /** 销记: RECEIPTED -> WRITTEN_OFF */
    WRITTEN_OFF
}
