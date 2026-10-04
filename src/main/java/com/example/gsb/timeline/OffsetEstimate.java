package com.example.gsb.timeline;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * 某来源时钟相对基准来源的偏移估计。
 *
 * <p>约定：条目记录时间 = 真实时间 + 偏移。校正时减去偏移。
 *
 * @param source           来源名
 * @param correctable      是否可校正（与基准来源之间存在因果标记链）
 * @param pointMillis      点估计（毫秒），区间中点；不可校正时为 0
 * @param lowerBoundMillis 偏移下界（毫秒），不可校正时为空
 * @param upperBoundMillis 偏移上界（毫秒），不可校正时为空
 */
public record OffsetEstimate(
        String source,
        boolean correctable,
        long pointMillis,
        OptionalLong lowerBoundMillis,
        OptionalLong upperBoundMillis) {

    public Duration point() {
        return Duration.ofMillis(pointMillis);
    }

    static OffsetEstimate of(String source, long lower, long upper) {
        return new OffsetEstimate(source, true, (lower + upper) / 2,
                OptionalLong.of(lower), OptionalLong.of(upper));
    }

    static OffsetEstimate uncorrectable(String source) {
        return new OffsetEstimate(source, false, 0L, OptionalLong.empty(), OptionalLong.empty());
    }
}
