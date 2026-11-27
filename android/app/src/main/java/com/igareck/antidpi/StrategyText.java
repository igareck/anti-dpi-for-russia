/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

/** Localizes catalogue display text while keeping the verified recipe data byte-for-byte intact. */
final class StrategyText {
    private static final String[][] TERMS = {
        {"Разделённый TLS-фейк", "Split TLS fake"},
        {"TLS-фейк", "TLS fake"},
        {"Фейк из текущего ClientHello", "Fake derived from the current ClientHello"},
        {"Нулевой фейк", "Zero-byte fake"},
        {"4 нулевых байта", "4 zero bytes"},
        {"нулевые фейки", "zero-byte fakes"},
        {"штатный SNI", "default SNI"},
        {"ClientHello в обратном порядке", "ClientHello in reverse order"},
        {"ClientHello по частям", "split ClientHello"},
        {"TS-опция первой", "timestamp option first"},
        {"без второго фейка", "without the second fake"},
        {"добавочный TCP-сегмент", "extra TCP segment"},
        {"неверная checksum", "invalid checksum"},
        {"перестановка после SNI", "reordering after SNI"},
        {"перестановка", "reordering"},
        {"разделение", "split"},
        {"случайный host", "random hostname"},
        {"фейковые вставки в SNI", "fake inserts in SNI"},
        {"флаги", "flags"},
        {"Именованный hostfakesplit", "Named hostfakesplit"},
        {"Реальный ClientHello", "Real ClientHello"},
        {"Большой TLS-префикс", "Large TLS prefix"},
        {"Обработка:", "Processing:"},
        {"изменённые TCP Timestamp и Sequence", "modified TCP timestamp and sequence number"},
        {"изменённые TCP ACK и Timestamp", "modified TCP ACK and timestamp"},
        {"изменённая TCP Timestamp", "modified TCP timestamp"},
        {"изменённый TCP Sequence", "modified TCP sequence number"},
        {"автоматический подбор TTL", "automatic TTL selection"},
        {"TCP-пакеты без флага ACK", "TCP packets without the ACK flag"},
        {"изменение флага SYN", "modified SYN flag"},
        {"неверная контрольная сумма", "invalid checksum"},
        {"ограничение TTL", "TTL limit"},
        {"сегментация TCP", "TCP segmentation"},
        {"перекрытие TCP-сегментов", "overlapping TCP segments"},
        {"IP-фрагментация, позиция", "IP fragmentation at offset"},
        {"IP-фрагментация", "IP fragmentation"},
        {"QUIC-фейк", "QUIC fake"},
        {"нули 16 Б", "16 zero bytes"},
        {"нули 64 Б", "64 zero bytes"},
    };

    static String english(String label) {
        for (String[] term : TERMS) label = label.replace(term[0],term[1]);
        return label;
    }

    private StrategyText() {}
}
