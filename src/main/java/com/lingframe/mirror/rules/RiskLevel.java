package com.lingframe.mirror.rules;

/**
 * 风险等级枚举，对应产品文档中的规则分级。
 * CR = CRITICAL（致命），HI = HIGH（高危），MD = MEDIUM（中危），LO = LOW（低风险）。
 */
public enum RiskLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
